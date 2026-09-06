// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Bounded JSON -> [DieselRequest] decoder.
 *
 * This is a Diesel protocol codec, not a BLE/Gadgetbridge command dispatcher.
 * A transport may feed JSON into this decoder, while Binder/native transports
 * may construct DieselRequest directly.
 */
object DieselJsonRequestDecoder {

    private val allowedFields =
        setOf(
            "t",
            "v",
            "kind",
            "id",
            "cmd",
            "name",
            "args",
        )

    private val longMinimum =
        BigInteger.valueOf(
            Long.MIN_VALUE,
        )

    private val longMaximum =
        BigInteger.valueOf(
            Long.MAX_VALUE,
        )

    fun decode(
        json: String,
    ): DieselRequestDecodeResult {
        val payloadBytes =
            json
                .toByteArray(
                    Charsets.UTF_8,
                )
                .size

        if (
            payloadBytes >
            DieselProtocolRules.MAX_REQUEST_JSON_BYTES
        ) {
            return invalid(
                root = null,
                reason =
                    DieselRequestFailureReason
                        .PAYLOAD_TOO_LARGE,
                detail =
                    "request exceeds JSON byte limit",
            )
        }

        val root =
            try {
                JSONObject(json)
            } catch (_: JSONException) {
                return invalid(
                    root = null,
                    reason =
                        DieselRequestFailureReason
                            .MALFORMED_JSON,
                    detail =
                        "request is not a JSON object",
                )
            }

        return try {
            DieselRequestDecodeResult.Success(
                request =
                    decodeObject(
                        root,
                    ),
            )
        } catch (error: DecodeException) {
            invalid(
                root = root,
                reason = error.reason,
                detail = error.detail,
            )
        } catch (_: JSONException) {
            invalid(
                root = root,
                reason =
                    DieselRequestFailureReason
                        .INVALID_VALUE,
                detail =
                    "request contains an invalid JSON value",
            )
        } catch (_: IllegalArgumentException) {
            /*
             * The decoder validates the same invariants before construction.
             * Keep this final model boundary as a safe fallback without
             * exposing implementation exception text.
             */
            invalid(
                root = root,
                reason =
                    DieselRequestFailureReason
                        .INVALID_VALUE,
                detail =
                    "decoded request was rejected by the protocol model",
            )
        }
    }

    private fun decodeObject(
        root: JSONObject,
    ): DieselRequest {
        validateTopLevelFields(
            root,
        )

        decodeOptionalEnvelopeType(
            root,
        )

        val version =
            decodeVersion(
                root,
            )

        decodeKind(
            root,
        )

        val requestId =
            decodeRequestId(
                root,
            )

        val command =
            decodeCommand(
                root,
            )

        val name =
            decodeTarget(
                root,
            )

        val args =
            decodeArguments(
                root,
            )

        return DieselRequest(
            requestId = requestId,
            command = command,
            name = name,
            args = args,
            version = version,
        )
    }

    private fun validateTopLevelFields(
        root: JSONObject,
    ) {
        val keys =
            root.keys()

        while (keys.hasNext()) {
            val key =
                keys.next()

            if (key !in allowedFields) {
                fail(
                    reason =
                        DieselRequestFailureReason
                            .UNKNOWN_FIELD,
                    detail =
                        "unknown top-level field '" +
                            key.take(
                                DieselProtocolRules
                                    .MAX_FIELD_NAME_LENGTH,
                            ) +
                            "'",
                )
            }
        }
    }

