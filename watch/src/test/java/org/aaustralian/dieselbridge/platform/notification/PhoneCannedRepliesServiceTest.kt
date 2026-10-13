// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.notification

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.data.CannedResponsesStore
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneCannedRepliesServiceTest {
    @Before
    fun setup() {
        CannedResponsesStore.set(
            emptyList(),
        )
    }

    @After
    fun teardown() {
        CannedResponsesStore.set(
            emptyList(),
        )
    }

    @Test
    fun phoneListIsBoundedBeforeStateAndLegacyProjection() =
        runTest {
            val projected =
                mutableListOf<List<String>>()

            val platform =
                DieselPlatform(
                    scope =
                        backgroundScope,
                )

            val service =
                PhoneCannedRepliesService(
                    platform =
                        platform,
                    project =
                        {
                            projected +=
                                it
                        },
                    clockMs =
                        {
                            7_000L
                        },
                )

            try {
                val source =
                    (1..40)
                        .map {
                            if (
                                it ==
                                1
                            ) {
                                "x".repeat(
                                    400,
                                )
                            } else {
                                "reply-$it"
                            }
                        }

                service.update(
                    source,
                )

                val state =
                    requireNotNull(
                        platform.states.current(
                            PhoneCannedRepliesService.STATE_KEY,
                        ),
                    ).value

                assertEquals(
                    PhoneCannedRepliesService.MAX_REPLIES,
                    state.replies.size,
                )

                assertEquals(
                    PhoneCannedRepliesService.MAX_REPLY_CHARS,
                    state.replies.first().length,
                )

                assertEquals(
                    state.replies,
                    projected.single(),
                )

                assertTrue(
                    state.replies.all {
                        it.length <=
                            PhoneCannedRepliesService.MAX_REPLY_CHARS
                    },
                )
            } finally {
                service.close()
                platform.transports.close()
            }
        }
}
