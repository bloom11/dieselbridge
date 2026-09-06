// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes Diesel responses for Gadgetbridge's Bangle.js Android Intent
 * bridge.
 *
 * The Android action and target are fixed here. Remote requests never choose
 * an arbitrary Intent action, package, class, service or activity.
 */
object DieselResponseCodec {

    const val ANDROID_ACTION =
        "io.github.bloom11.dieselbridge.DEVELOPER_RESPONSE"

    const val INTENT_TARGET =
        "broadcastreceiver"

    const val EXTRA_JSON =
        "json"

    /**
     * Bound the structured Diesel payload before adding the small Gadgetbridge
     * intent wrapper. NUS itself chunks long lines, but application responses
     * must remain deliberately bounded.
     */
    const val MAX_RESPONSE_JSON_BYTES =
        4096

    fun encodeGadgetbridgeIntent(
        response: DieselResponse,
    ): String {
        val responseJson =
            encodeResponseJson(
                response,
            )

        val payloadBytes =
            responseJson
                .toByteArray(
                    Charsets.UTF_8,
                )
                .size

        require(
            payloadBytes <=
                MAX_RESPONSE_JSON_BYTES,
        ) {
            "Diesel response payload is too large: " +
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
                            responseJson,
                        ),
                )
            }
            .toString()
    }

    fun encodeResponseJson(
        response: DieselResponse,
    ): String =
        JSONObject()
            .apply {
                put(
                    "v",
                    response.version,
                )

                response
                    .requestId
                    ?.let {
                        put(
                            "id",
                            it,
                        )
                    }

                put(
                    "cmd",
                    response.command,
                )

                response
                    .name
                    ?.let {
                        put(
                            "name",
                            it,
                        )
                    }

                put(
                    "status",
                    response.status.wireName,
                )

                if (response.data.isNotEmpty()) {
                    put(
                        "data",
                        encodeObject(
                            response.data,
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
