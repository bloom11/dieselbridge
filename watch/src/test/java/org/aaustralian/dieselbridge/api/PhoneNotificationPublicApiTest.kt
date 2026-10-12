// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.data.NotificationStore
import org.aaustralian.dieselbridge.data.WatchNotification
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.notification.PhoneNotificationReceivedEvent
import org.aaustralian.dieselbridge.platform.notification.PhoneNotificationService
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneNotificationPublicApiTest {
    @Before
    fun setup() {
        NotificationStore.clear()
    }

    @After
    fun teardown() {
        NotificationStore.clear()
    }

    @Test
    fun defaultCatalogExposesNotificationStateActionsAndEvents() =
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
                val notification =
                    WatchNotification(
                        id = 77L,
                        app = "messages",
                        title = "Hello",
                        body = "World",
                        sender = "Alice",
                        receivedAt = 5_000L,
                        replyable = true,
                    )

                service.receive(notification)

                val api = DieselDefaultPublicApi.create(platform)

                assertEquals(
                    DieselApiVersion(
                        major = 1,
                        minor = 7,
                    ),
                    api.catalog().apiVersion,
                )

                val ids =
                    api.catalog().resources.map {
                        it.id
                    }.toSet()

                assertTrue(
                    DieselDefaultPublicApi.NOTIFICATION_STATE_IDS.all(
                        ids::contains,
                    ),
                )
                assertTrue(
                    DieselDefaultPublicApi.NOTIFICATION_ACTION_IDS.all(
                        ids::contains,
                    ),
                )
                assertTrue(
                    DieselDefaultPublicApi.NOTIFICATION_EVENT_IDS.all(
                        ids::contains,
                    ),
                )

                val stateResult =
                    api.getState(
                        DieselDefaultPublicApi.PHONE_NOTIFICATION_SUMMARY.id,
                    )

                assertTrue(stateResult is DieselPublicStateResult.Success)
                stateResult as DieselPublicStateResult.Success

                val summary =
                    stateResult.snapshot.value as DieselValue.ObjectValue

                assertEquals(
                    DieselValue.Integer(1L),
                    summary.value["activeCount"],
                )

                val latest =
                    summary.value["latest"] as DieselValue.ObjectValue

                assertEquals(
                    DieselValue.Integer(77L),
                    latest.value["id"],
                )

                val replyResult =
                    api.invokeAction(
                        DieselDefaultPublicApi.PHONE_NOTIFICATION_REPLY.id,
                        DieselValue.ObjectValue(
                            linkedMapOf(
                                "id" to DieselValue.Integer(77L),
                                "text" to DieselValue.Text("ack"),
                            ),
                        ),
                    )

                assertTrue(replyResult is DieselPublicActionResult.Success)
                assertEquals("REPLY", sent.last().second)

                val encodedEvent =
                    api.encodeEvent(
                        DieselDefaultPublicApi.PHONE_NOTIFICATION_RECEIVED.id,
                        PhoneNotificationReceivedEvent(notification),
                    )

                assertNotNull(encodedEvent)

                val eventValue =
                    requireNotNull(encodedEvent).value as DieselValue.ObjectValue

                assertEquals(
                    DieselValue.Text("Alice"),
                    eventValue.value["sender"],
                )
            } finally {
                service.close()
                platform.transports.close()
            }
        }
}
