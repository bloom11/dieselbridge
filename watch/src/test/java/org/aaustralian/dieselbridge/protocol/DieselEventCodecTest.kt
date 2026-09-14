// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselEventCodecTest {

    @Test
    fun encodesBoundedEventThroughFixedGadgetbridgeIntent() {
        val line =
            DieselEventCodec
                .encodeGadgetbridgeIntent(
                    DieselEvent(
                        topic = "sensor.sample",
                        data =
                            mapOf(
                                "subscriptionId" to
                                    DieselValue.Integer(
                                        7L,
                                    ),
                                "providerId" to
                                    DieselValue.Text(
                                        "android.sensor_manager",
                                    ),
                                "values" to
                                    DieselValue.ListValue(
                                        listOf(
                                            DieselValue.Decimal(
                                                1.25,
                                            ),
                                            DieselValue.Decimal(
                                                -2.5,
                                            ),
                                        ),
                                    ),
                            ),
                    ),
                )

        val intent =
            JSONObject(line)

        assertEquals(
            "intent",
            intent.getString("t"),
        )
        assertEquals(
            DieselResponseCodec.INTENT_TARGET,
            intent.getString("target"),
        )
        assertEquals(
            DieselResponseCodec.ANDROID_ACTION,
            intent.getString("action"),
        )

        val event =
            JSONObject(
                intent
                    .getJSONObject("extra")
                    .getString(
                        DieselEventCodec.EXTRA_JSON,
                    ),
            )

        assertEquals(
            DieselEvent.PROTOCOL_VERSION,
            event.getInt("v"),
        )
        assertEquals(
            "event",
            event.getString("kind"),
        )
        assertEquals(
            "sensor.sample",
            event.getString("topic"),
        )

        val data =
            event.getJSONObject("data")

        assertEquals(
            7L,
            data.getLong("subscriptionId"),
        )
        assertEquals(
            "android.sensor_manager",
            data.getString("providerId"),
        )
        assertEquals(
            1.25,
            data
                .getJSONArray("values")
                .getDouble(0),
            0.0,
        )
    }

    @Test
    fun emptyEventDataIsOmitted() {
        val event =
            JSONObject(
                DieselEventCodec
                    .encodeEventJson(
                        DieselEvent(
                            topic =
                                "sensor.subscription.state",
                        ),
                    ),
            )

        assertFalse(
            event.has("data"),
        )
    }

    @Test
    fun invalidTopicIsRejected() {
        var rejected = false

        try {
            DieselEvent(
                topic = "Sensor Sample",
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun nonFiniteDecimalIsRejectedBeforeEncoding() {
        var rejected = false

        try {
            DieselValue.Decimal(
                Double.NaN,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun oversizedEventPayloadIsRejected() {
        var rejected = false

        try {
            DieselEventCodec
                .encodeGadgetbridgeIntent(
                    DieselEvent(
                        topic = "sensor.sample",
                        data =
                            mapOf(
                                "payload" to
                                    DieselValue.Text(
                                        "x".repeat(
                                            DieselEventCodec
                                                .MAX_EVENT_JSON_BYTES +
                                                512,
                                        ),
                                    ),
                            ),
                    ),
                )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }
}