    private fun decodeOptionalEnvelopeType(
        root: JSONObject,
    ) {
        if (!root.has("t")) {
            return
        }

        val raw =
            root.get("t")

        if (
            raw !is String ||
            raw != "diesel"
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .INVALID_ENVELOPE,
                detail =
                    "t must be 'diesel'",
            )
        }
    }

    private fun decodeVersion(
        root: JSONObject,
    ): Int {
        if (!root.has("v")) {
            return DieselProtocolRules
                .PROTOCOL_VERSION
        }

        val raw =
            root.get("v")

        val version =
            when (raw) {
                is Int ->
                    raw

                is Long ->
                    if (
                        raw >= Int.MIN_VALUE &&
                        raw <= Int.MAX_VALUE
                    ) {
                        raw.toInt()
                    } else {
                        null
                    }

                else ->
                    null
            }

        if (
            version !=
            DieselProtocolRules.PROTOCOL_VERSION
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .UNSUPPORTED_VERSION,
                detail =
                    "unsupported or invalid protocol version",
            )
        }

        return version
    }

    private fun decodeKind(
        root: JSONObject,
    ) {
        if (!root.has("kind")) {
            return
        }

        val raw =
            root.get("kind")

        if (
            raw !is String ||
            raw != "request"
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .INVALID_KIND,
                detail =
                    "kind must be 'request'",
            )
        }
    }

    private fun decodeRequestId(
        root: JSONObject,
    ): String? {
        if (!root.has("id")) {
            return null
        }

        val raw =
            root.get("id")

        if (raw === JSONObject.NULL) {
            return null
        }

        if (
            raw !is String ||
            !DieselProtocolRules
                .isValidRequestId(raw)
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .INVALID_REQUEST_ID,
                detail =
                    "invalid request id",
            )
        }

        return raw
    }

    private fun decodeCommand(
        root: JSONObject,
    ): String {
        if (
            !root.has("cmd") ||
            root.get("cmd") === JSONObject.NULL
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .MISSING_COMMAND,
                detail =
                    "cmd is required",
            )
        }

        val raw =
            root.get("cmd")

        if (
            raw !is String ||
            !DieselProtocolRules
                .isValidIdentifier(raw)
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .INVALID_COMMAND,
                detail =
                    "invalid command identifier",
            )
        }

        return raw
    }

    private fun decodeTarget(
        root: JSONObject,
    ): String? {
        if (!root.has("name")) {
            return null
        }

        val raw =
            root.get("name")

        if (raw === JSONObject.NULL) {
            return null
        }

        if (
            raw !is String ||
            !DieselProtocolRules
                .isValidIdentifier(raw)
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .INVALID_TARGET,
                detail =
                    "invalid target identifier",
            )
        }

        return raw
    }

    private fun decodeArguments(
        root: JSONObject,
    ): Map<String, DieselValue> {
        if (!root.has("args")) {
            return emptyMap()
        }

        val raw =
            root.get("args")

        if (raw !is JSONObject) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .INVALID_ARGS,
                detail =
                    "args must be an object",
            )
        }

        if (
            raw.length() >
            DieselProtocolRules.MAX_TOP_LEVEL_FIELDS
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .TOO_MANY_VALUES,
                detail =
                    "too many top-level arguments",
            )
        }

        val budget =
            NodeBudget()

        val decoded =
            linkedMapOf<String, DieselValue>()

        val keys =
            raw.keys()

        while (keys.hasNext()) {
            val key =
                keys.next()

            validateFieldName(
                key,
            )

            decoded[key] =
                decodeValue(
                    raw = raw.get(key),
                    depth = 1,
                    budget = budget,
                )
        }

        return decoded.toMap()
    }

    private fun decodeValue(
        raw: Any,
        depth: Int,
        budget: NodeBudget,
    ): DieselValue {
        if (
            depth >
            DieselProtocolRules.MAX_VALUE_DEPTH
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .STRUCTURE_TOO_DEEP,
                detail =
                    "value nesting exceeds depth limit",
            )
        }

        budget.consume()

        return when (raw) {
            JSONObject.NULL ->
                DieselValue.Null

            is String -> {
                requireTextSize(
                    raw,
                )

                DieselValue.Text(
                    raw,
                )
            }

            is Boolean ->
                DieselValue.Flag(
                    raw,
                )

            is Byte ->
                DieselValue.Integer(
                    raw.toLong(),
                )

            is Short ->
                DieselValue.Integer(
                    raw.toLong(),
                )

            is Int ->
                DieselValue.Integer(
                    raw.toLong(),
                )

            is Long ->
                DieselValue.Integer(
                    raw,
                )

            is BigInteger -> {
                if (
                    raw < longMinimum ||
                    raw > longMaximum
                ) {
                    fail(
                        reason =
                            DieselRequestFailureReason
                                .INVALID_VALUE,
                        detail =
                            "integer is outside signed 64-bit range",
                    )
                }

                DieselValue.Integer(
                    raw.toLong(),
                )
            }

            is Float ->
                decodeDecimal(
                    raw.toDouble(),
                )

            is Double ->
                decodeDecimal(
                    raw,
                )

            is BigDecimal ->
                decodeDecimal(
                    raw.toDouble(),
                )

            is JSONObject ->
                DieselValue.ObjectValue(
                    decodeNestedObject(
                        raw = raw,
                        depth = depth,
                        budget = budget,
                    ),
                )

            is JSONArray ->
                DieselValue.ListValue(
                    decodeArray(
                        raw = raw,
                        depth = depth,
                        budget = budget,
                    ),
                )

            else ->
                fail(
                    reason =
                        DieselRequestFailureReason
                            .INVALID_VALUE,
                    detail =
                        "unsupported JSON value type",
                )
        }
    }

    private fun decodeNestedObject(
        raw: JSONObject,
        depth: Int,
        budget: NodeBudget,
    ): Map<String, DieselValue> {
        if (
            raw.length() >
            DieselProtocolRules.MAX_COLLECTION_ENTRIES
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .TOO_MANY_VALUES,
                detail =
                    "object contains too many entries",
            )
        }

        val decoded =
            linkedMapOf<String, DieselValue>()

        val keys =
            raw.keys()

        while (keys.hasNext()) {
            val key =
                keys.next()

            validateFieldName(
                key,
            )

            decoded[key] =
                decodeValue(
                    raw = raw.get(key),
                    depth = depth + 1,
                    budget = budget,
                )
        }

        return decoded.toMap()
    }

    private fun decodeArray(
        raw: JSONArray,
        depth: Int,
        budget: NodeBudget,
    ): List<DieselValue> {
        if (
            raw.length() >
            DieselProtocolRules.MAX_COLLECTION_ENTRIES
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .TOO_MANY_VALUES,
                detail =
                    "array contains too many entries",
            )
        }

        val decoded =
            ArrayList<DieselValue>(
                raw.length(),
            )

        for (
            index in
            0 until raw.length()
        ) {
            decoded +=
                decodeValue(
                    raw = raw.get(index),
                    depth = depth + 1,
                    budget = budget,
                )
        }

        return decoded.toList()
    }

    private fun decodeDecimal(
        value: Double,
    ): DieselValue.Decimal {
        if (!value.isFinite()) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .INVALID_VALUE,
                detail =
                    "decimal must be finite",
            )
        }

        return DieselValue.Decimal(
            value,
        )
    }

    private fun validateFieldName(
        value: String,
    ) {
        if (
            !DieselProtocolRules
                .isValidFieldName(value)
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .INVALID_FIELD_NAME,
                detail =
                    "invalid structured field name",
            )
        }
    }

    private fun requireTextSize(
        value: String,
    ) {
        val bytes =
            value
                .toByteArray(
                    Charsets.UTF_8,
                )
                .size

        if (
            bytes >
            DieselProtocolRules.MAX_TEXT_VALUE_BYTES
        ) {
            fail(
                reason =
                    DieselRequestFailureReason
                        .VALUE_TOO_LARGE,
                detail =
                    "text value exceeds byte limit",
            )
        }
    }

    private fun invalid(
        root: JSONObject?,
        reason: DieselRequestFailureReason,
        detail: String,
    ): DieselRequestDecodeResult.Invalid =
        DieselRequestDecodeResult.Invalid(
            failure =
                DieselInvalidRequest(
                    requestId =
                        root
                            ?.sanitizedRequestId(),
                    command =
                        root
                            ?.sanitizedCommand(),
                    name =
                        root
                            ?.sanitizedTarget(),
                    reason = reason,
                    detail = detail,
                ),
        )

    private fun JSONObject.sanitizedRequestId():
        String? {
        if (
            !has("id") ||
            isNull("id")
        ) {
            return null
        }

        val value =
            opt("id") as? String
                ?: return null

        return value.takeIf {
            DieselProtocolRules
                .isValidRequestId(it)
        }
    }

    private fun JSONObject.sanitizedCommand():
        String? {
        if (
            !has("cmd") ||
            isNull("cmd")
        ) {
            return null
        }

        val value =
            opt("cmd") as? String
                ?: return null

        return value.takeIf {
            DieselProtocolRules
                .isValidIdentifier(it)
        }
    }

    private fun JSONObject.sanitizedTarget():
        String? {
        if (
            !has("name") ||
            isNull("name")
        ) {
            return null
        }

        val value =
            opt("name") as? String
                ?: return null

        return value.takeIf {
            DieselProtocolRules
                .isValidIdentifier(it)
        }
    }

    private class NodeBudget {

        private var count =
            0

        fun consume() {
            count += 1

            if (
                count >
                DieselProtocolRules.MAX_VALUE_NODES
            ) {
                fail(
                    reason =
                        DieselRequestFailureReason
                            .TOO_MANY_VALUES,
                    detail =
                        "request contains too many value nodes",
                )
            }
        }
    }

    private class DecodeException(
        val reason: DieselRequestFailureReason,
        val detail: String,
    ) : RuntimeException()

    private fun fail(
        reason: DieselRequestFailureReason,
        detail: String,
    ): Nothing =
        throw DecodeException(
            reason = reason,
            detail = detail,
        )
}
