// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        val buildInfo =
            PhoneBuildInfo(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                gitSha = BuildConfig.BUILD_GIT_SHA,
                ciRunId = BuildConfig.BUILD_CI_RUN_ID,
                buildTimestampUtc = BuildConfig.BUILD_TIMESTAMP_UTC,
            )

        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))

                addView(
                    TextView(context).apply {
                        text = "DieselBridge Companion"
                        textSize = 24f
                        setTypeface(typeface, Typeface.BOLD)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )

                addView(
                    TextView(context).apply {
                        text =
                            "Phone-side Diesel foundation. " +
                                "M6.0a contains no Gadgetbridge transport " +
                                "and no alarm provider."
                        textSize = 16f
                        setPadding(0, dp(12), 0, dp(20))
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )

                addView(
                    TextView(context).apply {
                        text = buildInfo.diagnosticText()
                        textSize = 14f
                        setTextIsSelectable(true)
                        typeface = Typeface.MONOSPACE
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

        setContentView(
            ScrollView(this).apply {
                addView(body)
            },
        )
    }

    private fun dp(
        value: Int,
    ): Int =
        (value * resources.displayMetrics.density)
            .toInt()
}
