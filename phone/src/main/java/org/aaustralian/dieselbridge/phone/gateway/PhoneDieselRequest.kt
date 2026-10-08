// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

/**
 * Phone-side subset of a Diesel v1 request used by M6.0b.
 *
 * M6.0b deliberately proves a no-argument read-only request first. Generic
 * structured argument values are added when the first phone-owned state
 * command actually needs them rather than introducing a parallel Diesel value
 * model prematurely.
 */
data class PhoneDieselRequest(
    val requestId: String,
    val command: String,
    val name: String? = null,
) {
    init {
        require(
            requestId.isNotBlank() &&
                requestId.length <= MAX_REQUEST_ID_LENGTH,
        ) {
            "requestId must be 1..$MAX_REQUEST_ID_LENGTH characters"
        }

        require(
            isValidIdentifier(
                command,
            ),
        ) {
            "invalid Diesel command '$command'"
        }

        require(
            name == null ||
                isValidIdentifier(
                    name,
                ),
        ) {
            "invalid Diesel target '$name'"
        }
    }

    companion object {
        const val MAX_REQUEST_ID_LENGTH = 64
        const val MAX_IDENTIFIER_LENGTH = 64

        private val IDENTIFIER =
            Regex(
                "[a-z][a-z0-9_.-]*",
            )

        private fun isValidIdentifier(
            value: String,
        ): Boolean =
            value.length <=
                MAX_IDENTIFIER_LENGTH &&
                IDENTIFIER.matches(
                    value,
                )
    }
}
