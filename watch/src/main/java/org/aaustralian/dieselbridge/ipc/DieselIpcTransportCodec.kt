// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import android.os.Bundle
import org.aaustralian.dieselbridge.platform.transport.DieselTransportOpenResult
import org.aaustralian.dieselbridge.platform.transport.DieselTransportSendResult

internal object DieselIpcTransportCodec {
    fun encodeOpen(
        result: DieselTransportOpenResult,
    ): Bundle =
        when (result) {
            is DieselTransportOpenResult.Opened ->
                Bundle().apply {
                    putString(
                        "status",
                        DieselIpcCodec.STATUS_OK,
                    )
                    putLong(
                        "leaseId",
                        result.lease.id,
                    )
                    putString(
                        "resourceId",
                        result.lease.resourceId,
                    )
                    putInt(
                        "maxFrameBytes",
                        result.lease.maxFrameBytes,
                    )
                }

            DieselTransportOpenResult.Unavailable ->
                Bundle().apply {
                    putString(
                        "status",
                        DieselIpcCodec.STATUS_UNAVAILABLE,
                    )
                }

            is DieselTransportOpenResult.Rejected ->
                rejected(
                    result.reason,
                )

            is DieselTransportOpenResult.Failed ->
                failed(
                    result.reason,
                )
        }

    fun notFound(
        resourceId: String,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                DieselIpcCodec.STATUS_NOT_FOUND,
            )
            putString(
                "resourceId",
                resourceId,
            )
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

    fun failed(
        reason: String,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                DieselIpcCodec.STATUS_FAILED,
            )
            putString(
                "reason",
                reason,
            )
        }

    fun encodeSend(
        leaseId: Long,
        result: DieselTransportSendResult,
    ): Bundle =
        when (result) {
            DieselTransportSendResult.Success ->
                Bundle().apply {
                    putString(
                        "status",
                        DieselIpcCodec.STATUS_OK,
                    )
                    putLong(
                        "leaseId",
                        leaseId,
                    )
                }

            is DieselTransportSendResult.Rejected ->
                rejected(
                    result.reason,
                ).apply {
                    putLong(
                        "leaseId",
                        leaseId,
                    )
                }

            is DieselTransportSendResult.Failed ->
                failed(
                    result.reason,
                ).apply {
                    putLong(
                        "leaseId",
                        leaseId,
                    )
                }
        }

    fun sendNotFound(
        leaseId: Long,
    ): Bundle =
        Bundle().apply {
            putString(
                "status",
                DieselIpcCodec.STATUS_NOT_FOUND,
            )
            putLong(
                "leaseId",
                leaseId,
            )
        }
}
