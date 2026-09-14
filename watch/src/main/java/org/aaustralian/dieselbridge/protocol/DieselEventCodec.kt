// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes unsolicited Diesel events for Gadgetbridge's fixed Android Intent
 * bridge.
 *
 * The Android action and target are fixed exactly as they are for command
 * responses. Event producers cannot choose an arbitrary Intent destination.
 */
object DieselEventCodec {

    const val ANDROID_ACTION =
        DieselResponseCodec.ANDROID_ACTION

    const val INTENT_TARGET =
        DieselResponseCodec.INTENT_TARGET

    const val EXTRA_JSON =
        DieselResponseCodec.EXTRA_JSON

    /**
     * Keep event payloads within the same bounded Diesel control-plane budget
     * as command responses.
     */
    const val MAX_EVENT_JSON_BYTES =
        4096

    fun encodeGadgetbridgeIntent(
        event: DieselEvent,
    ): String {
        val eventJson =
            encodeEventJson(
                event,
            )

        val payloadBytes =
            eventJson
                .toByteArray(
                    Charsets.UTF_8,
                )
                .size

        require(
            payloadBytes <=
                MAX_EVENT_JSON_BYTES,
        ) {
            "Diesel event payload is too large: " +
                "$payloadBytes bytes"
        }

        return JSONObject()
            .apply {
                put(
                    "t",
                    "intent",
                )
                put(
                    "target",
                    INTENT_TARGET,
                )
                put(
                    "action",
                    ANDROID_ACTION,
                )
                put(
                    "extra",
                    JSONObject()
                        .put(
                            EXTRA_JSON,
                            eventJson,
                        ),
                )
            }
            .toString()
    }

    fun encodeEventJson(
        event: DieselEvent,
    ): String =
        JSONObject()
            .apply {
                put(
                    "v",
                    event.version,
                )
                put(
                    "kind",
                    "event",
                )
                put(
                    "topic",
                    event.topic,
                )

                if (event.data.isNotEmpty()) {
                    put(
                        "data",
                        encodeObject(
                            event.data,
                        ),
                    )
                }
            }
            .toString()

    private fun encodeObject(
        values:
            Map<String, DieselValue>,
    ): JSONObject =
        JSONObject()
            .apply {
                values.forEach {
                        (key, value),
                    ->
                    put(
                        key,
                        encodeValue(
                            value,
                        ),
                    )
                }
            }

    private fun encodeValue(
        value: DieselValue,
    ): Any =
        when (value) {
            DieselValue.Null ->
                JSONObject.NULL

            is DieselValue.Text ->
                value.value

            is DieselValue.Integer ->
                value.value

            is DieselValue.Decimal ->
                value.value

            is DieselValue.Flag ->
                value.value

            is DieselValue.ObjectValue ->
                encodeObject(
                    value.value,
                )

            is DieselValue.ListValue ->
                JSONArray()
                    .apply {
                        value.value
                            .forEach {
                                put(
                                    encodeValue(
                                        it,
                                    ),
                                )
                            }
                    }
        }
}
