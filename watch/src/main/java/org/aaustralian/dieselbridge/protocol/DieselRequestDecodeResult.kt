// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

/**
 * Stable machine-readable reason for rejecting an inbound Diesel request.
 *
 * [wireName] is deliberately coarse. Detailed parser information remains
 * local and must not expose arbitrary exception messages remotely.
 */
enum class DieselRequestFailureReason(
    val wireName: String,
) {
    MALFORMED_JSON("malformed_json"),
    PAYLOAD_TOO_LARGE("payload_too_large"),
    UNKNOWN_FIELD("unknown_field"),
    INVALID_ENVELOPE("invalid_envelope"),
    UNSUPPORTED_VERSION("unsupported_version"),
    INVALID_KIND("invalid_kind"),
    MISSING_COMMAND("missing_command"),
    INVALID_COMMAND("invalid_command"),
    INVALID_TARGET("invalid_target"),
    INVALID_REQUEST_ID("invalid_request_id"),
    INVALID_ARGS("invalid_args"),
    INVALID_FIELD_NAME("invalid_field_name"),
    STRUCTURE_TOO_DEEP("structure_too_deep"),
    TOO_MANY_VALUES("too_many_values"),
    VALUE_TOO_LARGE("value_too_large"),
    INVALID_VALUE("invalid_value"),
}

/**
 * Sanitized information recovered from a request that could not become a
 * valid [DieselRequest].
 *
 * Invalid identifiers/IDs are never reflected here.
 */
data class DieselInvalidRequest(
    val requestId: String?,
    val command: String?,
    val name: String?,
    val reason: DieselRequestFailureReason,
    val detail: String,
)

sealed interface DieselRequestDecodeResult {

    data class Success(
        val request: DieselRequest,
    ) : DieselRequestDecodeResult

    data class Invalid(
        val failure: DieselInvalidRequest,
    ) : DieselRequestDecodeResult
}
