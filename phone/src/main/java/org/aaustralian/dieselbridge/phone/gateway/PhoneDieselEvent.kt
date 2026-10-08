// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

data class PhoneDieselEvent(
    val topic: String,
    val data: Map<String, PhoneDieselValue>,
    val version: Int = PhoneDieselProtocolRules.PROTOCOL_VERSION,
) {
    init {
        require(version == PhoneDieselProtocolRules.PROTOCOL_VERSION)
        require(PhoneDieselProtocolRules.isValidIdentifier(topic))
        require(
            data.size <= PhoneDieselProtocolRules.MAX_TOP_LEVEL_FIELDS,
        )
    }
}

sealed interface PhoneDieselInbound {
    data class Response(
        val value: PhoneDieselResponse,
    ) : PhoneDieselInbound

    data class Event(
        val value: PhoneDieselEvent,
    ) : PhoneDieselInbound
}
