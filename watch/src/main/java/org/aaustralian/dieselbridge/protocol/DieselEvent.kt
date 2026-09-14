// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

/**
 * One unsolicited Diesel protocol event.
 *
 * Events are intentionally distinct from command responses: they do not carry
 * a request id, command, target or response status. Producers publish a stable
 * topic and bounded structured data; transports only carry the resulting
 * envelope and remain unaware of event semantics.
 */
data class DieselEvent(
    val topic: String,
    val data: Map<String, DieselValue> = emptyMap(),
    val version: Int = PROTOCOL_VERSION,
) {
    init {
        require(
            version == PROTOCOL_VERSION,
        ) {
            "Unsupported Diesel event version: $version"
        }

        require(
            DieselProtocolRules
                .isValidIdentifier(topic),
        ) {
            "Invalid Diesel event topic '$topic'"
        }

        require(
            data.size <= MAX_TOP_LEVEL_DATA_FIELDS,
        ) {
            "Diesel event contains too many top-level data fields"
        }
    }

    companion object {
        const val PROTOCOL_VERSION =
            DieselProtocolRules.PROTOCOL_VERSION

        const val MAX_TOP_LEVEL_DATA_FIELDS =
            DieselProtocolRules.MAX_TOP_LEVEL_FIELDS
    }
}
