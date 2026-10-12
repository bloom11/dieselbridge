// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.notification

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.data.NotificationActions
import org.aaustralian.dieselbridge.data.NotificationStore
import org.aaustralian.dieselbridge.data.WatchNotification
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneNotificationServiceTest {
    @Before
    fun setup() {
        NotificationStore.clear()
    }

    @After
    fun teardown() {
        NotificationStore.clear()
    }

    @Test
    fun inboundMutationsPublishCurrentSummary() =
        runTest {
            val platform = DieselPlatform(scope = this)
            val service =
                PhoneNotificationService(
                    platform = platform,
                    sendAction = { _, _, _ -> true },
                    clockMs = { 9_000L },
                )

            try {
                service.receive(notification(1L))
                service.receive(notification(2L))

                val summary =
                    requireNotNull(
                        platform.states.current(
                            PhoneNotificationService.SUMMARY_KEY,
                        ),
                    ).value

                assertEquals(2, summary.activeCount)
                assertEquals(2L, summary.latest?.id)

                service.removeFromPhone(2L)

                val afterRemove =
                    requireNotNull(
                        platform.states.current(
                            PhoneNotificationService.SUMMARY_KEY,
                        ),
                    ).value

                assertEquals(1, afterRemove.activeCount)
                assertEquals(1L, afterRemove.latest?.id)
            } finally {
                service.close()
                platform.transports.close()
            }
        }

    @Test
    fun dismissAndReplyShareOneBoundedActionOwner() =
        runTest {
            val sent = mutableListOf<Triple<Long, String, String?>>()
            val platform = DieselPlatform(scope = this)
            val service =
                PhoneNotificationService(
                    platform = platform,
                    sendAction = { id, action, reply ->
                        sent += Triple(id, action, reply)
                        true
                    },
                )

            try {
                service.receive(notification(7L, replyable = true))

                val replyResult =
                    platform.actions.dispatch(
                        PhoneNotificationService.REPLY_KEY,
                        PhoneNotificationReplyRequest(
                            id = 7L,
                            text = "hello",
                        ),
                    )

                assertTrue(replyResult is DieselActionResult.Success)
                assertEquals(
                    Triple(
                        7L,
                        NotificationActions.ACTION_REPLY,
                        "hello",
                    ),
                    sent.last(),
                )

                val dismissResult =
                    platform.actions.dispatch(
                        PhoneNotificationService.DISMISS_KEY,
                        PhoneNotificationDismissRequest(
                            id = 7L,
                        ),
                    )

                assertTrue(dismissResult is DieselActionResult.Success)
                assertEquals(0, NotificationStore.items.value.size)

                val summary =
                    requireNotNull(
                        platform.states.current(
                            PhoneNotificationService.SUMMARY_KEY,
                        ),
                    ).value

                assertEquals(0, summary.activeCount)
            } finally {
                service.close()
                platform.transports.close()
            }
        }

    @Test
    fun replyRejectsNonReplyableAndOversizedText() =
        runTest {
            val platform = DieselPlatform(scope = this)
            val service =
                PhoneNotificationService(
                    platform = platform,
                    sendAction = { _, _, _ -> true },
                )

            try {
                service.receive(notification(8L, replyable = false))

                assertEquals(
                    DieselActionResult.Rejected(
                        "notification_not_replyable",
                    ),
                    platform.actions.dispatch(
                        PhoneNotificationService.REPLY_KEY,
                        PhoneNotificationReplyRequest(
                            id = 8L,
                            text = "hello",
                        ),
                    ),
                )

                service.receive(notification(9L, replyable = true))

                assertEquals(
                    DieselActionResult.Rejected(
                        "reply_too_large",
                    ),
                    platform.actions.dispatch(
                        PhoneNotificationService.REPLY_KEY,
                        PhoneNotificationReplyRequest(
                            id = 9L,
                            text = "x".repeat(
                                PhoneNotificationService.MAX_REPLY_BYTES + 1,
                            ),
                        ),
                    ),
                )
            } finally {
                service.close()
                platform.transports.close()
            }
        }

    private fun notification(
        id: Long,
        replyable: Boolean = false,
    ) =
        WatchNotification(
            id = id,
            app = "test.app",
            title = "Title $id",
            body = "Body $id",
            sender = "Sender",
            receivedAt = 1_000L + id,
            replyable = replyable,
        )
}
