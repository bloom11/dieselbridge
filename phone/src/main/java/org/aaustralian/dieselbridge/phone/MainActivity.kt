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
import org.aaustralian.dieselbridge.phone.gateway.GadgetbridgeIntentTransport
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselClient

class MainActivity : Activity() {

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(
            savedInstanceState,
        )

        val buildInfo =
            PhoneBuildInfo(
                applicationId =
                    BuildConfig.APPLICATION_ID,
                versionName =
                    BuildConfig.VERSION_NAME,
                versionCode =
                    BuildConfig.VERSION_CODE.toLong(),
                gitSha =
                    BuildConfig.BUILD_GIT_SHA,
                ciRunId =
                    BuildConfig.BUILD_CI_RUN_ID,
                buildTimestampUtc =
                    BuildConfig.BUILD_TIMESTAMP_UTC,
            )

        val client =
            PhoneDieselClient(
                GadgetbridgeIntentTransport(
                    applicationContext,
                ),
            )

        val status =
            TextView(
                this,
            ).apply {
                text =
                    "No request submitted yet.\n" +
                        "Submission is not a watch acknowledgement."
                textSize =
                    14f
                setTextIsSelectable(
                    true,
                )
                typeface =
                    Typeface.MONOSPACE
                setPadding(
                    0,
                    dp(12),
                    0,
                    0,
                )
            }

        val body =
            LinearLayout(
                this,
            ).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(24),
                    dp(24),
                    dp(24),
                    dp(24),
                )

                addView(
                    TextView(
                        context,
                    ).apply {
                        text =
                            "DieselBridge Companion"
                        textSize =
                            24f
                        setTypeface(
                            typeface,
                            Typeface.BOLD,
                        )
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )

                addView(
                    TextView(
                        context,
                    ).apply {
                        text =
                            "M6.0b sends one-way Diesel requests through " +
                                "stock Gadgetbridge. In the connected " +
                                "Bangle-compatible device settings, enable " +
                                "Allow Intents before testing."
                        textSize =
                            16f
                        setPadding(
                            0,
                            dp(12),
                            0,
                            dp(20),
                        )
                    },
                )

                addView(
                    TextView(
                        context,
                    ).apply {
                        text =
                            buildInfo
                                .diagnosticText()
                        textSize =
                            14f
                        setTextIsSelectable(
                            true,
                        )
                        typeface =
                            Typeface.MONOSPACE
                    },
                )

                addView(
                    Button(
                        context,
                    ).apply {
                        text =
                            "Send watch build-info request"

                        setOnClickListener {
                            status.text =
                                runCatching {
                                    client
                                        .sendBuildInfo()
                                }.fold(
                                    onSuccess = {
                                            dispatch,
                                            ->
                                            buildString {
                                                append(
                                                    "Submitted to Android broadcast.\n",
                                                )
                                                append(
                                                    "Request ID: ",
                                                )
                                                append(
                                                    dispatch
                                                        .request
                                                        .requestId,
                                                )
                                                append(
                                                    "\nCommand: ",
                                                )
                                                append(
                                                    dispatch
                                                        .request
                                                        .command,
                                                )
                                                append(
                                                    "\nLine: ",
                                                )
                                                append(
                                                    dispatch
                                                        .gadgetbridgeLine,
                                                )
                                                append(
                                                    "\n\nM6.0b does not consume the watch response.",
                                                )
                                            }
                                        },
                                    onFailure = {
                                            error,
                                            ->
                                            "Local submission failed: " +
                                                (
                                                    error.message
                                                        ?: error.javaClass.simpleName
                                                )
                                        },
                                )
                        }
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin =
                            dp(20)
                    },
                )

                addView(
                    status,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

        setContentView(
            ScrollView(
                this,
            ).apply {
                addView(
                    body,
                )
            },
        )
    }

    private fun dp(
        value: Int,
    ): Int =
        (
            value *
                resources
                    .displayMetrics
                    .density
        ).toInt()
}
