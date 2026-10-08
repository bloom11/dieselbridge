// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import org.json.JSONObject

object PhoneDieselInboundCodec {
    fun parse(json: String): PhoneDieselInbound {
        require(
            json.toByteArray(Charsets.UTF_8).size <=
                PhoneDieselProtocolRules.MAX_JSON_BYTES,
        )
        val root = JSONObject(json)
        require(
            root.length() <=
                PhoneDieselProtocolRules.MAX_TOP_LEVEL_FIELDS,
        )
        require(
            root.getInt("v") ==
                PhoneDieselProtocolRules.PROTOCOL_VERSION,
        )
        return when (root.getString("kind")) {
            "response" ->
                PhoneDieselInbound.Response(
                    PhoneDieselResponse(
                        requestId = optionalString(root, "id"),
                        command = root.getString("cmd"),
                        name = optionalString(root, "name"),
                        status =
                            PhoneDieselResponseStatus.fromWire(
                                root.getString("status"),
                            ) ?: error("Unknown Diesel response status"),
                        data = dataObject(root),
                    ),
                )
            "event" ->
                PhoneDieselInbound.Event(
                    PhoneDieselEvent(
                        topic = root.getString("topic"),
                        data = dataObject(root),
                    ),
                )
            else ->
                error("Unknown Diesel inbound kind")
        }
    }

    fun parseResponse(json: String): PhoneDieselResponse =
        when (val inbound = parse(json)) {
            is PhoneDieselInbound.Response -> inbound.value
            is PhoneDieselInbound.Event ->
                error("Inbound message is not a response")
        }

    private fun dataObject(
        root: JSONObject,
    ): Map<String, PhoneDieselValue> =
        if (root.has("data") && !root.isNull("data")) {
            PhoneDieselValueCodec.decodeObject(
                root.getJSONObject("data"),
            )
        } else {
            emptyMap()
        }

    private fun optionalString(
        root: JSONObject,
        key: String,
    ): String? =
        if (root.has(key) && !root.isNull(key)) {
            root.getString(key)
        } else {
            null
        }
}
