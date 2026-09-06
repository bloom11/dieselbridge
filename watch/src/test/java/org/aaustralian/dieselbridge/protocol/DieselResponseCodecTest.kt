// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselResponseCodecTest {

    @Test
    fun encodesFixedGadgetbridgeBroadcastIntent() {
        val line =
            DieselResponseCodec
                .encodeGadgetbridgeIntent(
                    DieselResponse(
                        requestId = "req-42",
                        command = "test",
                        name = "vibration",
                        status =
                            DieselResponseStatus.OK,
                        data =
                            mapOf(
                                "provider" to
                                    DieselValue.Text(
                                        "legacy.vibration",
                                    ),
                                "durationMs" to
                                    DieselValue.Integer(
                                        250L,
                                    ),
                                "bounded" to
                                    DieselValue.Flag(
                                        true,
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

        val response =
            JSONObject(
                intent
                    .getJSONObject("extra")
                    .getString(
                        DieselResponseCodec.EXTRA_JSON,
                    ),
            )

        assertEquals(
            DieselResponse.PROTOCOL_VERSION,
            response.getInt("v"),
        )

        assertEquals(
            "req-42",
            response.getString("id"),
        )

        assertEquals(
            "test",
            response.getString("cmd"),
        )

        assertEquals(
            "vibration",
            response.getString("name"),
        )

        assertEquals(
            "ok",
            response.getString("status"),
        )

        val data =
            response.getJSONObject("data")

        assertEquals(
            "legacy.vibration",
            data.getString("provider"),
        )

        assertEquals(
            250L,
            data.getLong("durationMs"),
        )

        assertTrue(
            data.getBoolean("bounded"),
        )
    }

    @Test
    fun optionalEnvelopeFieldsAreOmitted() {
        val response =
            JSONObject(
                DieselResponseCodec
                    .encodeResponseJson(
                        DieselResponse(
                            requestId = null,
                            command = "commands",
                            status =
                                DieselResponseStatus.OK,
                        ),
                    ),
            )

        assertFalse(
            response.has("id"),
        )

        assertFalse(
            response.has("name"),
        )

        assertFalse(
            response.has("data"),
        )
    }

    @Test
    fun structuredValuesRemainTyped() {
        val response =
            JSONObject(
                DieselResponseCodec
                    .encodeResponseJson(
                        DieselResponse(
                            requestId = "typed-1",
                            command = "commands",
                            status =
                                DieselResponseStatus.OK,
                            data =
                                mapOf(
                                    "decimal" to
                                        DieselValue.Decimal(
                                            3.5,
                                        ),
                                    "items" to
                                        DieselValue.ListValue(
                                            listOf(
                                                DieselValue.Text(
                                                    "one",
                                                ),
                                                DieselValue.Integer(
                                                    2L,
                                                ),
                                            ),
                                        ),
                                    "nested" to
                                        DieselValue.ObjectValue(
                                            mapOf(
                                                "enabled" to
                                                    DieselValue.Flag(
                                                        true,
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                    ),
            )

        val data =
            response.getJSONObject("data")

        assertEquals(
            3.5,
            data.getDouble("decimal"),
            0.0,
        )

        assertEquals(
            "one",
            data
                .getJSONArray("items")
                .getString(0),
        )

        assertEquals(
            2L,
            data
                .getJSONArray("items")
                .getLong(1),
        )

        assertTrue(
            data
                .getJSONObject("nested")
                .getBoolean("enabled"),
        )
    }

    @Test
    fun oversizedPayloadIsRejected() {
        var rejected = false

        try {
            DieselResponseCodec
                .encodeGadgetbridgeIntent(
                    DieselResponse(
                        requestId = "large-1",
                        command = "commands",
                        status =
                            DieselResponseStatus.OK,
                        data =
                            mapOf(
                                "payload" to
                                    DieselValue.Text(
                                        "x".repeat(
                                            DieselResponseCodec
                                                .MAX_RESPONSE_JSON_BYTES +
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
