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

    const val MAX_TOP_LEVEL_FIELDS = 32

    private val IDENTIFIER =
        Regex("[a-z][a-z0-9_.-]*")

    fun isValidIdentifier(
        value: String,
    ): Boolean =
        value.length <= MAX_IDENTIFIER_LENGTH &&
            IDENTIFIER.matches(value)

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
