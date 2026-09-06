// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

enum class DieselResponseStatus(
    val wireName: String,
) {
    OK("ok"),
    UNAVAILABLE("unavailable"),
    RATE_LIMITED("rate_limited"),
    FAILED("failed"),
    UNKNOWN_COMMAND("unknown_command"),
    UNKNOWN_TARGET("unknown_target"),
    INVALID_REQUEST("invalid_request"),
}

sealed interface DieselResponseValue {

    data class Text(
        val value: String,
    ) : DieselResponseValue

    data class Integer(
        val value: Long,
    ) : DieselResponseValue

    data class Decimal(
        val value: Double,
    ) : DieselResponseValue {
        init {
            require(value.isFinite()) {
                "Diesel decimal response values must be finite"
            }
        }
    }

    data class Flag(
        val value: Boolean,
    ) : DieselResponseValue

    data class ObjectValue(
        val value: Map<String, DieselResponseValue>,
    ) : DieselResponseValue

    data class ListValue(
        val value: List<DieselResponseValue>,
    ) : DieselResponseValue
}

data class DieselResponse(
    val requestId: String?,
    val command: String,
    val name: String? = null,
    val status: DieselResponseStatus,
    val data: Map<String, DieselResponseValue> = emptyMap(),
    val version: Int = PROTOCOL_VERSION,
) {
    init {
        require(version == PROTOCOL_VERSION) {
            "Unsupported Diesel response version: $version"
        }

        require(COMMAND_NAME.matches(command)) {
            "Invalid Diesel response command '$command'"
        }

        require(
            name == null ||
                COMMAND_NAME.matches(name),
        ) {
            "Invalid Diesel response target '$name'"
        }

        require(
            requestId == null ||
                (
                    requestId.isNotBlank() &&
                        requestId.length <=
                            MAX_REQUEST_ID_LENGTH
                ),
        ) {
            "Diesel request id must be 1..$MAX_REQUEST_ID_LENGTH characters"
        }

        require(
            data.size <= MAX_TOP_LEVEL_DATA_FIELDS,
        ) {
            "Diesel response contains too many top-level data fields"
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_REQUEST_ID_LENGTH = 64
        const val MAX_TOP_LEVEL_DATA_FIELDS = 32

        private val COMMAND_NAME =
            Regex("[a-z][a-z0-9_.-]*")
    }
}
