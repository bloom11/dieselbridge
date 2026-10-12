// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.ipc

import android.os.Bundle
import android.os.IBinder
import org.aaustralian.dieselbridge.phone.api.PhoneDieselPublicApi
import org.aaustralian.dieselbridge.phone.api.PhoneDieselPublicApiVersion
import org.aaustralian.dieselbridge.phone.api.PhoneDieselPublicRequest
import org.aaustralian.dieselbridge.phone.api.PhoneDieselPublicResult
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselGateway
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselProtocolRules
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselValue

internal sealed interface PhoneDieselIpcRequestDecode {
    data class Value(
        val request:
            PhoneDieselPublicRequest,
    ) :
        PhoneDieselIpcRequestDecode

    data class Invalid(
        val reason: String,
    ) :
        PhoneDieselIpcRequestDecode
}

internal object PhoneDieselIpcCodec {
    const val STATUS_OK =
        "ok"

    private const val STATUS_INVALID =
        "invalid"

    private const val STATUS_DENIED =
        "denied"

    private const val STATUS_FAILED =
        "failed"

    private val REQUEST_KEYS =
        setOf(
            "command",
            "name",
            "args",
            "timeoutMs",
        )

    private class Budget(
        var nodes: Int = 0,
    )

    fun serviceInfo(
        runtimeAvailable: Boolean,
        version:
            PhoneDieselPublicApiVersion =
            PhoneDieselPublicApiVersion.CURRENT,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                STATUS_OK,
            )
            putInt(
                "apiMajor",
                version.major,
            )
            putInt(
                "apiMinor",
                version.minor,
            )
            putBoolean(
                "runtimeAvailable",
                runtimeAvailable,
            )
            putInt(
                "maxSessions",
                PhoneDieselSessionAdmission.MAX_SESSIONS,
            )
            putInt(
                "maxConcurrentInvocationsPerSession",
                PhoneDieselInvocationGate.DEFAULT_MAX_CONCURRENT,
            )
            putLong(
                "defaultTimeoutMs",
                PhoneDieselGateway.DEFAULT_TIMEOUT_MS,
            )
            putLong(
                "maxTimeoutMs",
                PhoneDieselGateway.MAX_TIMEOUT_MS,
            )
        }

    fun sessionOpenSuccess(
        sessionBinder: IBinder,
        version:
            PhoneDieselPublicApiVersion,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                STATUS_OK,
            )
            putBinder(
                "session",
                sessionBinder,
            )
            putInt(
                "apiMajor",
                version.major,
            )
            putInt(
                "apiMinor",
                version.minor,
            )
        }

    fun sessionOpenRejected(
        reason:
            PhoneDieselSessionOpenRejection,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                reason.wireStatus,
            )
        }

    fun catalog(
        api: PhoneDieselPublicApi,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                STATUS_OK,
            )
            putInt(
                "apiMajor",
                api.version.major,
            )
            putInt(
                "apiMinor",
                api.version.minor,
            )
            putStringArrayList(
                "commands",
                ArrayList(
                    api.allowedCommands(),
                ),
            )
        }

    fun decodeRequest(
        bundle: Bundle,
    ): PhoneDieselIpcRequestDecode =
        try {
            decodeRequestUnsafe(
                bundle,
            )
        } catch (_: RuntimeException) {
            PhoneDieselIpcRequestDecode.Invalid(
                "invalid_request_bundle",
            )
        }

    private fun decodeRequestUnsafe(
        bundle: Bundle,
    ): PhoneDieselIpcRequestDecode {
        if (
            bundle.keySet()
                .any {
                    it !in REQUEST_KEYS
                }
        ) {
            return PhoneDieselIpcRequestDecode.Invalid(
                "unexpected_request_field",
            )
        }

        val commandValue =
            bundle.get(
                "command",
            )

        if (commandValue !is String) {
            return PhoneDieselIpcRequestDecode.Invalid(
                "missing_command",
            )
        }

        val nameValue =
            if (
                bundle.containsKey(
                    "name",
                )
            ) {
                bundle.get(
                    "name",
                )
            } else {
                null
            }

        if (
            nameValue != null &&
            nameValue !is String
        ) {
            return PhoneDieselIpcRequestDecode.Invalid(
                "invalid_name",
            )
        }

        val timeoutMs =
            if (
                bundle.containsKey(
                    "timeoutMs",
                )
            ) {
                val raw =
                    bundle.get(
                        "timeoutMs",
                    )

                if (raw !is Long) {
                    return PhoneDieselIpcRequestDecode.Invalid(
                        "invalid_timeout",
                    )
                }

                raw
            } else {
                PhoneDieselGateway.DEFAULT_TIMEOUT_MS
            }

        val args =
            if (
                bundle.containsKey(
                    "args",
                )
            ) {
                val raw =
                    bundle.get(
                        "args",
                    )

                if (raw !is Bundle) {
                    return PhoneDieselIpcRequestDecode.Invalid(
                        "invalid_args",
                    )
                }

                decodeMap(
                    raw,
                    depth = 0,
                    budget = Budget(),
                )
            } else {
                emptyMap()
            }

        return PhoneDieselIpcRequestDecode.Value(
            PhoneDieselPublicRequest(
                command =
                    commandValue,
                name =
                    nameValue as?
                        String,
                args =
                    args,
                timeoutMs =
                    timeoutMs,
            ),
        )
    }

    fun encodeResult(
        result:
            PhoneDieselPublicResult,
    ): Bundle =
        when (result) {
            is PhoneDieselPublicResult.Success ->
                Bundle().apply {
                    putString(
                        "status",
                        "success",
                    )
                    putString(
                        "command",
                        result.command,
                    )
                    result.name?.let {
                        putString(
                            "name",
                            it,
                        )
                    }
                    putString(
                        "remoteStatus",
                        result.remoteStatus,
                    )
                    putBundle(
                        "data",
                        encodeMap(
                            result.data,
                            depth = 0,
                            budget = Budget(),
                        ),
                    )
                }

            is PhoneDieselPublicResult.Invalid ->
                resultBundle(
                    STATUS_INVALID,
                    result.reason,
                )

            is PhoneDieselPublicResult.Denied ->
                resultBundle(
                    STATUS_DENIED,
                    result.reason,
                )

            is PhoneDieselPublicResult.Failed ->
                resultBundle(
                    STATUS_FAILED,
                    result.reason,
                )
        }

    fun busyResult():
        Bundle =
        resultBundle(
            STATUS_FAILED,
            "session_busy",
        )

    fun malformedRequestResult(
        reason: String,
    ): Bundle =
        resultBundle(
            STATUS_INVALID,
            reason,
        )

    private fun resultBundle(
        status: String,
        reason: String,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                status,
            )
            putString(
                "reason",
                reason,
            )
        }

    private fun encodeMap(
        values:
            Map<String, PhoneDieselValue>,
        depth: Int,
        budget: Budget,
    ): Bundle {
        requireDepth(
            depth,
        )

        require(
            values.size <=
            PhoneDieselProtocolRules.MAX_COLLECTION_ENTRIES,
        )

        return Bundle().apply {
            values.forEach {
                (
                    key,
                    value,
                ) ->
                require(
                    PhoneDieselProtocolRules.isValidFieldName(
                        key,
                    ),
                )

                putBundle(
                    key,
                    encodeValue(
                        value,
                        depth + 1,
                        budget,
                    ),
                )
            }
        }
    }

    private fun encodeValue(
        value: PhoneDieselValue,
        depth: Int,
        budget: Budget,
    ): Bundle {
        consumeNode(
            budget,
        )
        requireDepth(
            depth,
        )

        return Bundle().apply {
            when (value) {
                PhoneDieselValue.Null -> {
                    putString(
                        "type",
                        "null",
                    )
                }

                is PhoneDieselValue.Text -> {
                    requireText(
                        value.value,
                    )
                    putString(
                        "type",
                        "text",
                    )
                    putString(
                        "text",
                        value.value,
                    )
                }

                is PhoneDieselValue.Integer -> {
                    putString(
                        "type",
                        "integer",
                    )
                    putLong(
                        "integer",
                        value.value,
                    )
                }

                is PhoneDieselValue.Decimal -> {
                    require(
                        value.value.isFinite(),
                    )
                    putString(
                        "type",
                        "decimal",
                    )
                    putDouble(
                        "decimal",
                        value.value,
                    )
                }

                is PhoneDieselValue.Flag -> {
                    putString(
                        "type",
                        "flag",
                    )
                    putBoolean(
                        "flag",
                        value.value,
                    )
                }

                is PhoneDieselValue.ObjectValue -> {
                    putString(
                        "type",
                        "object",
                    )
                    putBundle(
                        "fields",
                        encodeMap(
                            value.value,
                            depth,
                            budget,
                        ),
                    )
                }

                is PhoneDieselValue.ListValue -> {
                    require(
                        value.value.size <=
                        PhoneDieselProtocolRules
                            .MAX_COLLECTION_ENTRIES,
                    )

                    putString(
                        "type",
                        "list",
                    )
                    putInt(
                        "count",
                        value.value.size,
                    )

                    value.value.forEachIndexed {
                        index,
                        item,
                        ->
                        putBundle(
                            "item.$index",
                            encodeValue(
                                item,
                                depth + 1,
                                budget,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun decodeMap(
        bundle: Bundle,
        depth: Int,
        budget: Budget,
    ): Map<String, PhoneDieselValue> {
        requireDepth(
            depth,
        )

        val keys =
            bundle.keySet()

        require(
            keys.size <=
            PhoneDieselProtocolRules.MAX_COLLECTION_ENTRIES,
        )

        val result =
            linkedMapOf<String, PhoneDieselValue>()

        keys.sorted().forEach {
            key ->
            require(
                PhoneDieselProtocolRules.isValidFieldName(
                    key,
                ),
            )

            val encoded =
                bundle.get(
                    key,
                ) as?
                    Bundle
                    ?: error(
                        "Invalid encoded Diesel value",
                    )

            result[
                key
            ] =
                decodeValue(
                    encoded,
                    depth + 1,
                    budget,
                )
        }

        return result
    }

    private fun decodeValue(
        bundle: Bundle,
        depth: Int,
        budget: Budget,
    ): PhoneDieselValue {
        consumeNode(
            budget,
        )
        requireDepth(
            depth,
        )

        return when (
            bundle.getString(
                "type",
            )
        ) {
            "null" ->
                PhoneDieselValue.Null

            "text" -> {
                val value =
                    bundle.get(
                        "text",
                    ) as?
                        String
                        ?: error(
                            "Missing text",
                        )

                requireText(
                    value,
                )

                PhoneDieselValue.Text(
                    value,
                )
            }

            "integer" -> {
                val value =
                    bundle.get(
                        "integer",
                    )

                require(
                    value is Long,
                )

                PhoneDieselValue.Integer(
                    value,
                )
            }

            "decimal" -> {
                val value =
                    bundle.get(
                        "decimal",
                    )

                require(
                    value is Double &&
                    value.isFinite(),
                )

                PhoneDieselValue.Decimal(
                    value,
                )
            }

            "flag" -> {
                val value =
                    bundle.get(
                        "flag",
                    )

                require(
                    value is Boolean,
                )

                PhoneDieselValue.Flag(
                    value,
                )
            }

            "object" -> {
                val fields =
                    bundle.get(
                        "fields",
                    ) as?
                        Bundle
                        ?: error(
                            "Missing object fields",
                        )

                PhoneDieselValue.ObjectValue(
                    decodeMap(
                        fields,
                        depth,
                        budget,
                    ),
                )
            }

            "list" -> {
                val rawCount =
                    bundle.get(
                        "count",
                    )

                require(
                    rawCount is Int &&
                    rawCount >= 0 &&
                    rawCount <=
                    PhoneDieselProtocolRules
                        .MAX_COLLECTION_ENTRIES,
                )

                PhoneDieselValue.ListValue(
                    (0 until rawCount).map {
                        index ->
                        val item =
                            bundle.get(
                                "item.$index",
                            ) as?
                                Bundle
                                ?: error(
                                    "Missing list item",
                                )

                        decodeValue(
                            item,
                            depth + 1,
                            budget,
                        )
                    },
                )
            }

            else ->
                error(
                    "Unknown Diesel value type",
                )
        }
    }

    private fun consumeNode(
        budget: Budget,
    ) {
        budget.nodes++

        require(
            budget.nodes <=
            PhoneDieselProtocolRules.MAX_VALUE_NODES,
        )
    }

    private fun requireDepth(
        depth: Int,
    ) {
        require(
            depth <=
            PhoneDieselProtocolRules.MAX_VALUE_DEPTH,
        )
    }

    private fun requireText(
        value: String,
    ) {
        require(
            value
                .toByteArray(
                    Charsets.UTF_8,
                )
                .size <=
            PhoneDieselProtocolRules.MAX_TEXT_VALUE_BYTES,
        )
    }
}
