// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

data class PhoneDieselRequest(
    val requestId: String,
    val command: String,
    val name: String? = null,
    val args: Map<String, PhoneDieselValue> = emptyMap(),
) {
    init {
        require(
            requestId.isNotBlank() &&
                PhoneDieselProtocolRules.isValidRequestId(requestId),
        )
        require(PhoneDieselProtocolRules.isValidIdentifier(command))
        require(
            name == null ||
                PhoneDieselProtocolRules.isValidIdentifier(name),
        )
        require(
            args.size <= PhoneDieselProtocolRules.MAX_TOP_LEVEL_FIELDS,
        )
    }
}
