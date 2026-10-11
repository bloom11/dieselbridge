// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import android.os.Bundle
import org.aaustralian.dieselbridge.api.DieselProviderResourceRequest
import org.aaustralian.dieselbridge.api.DieselPublicDecodeResult
import org.aaustralian.dieselbridge.api.DieselResourceKind

internal object DieselIpcProviderCodec {
    fun decodeRequest(
        bundle: Bundle,
    ): DieselPublicDecodeResult<DieselProviderResourceRequest> {
        val id =
            bundle.getString("id")
                ?: return DieselPublicDecodeResult.Invalid(
                    "missing_id",
                )
        val schema =
            bundle.getString("schema")
                ?: return DieselPublicDecodeResult.Invalid(
                    "missing_schema",
                )
        val summary =
            bundle.getString("summary")
                ?: return DieselPublicDecodeResult.Invalid(
                    "missing_summary",
                )

        val kind =
            when (bundle.getString("kind")?.lowercase()) {
                "state" ->
                    DieselResourceKind.STATE

                "action" ->
                    DieselResourceKind.ACTION

                "observation" ->
                    DieselResourceKind.OBSERVATION

                "event" ->
                    DieselResourceKind.EVENT

                "transport" ->
                    DieselResourceKind.TRANSPORT

                else ->
                    return DieselPublicDecodeResult.Invalid(
                        "invalid_kind",
                    )
            }

        return DieselPublicDecodeResult.Value(
            DieselProviderResourceRequest(
                id,
                kind,
                schema,
                summary,
            ),
        )
    }

    fun ok(
        registrationId: Long? = null,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                DieselIpcCodec.STATUS_OK,
            )
            registrationId?.let {
                putLong(
                    "registrationId",
                    it,
                )
            }
        }

    fun rejected(
        reason: String,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                DieselIpcCodec.STATUS_REJECTED,
            )
            putString(
                "reason",
                reason,
            )
        }
}
