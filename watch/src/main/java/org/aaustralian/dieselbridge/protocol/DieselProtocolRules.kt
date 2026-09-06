// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

/**
 * Shared structural limits for the Diesel protocol.
 *
 * These constraints belong to the protocol layer, not to BLE, Gadgetbridge
 * or individual command modules.
 */
object DieselProtocolRules {

    const val PROTOCOL_VERSION = 1

    const val MAX_REQUEST_ID_LENGTH = 64

    const val MAX_IDENTIFIER_LENGTH = 64

    const val MAX_COMMAND_SUMMARY_LENGTH = 256

    const val MAX_TOP_LEVEL_FIELDS = 32

    const val MAX_REQUEST_JSON_BYTES = 4096

    const val MAX_VALUE_DEPTH = 6

    const val MAX_COLLECTION_ENTRIES = 64

    const val MAX_VALUE_NODES = 256

    const val MAX_TEXT_VALUE_BYTES = 2048

    const val MAX_FIELD_NAME_LENGTH = 64

    private val IDENTIFIER =
        Regex("[a-z][a-z0-9_.-]*")

    /*
     * Structured argument/result field names allow camelCase after the first
     * lowercase character. Command identifiers remain lowercase-only.
     */
    private val FIELD_NAME =
        Regex("[a-z][A-Za-z0-9_.-]*")

    fun isValidIdentifier(
        value: String,
    ): Boolean =
        value.length <= MAX_IDENTIFIER_LENGTH &&
            IDENTIFIER.matches(value)

    fun isValidFieldName(
        value: String,
    ): Boolean =
        value.length <= MAX_FIELD_NAME_LENGTH &&
            FIELD_NAME.matches(value)

    fun isValidRequestId(
        value: String?,
    ): Boolean =
        value == null ||
            (
                value.isNotBlank() &&
                    value.length <=
                        MAX_REQUEST_ID_LENGTH
            )
}
