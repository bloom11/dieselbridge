// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import org.json.JSONObject

/**
 * Encodes the M6.0b request subset and the already-proven Gadgetbridge line
 * contract used by tools/diesel-adb.
 *
 * Android sends exactly:
 *
 *   action = com.banglejs.uart.tx
 *   line   = GB(<Diesel JSON>)
 *
 * The companion does not add DLE/newline framing. Gadgetbridge owns UART
 * transmission framing on the central side.
 */
object PhoneDieselRequestCodec {

    const val PROTOCOL_VERSION = 1
    const val MAX_REQUEST_JSON_BYTES = 4096

    fun encodeJson(
        request: PhoneDieselRequest,
    ): String {
        val root =
            JSONObject()
                .apply {
                    put(
                        "t",
                        "diesel",
                    )
                    put(
                        "v",
                        PROTOCOL_VERSION,
                    )
                    put(
                        "kind",
                        "request",
                    )
                    put(
                        "id",
                        request.requestId,
                    )
                    put(
                        "cmd",
                        request.command,
                    )

                    request.name
                        ?.let { target ->
                            put(
                                "name",
                                target,
                            )
                        }

                    /*
                     * Keep parity with tools/diesel-adb, which canonicalizes
                     * missing args to an empty object.
                     */
                    put(
                        "args",
                        JSONObject(),
                    )
                }

        val json =
            root.toString()

        require(
            json
                .toByteArray(
                    Charsets.UTF_8,
                )
                .size <=
                MAX_REQUEST_JSON_BYTES,
        ) {
            "Diesel request exceeds $MAX_REQUEST_JSON_BYTES bytes"
        }

        return json
    }

    fun encodeGadgetbridgeLine(
        request: PhoneDieselRequest,
    ): String =
        "GB(${encodeJson(request)})"
}
