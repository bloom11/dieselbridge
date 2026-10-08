// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.aaustralian.dieselbridge.phone.DieselBridgePhoneApplication
import org.aaustralian.dieselbridge.phone.alarm.NextAlarmObservation
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselGatewayResult
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselValue

class DebugCompanionGatewayReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val application =
            context.applicationContext as?
                DieselBridgePhoneApplication
                ?: return fail("wrong_application")

        when (intent.action) {
            ACTION_ROUND_TRIP -> {
                val pending = goAsync()
                application.runtime.launch {
                    try {
                        val result =
                            application.runtime.gateway.buildInfo(
                                timeoutMs = 10_000L,
                            )
                        when (result) {
                            is PhoneDieselGatewayResult.Success -> {
                                val sha =
                                    (
                                        result.response.data["gitSha"] as?
                                            PhoneDieselValue.Text
                                    )?.value
                                        ?: "<missing>"
                                pending.setResultCode(
                                    Activity.RESULT_OK,
                                )
                                pending.setResultData(
                                    "status=ok;" +
                                        "id=${result.response.requestId};" +
                                        "watchGitSha=$sha",
                                )
                            }
                            else -> {
                                pending.setResultCode(
                                    Activity.RESULT_CANCELED,
                                )
                                pending.setResultData(
                                    "status=failed;result=" +
                                        result.javaClass.simpleName,
                                )
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_ALARM_SNAPSHOT -> {
                val observation =
                    application.runtime.nextAlarmProvider.read()
                val sync =
                    application.runtime.alarmSync.state.value
                setResultCode(Activity.RESULT_OK)
                setResultData(
                    "status=ok;" +
                        formatObservation(observation) +
                        ";sync=${sync.status.name.lowercase()}" +
                        ";attempts=${sync.attemptCount}",
                )
            }

            ACTION_ALARM_SYNC -> {
                val pending = goAsync()
                application.runtime.launch {
                    try {
                        val ok =
                            application.runtime.alarmSync.syncOnce(
                                "debug_receiver",
                            )
                        val state =
                            application.runtime.alarmSync.state.value
                        pending.setResultCode(
                            if (ok) {
                                Activity.RESULT_OK
                            } else {
                                Activity.RESULT_CANCELED
                            },
                        )
                        pending.setResultData(
                            "status=${if (ok) "ok" else "failed"};" +
                                "sync=${state.status.name.lowercase()};" +
                                "attempts=${state.attemptCount}",
                        )
                    } finally {
                        pending.finish()
                    }
                }
            }

            else ->
                fail("invalid_action")
        }
    }

    private fun fail(reason: String) {
        setResultCode(Activity.RESULT_CANCELED)
        setResultData("status=failed;reason=$reason")
    }

    private fun formatObservation(
        observation: NextAlarmObservation,
    ): String =
        when (observation) {
            is NextAlarmObservation.Scheduled ->
                "kind=scheduled;" +
                    "triggerAtMs=${observation.triggerAtMs};" +
                    "observedAtMs=${observation.observedAtMs}"
            is NextAlarmObservation.None ->
                "kind=none;" +
                    "observedAtMs=${observation.observedAtMs}"
            is NextAlarmObservation.Unavailable ->
                "kind=unavailable;" +
                    "reason=${observation.reason};" +
                    "observedAtMs=${observation.observedAtMs}"
        }

    companion object {
        const val ACTION_ROUND_TRIP =
            "io.github.bloom11.dieselbridge.phone.DEBUG_ROUND_TRIP"
        const val ACTION_ALARM_SNAPSHOT =
            "io.github.bloom11.dieselbridge.phone.DEBUG_ALARM_SNAPSHOT"
        const val ACTION_ALARM_SYNC =
            "io.github.bloom11.dieselbridge.phone.DEBUG_ALARM_SYNC"
    }
}
