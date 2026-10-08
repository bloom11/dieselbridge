// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

object PhoneDieselProtocolRules {
    const val PROTOCOL_VERSION = 1
    const val MAX_REQUEST_ID_LENGTH = 64
    const val MAX_IDENTIFIER_LENGTH = 64
    const val MAX_TOP_LEVEL_FIELDS = 32
    const val MAX_JSON_BYTES = 4096
    const val MAX_VALUE_DEPTH = 6
    const val MAX_COLLECTION_ENTRIES = 64
    const val MAX_VALUE_NODES = 256
    const val MAX_TEXT_VALUE_BYTES = 2048
    const val MAX_FIELD_NAME_LENGTH = 64

    private val identifier = Regex("[a-z][a-z0-9_.-]*")
    private val fieldName = Regex("[a-z][A-Za-z0-9_.-]*")

    fun isValidIdentifier(value: String): Boolean =
        value.length <= MAX_IDENTIFIER_LENGTH &&
            identifier.matches(value)

    fun isValidFieldName(value: String): Boolean =
        value.length <= MAX_FIELD_NAME_LENGTH &&
            fieldName.matches(value)

    fun isValidRequestId(value: String?): Boolean =
        value == null ||
            (value.isNotBlank() && value.length <= MAX_REQUEST_ID_LENGTH)
}
