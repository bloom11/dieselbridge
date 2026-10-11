// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ipc

import android.os.Bundle
import android.os.IBinder
import org.aaustralian.dieselbridge.api.DieselApiVersion
import org.aaustralian.dieselbridge.api.DieselCatalogSnapshot
import org.aaustralian.dieselbridge.api.DieselPublicActionResult
import org.aaustralian.dieselbridge.api.DieselPublicDecodeResult
import org.aaustralian.dieselbridge.api.DieselPublicStateResult
import org.aaustralian.dieselbridge.api.DieselResourceDescriptor
import org.aaustralian.dieselbridge.protocol.DieselProtocolRules
import org.aaustralian.dieselbridge.protocol.DieselValue

internal object DieselIpcCodec {
    const val STATUS_OK =
        "ok"

    const val STATUS_NOT_FOUND =
        "not_found"

    const val STATUS_INVALID =
        "invalid"

    const val STATUS_UNAVAILABLE =
        "unavailable"

    const val STATUS_REJECTED =
        "rejected"

    const val STATUS_FAILED =
        "failed"

    fun encodeServiceInfo(
        runtimeAvailable: Boolean,
    ): Bundle =
        Bundle().apply {
            putInt(
                "apiMajor",
                DieselApiVersion.CURRENT.major,
            )
            putInt(
                "apiMinor",
                DieselApiVersion.CURRENT.minor,
            )
            putBoolean(
                "runtimeAvailable",
                runtimeAvailable,
            )
        }

