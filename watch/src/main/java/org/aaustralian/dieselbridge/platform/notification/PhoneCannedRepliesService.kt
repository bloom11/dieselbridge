// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.notification

import org.aaustralian.dieselbridge.data.CannedResponsesStore
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.state.DieselStateKey
import org.aaustralian.dieselbridge.platform.state.DieselStatePublisher

data class PhoneCannedRepliesState(
    val replies: List<String>,
    val updatedAtMs: Long,
)

/**
 * Diesel owner for the bounded canned-reply list supplied by the phone.
 *
 * CannedResponsesStore remains the legacy Compose projection. The public
 * contract is deliberately bounded so it is safe to encode as one state value
 * without waiting for the M8.4 collection/history API.
 */
class PhoneCannedRepliesService(
    private val platform: DieselPlatform,
    private val project:
        (List<String>) -> Unit =
        CannedResponsesStore::set,
    private val clockMs: () -> Long =
        System::currentTimeMillis,
) : AutoCloseable {
    private val lock =
        Any()

    private val publisher:
        DieselStatePublisher<PhoneCannedRepliesState> =
        platform.states.registerPublisher(
            key =
                STATE_KEY,
            ownerId =
                OWNER_ID,
        )

    init {
        val initial =
            normalize(
                CannedResponsesStore
                    .state
                    .value,
            )

        check(
            publisher.set(
                PhoneCannedRepliesState(
                    replies =
                        initial,
                    updatedAtMs =
                        clockMs(),
                ),
            ),
        )
    }

    fun update(
        replies: List<String>,
    ) {
        val normalized =
            normalize(
                replies,
            )

        synchronized(lock) {
            check(
                publisher.set(
                    PhoneCannedRepliesState(
                        replies =
                            normalized,
                        updatedAtMs =
                            clockMs(),
                    ),
                ),
            ) {
                "Phone canned replies publisher is closed"
            }
        }

        project(
            normalized,
        )
    }

    override fun close() {
        publisher.close()
    }

    companion object {
        const val OWNER_ID =
            "platform.phone.notification.canned_replies"

        const val MAX_REPLIES =
            32

        const val MAX_REPLY_CHARS =
            256

        val STATE_KEY =
            DieselStateKey(
                id =
                    "phone.notification.canned_replies",
                valueClass =
                    PhoneCannedRepliesState::class.java,
            )

        private fun normalize(
            replies: List<String>,
        ): List<String> =
            replies
                .take(
                    MAX_REPLIES,
                )
                .map {
                    it.take(
                        MAX_REPLY_CHARS,
                    )
                }
    }
}
