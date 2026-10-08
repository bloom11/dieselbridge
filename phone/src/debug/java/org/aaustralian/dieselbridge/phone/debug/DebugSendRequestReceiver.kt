// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.aaustralian.dieselbridge.phone.BuildConfig
import org.aaustralian.dieselbridge.phone.gateway.GadgetbridgeIntentTransport
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselClient

/**
 * Debug-build-only exact-SHA hardware-proof seam.
 *
 * The external caller supplies only the correlation ID. The command is fixed
 * to read-only debug.build.info, so this receiver cannot be used to invoke
 * arbitrary Diesel commands.
 */
class DebugSendRequestReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (
            intent.action != ACTION
        ) {
            setResultCode(
                Activity.RESULT_CANCELED,
            )
            setResultData(
                "status=invalid_action",
            )
            return
        }

        val requestId =
            intent
                .getStringExtra(
                    EXTRA_REQUEST_ID,
                )

        if (
            requestId.isNullOrBlank()
        ) {
            setResultCode(
                Activity.RESULT_CANCELED,
            )
            setResultData(
                "status=missing_request_id",
            )
            return
        }

        runCatching {
            PhoneDieselClient(
                GadgetbridgeIntentTransport(
                    context,
                ),
            ).sendBuildInfo(
                requestId =
                    requestId,
            )
        }.onSuccess {
            dispatch,
            ->
            setResultCode(
                Activity.RESULT_OK,
            )
            setResultData(
                buildString {
                    append(
                        "status=submitted",
                    )
                    append(
                        ";id=",
                    )
                    append(
                        dispatch
                            .request
                            .requestId,
                    )
                    append(
                        ";cmd=",
                    )
                    append(
                        dispatch
                            .request
                            .command,
                    )
                    append(
                        ";gitSha=",
                    )
                    append(
                        BuildConfig
                            .BUILD_GIT_SHA,
                    )
                },
            )
        }.onFailure {
            error,
            ->
            setResultCode(
                Activity.RESULT_CANCELED,
            )
            setResultData(
                "status=failed;error=" +
                    (
                        error.message
                            ?: error.javaClass.simpleName
                    ),
            )
        }
    }

    companion object {
        const val ACTION =
            "io.github.bloom11.dieselbridge.phone.DEBUG_SEND_REQUEST"

        const val EXTRA_REQUEST_ID =
            "requestId"
    }
}