    fun encodeSessionOpenSuccess(
        sessionBinder: IBinder,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                STATUS_OK,
            )
            putInt(
                "apiMajor",
                DieselApiVersion.CURRENT.major,
            )
            putInt(
                "apiMinor",
                DieselApiVersion.CURRENT.minor,
            )
            putBinder(
                "session",
                sessionBinder,
            )
        }

    fun encodeSessionOpenRejected(
        rejection: DieselSessionOpenRejection,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                rejection.wireStatus,
            )
            putInt(
                "apiMajor",
                DieselApiVersion.CURRENT.major,
            )
            putInt(
                "apiMinor",
                DieselApiVersion.CURRENT.minor,
            )
        }

    fun encodeCatalog(
        snapshot: DieselCatalogSnapshot,
        resources: List<DieselResourceDescriptor> =
            snapshot.resources,
    ): Bundle =
        Bundle().apply {
            putInt(
                "apiMajor",
                snapshot.apiVersion.major,
            )
            putInt(
                "apiMinor",
                snapshot.apiVersion.minor,
            )
            putParcelableArrayList(
                "resources",
                ArrayList(
                    resources.map(
                        ::encodeDescriptor,
                    ),
                ),
            )
        }

    private fun encodeDescriptor(
        descriptor: DieselResourceDescriptor,
    ): Bundle =
        Bundle().apply {
            putString(
                "id",
                descriptor.id,
            )
            putString(
                "kind",
                descriptor.kind.name.lowercase(),
            )
            putString(
                "schema",
                descriptor.schema,
            )
            putStringArrayList(
                "operations",
                ArrayList(
                    descriptor.operations
                        .map {
                            it.name.lowercase()
                        }
                        .sorted(),
                ),
            )
            putString(
                "summary",
                descriptor.summary,
            )
        }

    fun encodeStateResult(
        result: DieselPublicStateResult,
    ): Bundle =
        when (result) {
            is DieselPublicStateResult.Success ->
                Bundle().apply {
                    putString(
                        "status",
                        STATUS_OK,
                    )

                    val snapshot =
                        result.snapshot

                    putBoolean(
                        "present",
                        snapshot.value != null,
                    )

                    snapshot.value?.let {
                        putBundle(
                            "value",
                            encodeValue(
                                it,
                            ),
                        )
                        putLong(
                            "revision",
                            requireNotNull(
                                snapshot.revision,
                            ),
                        )
                        putLong(
                            "updatedAtMs",
                            requireNotNull(
                                snapshot.updatedAtMs,
                            ),
                        )
                    }
                }

            is DieselPublicStateResult.NotFound ->
                encodeNotFound(
                    result.resourceId,
                )

            is DieselPublicStateResult.Failed ->
                Bundle().apply {
                    putString(
                        "status",
                        STATUS_FAILED,
                    )
                    putString(
                        "reason",
                        result.reason,
                    )
                }
        }

    fun encodeActionResult(
        result: DieselPublicActionResult,
    ): Bundle =
        when (result) {
            is DieselPublicActionResult.Success ->
                Bundle().apply {
                    putString(
                        "status",
                        STATUS_OK,
                    )
                    putBundle(
                        "value",
                        encodeValue(
                            result.value,
                        ),
                    )
                }

            is DieselPublicActionResult.NotFound ->
                encodeNotFound(
                    result.resourceId,
                )

            is DieselPublicActionResult.InvalidInput ->
                Bundle().apply {
                    putString(
                        "status",
                        STATUS_INVALID,
                    )
                    putString(
                        "reason",
                        result.reason,
                    )
                }

            DieselPublicActionResult.Unavailable ->
                Bundle().apply {
                    putString(
                        "status",
                        STATUS_UNAVAILABLE,
                    )
                }

            is DieselPublicActionResult.Rejected ->
                Bundle().apply {
                    putString(
                        "status",
                        STATUS_REJECTED,
                    )
                    putString(
                        "reason",
                        result.reason,
                    )
                }

            is DieselPublicActionResult.Failed ->
                Bundle().apply {
                    putString(
                        "status",
                        STATUS_FAILED,
                    )
                    putString(
                        "reason",
                        result.reason,
                    )
                }
        }

    fun encodeNotFound(
        resourceId: String,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                STATUS_NOT_FOUND,
            )
            putString(
                "resourceId",
                resourceId,
            )
        }

    fun encodeValue(
        value: DieselValue,
    ): Bundle =
        when (value) {
            DieselValue.Null ->
                Bundle().apply {
                    putString(
                        "type",
                        "null",
                    )
                }

            is DieselValue.Text ->
                Bundle().apply {
                    putString(
                        "type",
                        "text",
                    )
                    putString(
                        "text",
                        value.value,
                    )
                }

            is DieselValue.Integer ->
                Bundle().apply {
                    putString(
                        "type",
                        "integer",
                    )
                    putLong(
                        "integer",
                        value.value,
                    )
                }

            is DieselValue.Decimal ->
                Bundle().apply {
                    putString(
                        "type",
                        "decimal",
                    )
                    putDouble(
                        "decimal",
                        value.value,
                    )
                }

            is DieselValue.Flag ->
                Bundle().apply {
                    putString(
                        "type",
                        "flag",
                    )
                    putBoolean(
                        "flag",
                        value.value,
                    )
                }

            is DieselValue.ObjectValue ->
                Bundle().apply {
                    putString(
                        "type",
                        "object",
                    )
                    putBundle(
                        "object",
                        Bundle().apply {
                            value.value.forEach {
                                    key,
                                    nested,
                                ->
                                putBundle(
                                    key,
                                    encodeValue(
                                        nested,
                                    ),
                                )
                            }
                        },
                    )
                }

            is DieselValue.ListValue ->
                Bundle().apply {
                    putString(
                        "type",
                        "list",
                    )
                    putParcelableArrayList(
                        "list",
                        ArrayList(
                            value.value.map(
                                ::encodeValue,
                            ),
                        ),
                    )
                }
        }

    fun decodeValue(
        bundle: Bundle,
    ): DieselPublicDecodeResult<DieselValue> =
        try {
            DieselPublicDecodeResult.Value(
                decodeValueInternal(
                    bundle =
                        bundle,
                    depth =
                        1,
                    budget =
                        NodeBudget(),
                ),
            )
        } catch (
            error:
                DecodeFailure,
        ) {
            DieselPublicDecodeResult.Invalid(
                error.reason,
            )
        }

    private fun decodeValueInternal(
        bundle: Bundle,
        depth: Int,
        budget: NodeBudget,
    ): DieselValue {
        if (
            depth >
            DieselProtocolRules.MAX_VALUE_DEPTH
        ) {
            fail(
                "structure_too_deep",
            )
        }

        budget.consume()

        return when (
            bundle.getString(
                "type",
            )
        ) {
            "null" ->
                DieselValue.Null

            "text" -> {
                val text =
                    bundle.getString(
                        "text",
                    )
                        ?: fail(
                            "missing_text",
                        )

                if (
                    text
                        .toByteArray(
                            Charsets.UTF_8,
                        )
                        .size >
                    DieselProtocolRules.MAX_TEXT_VALUE_BYTES
                ) {
                    fail(
                        "text_too_large",
                    )
                }

                DieselValue.Text(
                    text,
                )
            }

            "integer" -> {
                if (
                    !bundle.containsKey(
                        "integer",
                    )
                ) {
                    fail(
                        "missing_integer",
                    )
                }

                DieselValue.Integer(
                    bundle.getLong(
                        "integer",
                    ),
                )
            }

            "decimal" -> {
                if (
                    !bundle.containsKey(
                        "decimal",
                    )
                ) {
                    fail(
                        "missing_decimal",
                    )
                }

                val value =
                    bundle.getDouble(
                        "decimal",
                    )

                if (!value.isFinite()) {
                    fail(
                        "invalid_decimal",
                    )
                }

                DieselValue.Decimal(
                    value,
                )
            }

            "flag" -> {
                if (
                    !bundle.containsKey(
                        "flag",
                    )
                ) {
                    fail(
                        "missing_flag",
                    )
                }

                DieselValue.Flag(
                    bundle.getBoolean(
                        "flag",
                    ),
                )
            }

            "object" -> {
                val nested =
                    bundle.getBundle(
                        "object",
                    )
                        ?: fail(
                            "missing_object",
                        )

                if (
                    nested.size() >
                    DieselProtocolRules.MAX_COLLECTION_ENTRIES
                ) {
                    fail(
                        "object_too_large",
                    )
                }

                val decoded =
                    linkedMapOf<String, DieselValue>()

                nested.keySet()
                    .sorted()
                    .forEach {
                        key ->
                        if (
                            !DieselProtocolRules.isValidFieldName(
                                key,
                            )
                        ) {
                            fail(
                                "invalid_field_name",
                            )
                        }

                        decoded[
                            key
                        ] =
                            decodeValueInternal(
                                bundle =
                                    nested.getBundle(
                                        key,
                                    )
                                        ?: fail(
                                            "invalid_object_value",
                                        ),
                                depth =
                                    depth + 1,
                                budget =
                                    budget,
                            )
                    }

                DieselValue.ObjectValue(
                    decoded,
                )
            }

            "list" -> {
                @Suppress("DEPRECATION")
                val items =
                    bundle.getParcelableArrayList<Bundle>(
                        "list",
                    )
                        ?: fail(
                            "missing_list",
                        )

                if (
                    items.size >
                    DieselProtocolRules.MAX_COLLECTION_ENTRIES
                ) {
                    fail(
                        "list_too_large",
                    )
                }

                DieselValue.ListValue(
                    items.map {
                        child ->
                        decodeValueInternal(
                            bundle =
                                child,
                            depth =
                                depth + 1,
                            budget =
                                budget,
                        )
                    },
                )
            }

            else ->
                fail(
                    "invalid_value_type",
                )
        }
    }

    private class NodeBudget {
        private var count =
            0

        fun consume() {
            count +=
                1

            if (
                count >
                DieselProtocolRules.MAX_VALUE_NODES
            ) {
                fail(
                    "too_many_value_nodes",
                )
            }
        }
    }

    private class DecodeFailure(
        val reason: String,
    ) : RuntimeException()

    private fun fail(
        reason: String,
    ): Nothing =
        throw DecodeFailure(
            reason,
        )
}
