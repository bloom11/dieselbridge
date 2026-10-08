// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

enum class PhoneDieselResponseStatus(val wireName: String) {
    OK("ok"),
    UNAVAILABLE("unavailable"),
    RATE_LIMITED("rate_limited"),
    FAILED("failed"),
    UNKNOWN_COMMAND("unknown_command"),
    UNKNOWN_TARGET("unknown_target"),
    INVALID_REQUEST("invalid_request");

    companion object {
        fun fromWire(value: String): PhoneDieselResponseStatus? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class PhoneDieselResponse(
    val requestId: String?,
    val command: String,
    val name: String?,
    val status: PhoneDieselResponseStatus,
    val data: Map<String, PhoneDieselValue>,
    val version: Int = PhoneDieselProtocolRules.PROTOCOL_VERSION,
) {
    init {
        require(version == PhoneDieselProtocolRules.PROTOCOL_VERSION)
        require(PhoneDieselProtocolRules.isValidRequestId(requestId))
        require(PhoneDieselProtocolRules.isValidIdentifier(command))
        require(
            name == null ||
                PhoneDieselProtocolRules.isValidIdentifier(name),
        )
        require(
            data.size <= PhoneDieselProtocolRules.MAX_TOP_LEVEL_FIELDS,
        )
    }
}
