// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.aaustralian.dieselbridge.phone.alarm.NextAlarmObservation
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselGatewayResult
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselValue

class MainActivity : Activity() {
    private val activityScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate,
        )

    private lateinit var runtime: PhoneCompanionRuntime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        runtime =
            (application as DieselBridgePhoneApplication).runtime

        val buildInfo =
            PhoneBuildInfo(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                gitSha = BuildConfig.BUILD_GIT_SHA,
                ciRunId = BuildConfig.BUILD_CI_RUN_ID,
                buildTimestampUtc =
                    BuildConfig.BUILD_TIMESTAMP_UTC,
            )

        val gatewayStatus =
            TextView(this).apply {
                text = "No round trip completed yet."
                textSize = 14f
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(12), 0, dp(16))
            }

        val alarmStatus =
            TextView(this).apply {
                text = "Alarm sync state loading..."
                textSize = 14f
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(12), 0, 0)
            }

        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(24),
                    dp(24),
                    dp(24),
                    dp(24),
                )

                addView(
                    TextView(context).apply {
                        text = "DieselBridge Companion"
                        textSize = 24f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                )

                addView(
                    TextView(context).apply {
                        text = buildInfo.diagnosticText()
                        textSize = 14f
                        setTextIsSelectable(true)
                        typeface = Typeface.MONOSPACE
                        setPadding(0, dp(12), 0, dp(20))
                    },
                )

                addView(
                    Button(context).apply {
                        text = "Round-trip watch build info"
                        setOnClickListener {
                            gatewayStatus.text =
                                "Waiting for correlated watch response..."
                            activityScope.launch {
                                gatewayStatus.text =
                                    when (
                                        val result =
                                            runtime.gateway.buildInfo(
                                                timeoutMs = 10_000L,
                                            )
                                    ) {
                                        is PhoneDieselGatewayResult.Success -> {
                                            val watchSha =
                                                (
                                                    result.response
                                                        .data["gitSha"] as?
                                                        PhoneDieselValue.Text
                                                )?.value
                                                    ?: "<missing>"

                                            "Correlated response received.\n" +
                                                "Request ID: " +
                                                result.response.requestId +
                                                "\nStatus: " +
                                                result.response.status.wireName +
                                                "\nWatch Git SHA: " +
                                                watchSha
                                        }
                                        else ->
                                            "Round trip failed: $result"
                                    }
                            }
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )

                addView(gatewayStatus)

                addView(
                    Button(context).apply {
                        text = "Sync next alarm now"
                        setOnClickListener {
                            activityScope.launch {
                                runtime.alarmSync.syncOnce(
                                    "manual_ui",
                                )
                            }
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )

                addView(alarmStatus)
            }

        setContentView(
            ScrollView(this).apply {
                addView(body)
            },
        )

        activityScope.launch {
            runtime.alarmSync.state.collect { snapshot ->
                alarmStatus.text =
                    buildString {
                        append("Sync: ")
                        append(snapshot.status)
                        append("\nDesired: ")
                        append(
                            formatObservation(snapshot.desired),
                        )
                        append("\nConfirmed: ")
                        append(
                            formatObservation(snapshot.confirmed),
                        )
                        append("\nAttempts: ")
                        append(snapshot.attemptCount)
                        snapshot.lastError?.let {
                            append("\nLast error: ")
                            append(it)
                        }
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::runtime.isInitialized) {
            runtime.alarmSync.requestRefresh(
                "activity_resume",
            )
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun formatObservation(
        observation: NextAlarmObservation?,
    ): String =
        when (observation) {
            null -> "<none yet>"
            is NextAlarmObservation.Scheduled ->
                "scheduled@${observation.triggerAtMs}"
            is NextAlarmObservation.None ->
                "none"
            is NextAlarmObservation.Unavailable ->
                "unavailable:${observation.reason}"
        }

    private fun dp(value: Int): Int =
        (
            value *
                resources.displayMetrics.density
        ).toInt()
}
