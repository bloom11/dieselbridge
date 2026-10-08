// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import org.json.JSONObject

object PhoneDieselRequestCodec {
    fun encodeJson(request: PhoneDieselRequest): String {
        val root =
            JSONObject().apply {
                put("t", "diesel")
                put("v", PhoneDieselProtocolRules.PROTOCOL_VERSION)
                put("kind", "request")
                put("id", request.requestId)
                put("cmd", request.command)
                request.name?.let { put("name", it) }
                put(
                    "args",
                    PhoneDieselValueCodec.encodeObject(request.args),
                )
            }
        val json = root.toString()
        require(
            json.toByteArray(Charsets.UTF_8).size <=
                PhoneDieselProtocolRules.MAX_JSON_BYTES,
        )
        return json
    }

    fun encodeGadgetbridgeLine(
        request: PhoneDieselRequest,
    ): String =
        "GB(${encodeJson(request)})"
}
