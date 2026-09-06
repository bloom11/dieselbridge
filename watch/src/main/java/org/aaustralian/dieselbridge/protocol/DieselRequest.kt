// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

/**
 * Transport-independent Diesel request.
 *
 * Gadgetbridge/BLE is only one adapter capable of producing this object.
 * Future Binder, Wi-Fi, companion or plugin transports can produce the same
 * request without changing command modules.
 *
 * [args] is deliberately generic so new commands do not require new fields
 * to be hard-coded into the central protocol engine.
 */
data class DieselRequest(
    val requestId: String?,
    val command: String,
    val name: String? = null,
    val args: Map<String, DieselValue> = emptyMap(),
    val version: Int = PROTOCOL_VERSION,
) {
    init {
        require(
            version == PROTOCOL_VERSION,
        ) {
            "Unsupported Diesel request version: $version"
        }

        require(
            DieselProtocolRules
                .isValidIdentifier(command),
        ) {
            "Invalid Diesel request command '$command'"
        }

        require(
            name == null ||
                DieselProtocolRules
                    .isValidIdentifier(name),
        ) {
            "Invalid Diesel request target '$name'"
        }

        require(
            DieselProtocolRules
                .isValidRequestId(requestId),
        ) {
            "Diesel request id must be 1.." +
                "$MAX_REQUEST_ID_LENGTH characters"
        }

        require(
            args.size <= MAX_ARGUMENT_FIELDS,
        ) {
            "Diesel request contains too many arguments"
        }
    }

    companion object {
        const val PROTOCOL_VERSION =
            DieselProtocolRules.PROTOCOL_VERSION

        const val MAX_REQUEST_ID_LENGTH =
            DieselProtocolRules.MAX_REQUEST_ID_LENGTH

        const val MAX_ARGUMENT_FIELDS =
            DieselProtocolRules.MAX_TOP_LEVEL_FIELDS
    }
}
