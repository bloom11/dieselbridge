// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.data.CannedResponsesStore
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.aaustralian.dieselbridge.platform.call.PhoneCallChangedEvent
import org.aaustralian.dieselbridge.platform.call.PhoneCallService
import org.aaustralian.dieselbridge.platform.notification.PhoneCannedRepliesService
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneInteractivePublicApiTest {
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
    fun defaultCatalogExposesCannedRepliesAndCallService() =
        runTest {
            val sent =
                mutableListOf<String>()

            val platform =
                DieselPlatform(
                    scope =
                        backgroundScope,
                )

            val canned =
                PhoneCannedRepliesService(
                    platform =
                        platform,
                    clockMs =
                        {
                            3_000L
                        },
                )

            val calls =
                PhoneCallService(
                    platform =
                        platform,
                    sendAction =
                        {
                            sent +=
                                it

                            true
                        },
                    clockMs =
                        {
                            4_000L
                        },
                )

            try {
                canned.update(
                    listOf(
                        "Yes",
                        "No",
                    ),
                )

                calls.receiveInbound(
                    cmd =
                        "incoming",
                    name =
                        "Alice",
                    number =
                        "123",
                )

                val api =
                    DieselDefaultPublicApi.create(
                        platform,
                    )

                assertEquals(
                    DieselApiVersion.CURRENT,
                    api.catalog().apiVersion,
                )

                val ids =
                    api.catalog()
                        .resources
                        .map {
                            it.id
                        }
                        .toSet()

                assertTrue(
                    DieselDefaultPublicApi
                        .CANNED_REPLY_STATE_IDS
                        .all(
                            ids::contains,
                        ),
                )

                assertTrue(
                    DieselDefaultPublicApi
                        .CALL_STATE_IDS
                        .all(
                            ids::contains,
                        ),
                )

                assertTrue(
                    DieselDefaultPublicApi
                        .CALL_ACTION_IDS
                        .all(
                            ids::contains,
                        ),
                )

                assertTrue(
                    DieselDefaultPublicApi
                        .CALL_EVENT_IDS
                        .all(
                            ids::contains,
                        ),
                )

                val cannedResult =
                    api.getState(
                        DieselDefaultPublicApi
                            .PHONE_CANNED_REPLIES
                            .id,
                    )

                assertTrue(
                    cannedResult is
                        DieselPublicStateResult.Success,
                )

                cannedResult as
                    DieselPublicStateResult.Success

                val cannedValue =
                    cannedResult
                        .snapshot
                        .value as
                        DieselValue.ObjectValue

                val replies =
                    cannedValue.value[
                        "replies"
                    ] as
                        DieselValue.ListValue

                assertEquals(
                    listOf(
                        DieselValue.Text(
                            "Yes",
                        ),
                        DieselValue.Text(
                            "No",
                        ),
                    ),
                    replies.value,
                )

                val callResult =
                    api.getState(
                        DieselDefaultPublicApi
                            .PHONE_CALL_CURRENT
                            .id,
                    )

                assertTrue(
                    callResult is
                        DieselPublicStateResult.Success,
                )

                callResult as
                    DieselPublicStateResult.Success

                val callValue =
                    callResult
                        .snapshot
                        .value as
                        DieselValue.ObjectValue

                assertEquals(
                    DieselValue.Text(
                        "ringing",
                    ),
                    callValue.value[
                        "phase"
                    ],
                )

                val accept =
                    api.invokeAction(
                        DieselDefaultPublicApi
                            .PHONE_CALL_ACCEPT
                            .id,
                        DieselValue.Null,
                    )

                assertTrue(
                    accept is
                        DieselPublicActionResult.Success,
                )

                assertEquals(
                    listOf(
                        PhoneCallService.ACTION_ACCEPT,
                    ),
                    sent,
                )

                val encoded =
                    api.encodeEvent(
                        DieselDefaultPublicApi
                            .PHONE_CALL_CHANGED
                            .id,
                        PhoneCallChangedEvent(
                            state =
                                calls.current(),
                            reason =
                                "test",
                        ),
                    )

                assertNotNull(
                    encoded,
                )

                val encodedValue =
                    requireNotNull(
                        encoded,
                    ).value as
                        DieselValue.ObjectValue

                assertEquals(
                    DieselValue.Text(
                        "test",
                    ),
                    encodedValue.value[
                        "reason"
                    ],
                )
            } finally {
                calls.close()
                canned.close()
                platform.transports.close()
            }
        }
}
