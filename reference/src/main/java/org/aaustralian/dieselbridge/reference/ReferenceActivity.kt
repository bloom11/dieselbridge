// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.reference

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReferenceActivity :
    Activity() {

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main,
        )

    private lateinit var output:
        TextView

    private var activeJob:
        Job? =
        null

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(
            savedInstanceState,
        )

        output =
            TextView(
                this,
            ).apply {
                text =
                    "M7.7 reference external APK\n" +
                    "Start the DieselBridge runtime first.\n"

                setTextIsSelectable(
                    true,
                )
            }

        val layout =
            LinearLayout(
                this,
            ).apply {
                orientation =
                    LinearLayout.VERTICAL

                addView(
                    button(
                        "Run consumer proof",
                        ::runConsumerProof,
                    ),
                    matchWrap(),
                )

                addView(
                    button(
                        "Run provider proof",
                        ::runProviderProof,
                    ),
                    matchWrap(),
                )

                addView(
                    button(
                        "Kill active session process",
                    ) {
                        startDeathMode(
                            ReferenceDeathService
                                .MODE_SESSION_DEATH,
                        )
                    },
                    matchWrap(),
                )

                addView(
                    button(
                        "Kill provider process",
                    ) {
                        startDeathMode(
                            ReferenceDeathService
                                .MODE_PROVIDER_DEATH,
                        )
                    },
                    matchWrap(),
                )

                addView(
                    output,
                    matchWrap(),
                )
            }

        setContentView(
            ScrollView(
                this,
            ).apply {
                addView(
                    layout,
                )
            },
        )

        when (
            intent.getStringExtra(
                EXTRA_MODE,
            )
        ) {
            MODE_CONSUMER ->
                runConsumerProof()

            MODE_PROVIDER ->
                runProviderProof()

            ReferenceDeathService
                .MODE_SESSION_DEATH ->
                startDeathMode(
                    ReferenceDeathService
                        .MODE_SESSION_DEATH,
                )

            ReferenceDeathService
                .MODE_PROVIDER_DEATH ->
                startDeathMode(
                    ReferenceDeathService
                        .MODE_PROVIDER_DEATH,
                )
        }
    }

    override fun onDestroy() {
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun runConsumerProof() {
        activeJob?.cancel()

        output.text =
            "M77_CONSUMER START\n"

        activeJob =
            scope.launch {
                try {
                    val result =
                        withContext(
                            Dispatchers.IO,
                        ) {
                            DieselReferenceClient
                                .connect(
                                    this@ReferenceActivity,
                                )
                                .use {
                                    client ->
                                    client
                                        .runConsumerProof()
                                }
                        }

                    val message =
                        buildString {
                            appendLine(
                                "M77_CONSUMER PASS",
                            )
                            appendLine(
                                "catalogSize=${result.catalogSize}",
                            )
                            appendLine(
                                "alarmPresent=${result.alarmPresent}",
                            )
                            appendLine(
                                "vibrationStatus=${result.vibrationStatus}",
                            )
                            appendLine(
                                "observationSubscriptionId=" +
                                    result.observationSubscriptionId,
                            )
                            appendLine(
                                "accelerometerValues=" +
                                    result.sampleValueCount,
                            )
                        }

                    reportPass(
                        message,
                    )
                } catch (
                    error:
                        Throwable,
                ) {
                    reportFailure(
                        "M77_CONSUMER",
                        error,
                    )
                }
            }
    }

    private fun runProviderProof() {
        activeJob?.cancel()

        output.text =
            "M77_PROVIDER START\n"

        activeJob =
            scope.launch {
                try {
                    val result =
                        withContext(
                            Dispatchers.IO,
                        ) {
                            DieselReferenceClient
                                .connect(
                                    this@ReferenceActivity,
                                )
                                .use {
                                    client ->
                                    DieselReferenceProviderHarness(
                                        client,
                                    ).use {
                                        provider ->
                                        provider
                                            .runProof()
                                    }
                                }
                        }

                    val message =
                        buildString {
                            appendLine(
                                "M77_PROVIDER PASS",
                            )
                            appendLine(
                                "stateRegistrationId=" +
                                    result.stateRegistrationId,
                            )
                            appendLine(
                                "actionRegistrationId=" +
                                    result.actionRegistrationId,
                            )
                            appendLine(
                                "eventRegistrationId=" +
                                    result.eventRegistrationId,
                            )
                            appendLine(
                                "observationRegistrationId=" +
                                    result.observationRegistrationId,
                            )
                            appendLine(
                                "stateValue=${result.stateValue}",
                            )
                            appendLine(
                                "actionValue=${result.actionValue}",
                            )
                            appendLine(
                                "eventObserved=${result.eventObserved}",
                            )
                            appendLine(
                                "observationValues=" +
                                    result.observationValueCount,
                            )
                        }

                    reportPass(
                        message,
                    )
                } catch (
                    error:
                        Throwable,
                ) {
                    reportFailure(
                        "M77_PROVIDER",
                        error,
                    )
                }
            }
    }

    private fun startDeathMode(
        mode: String,
    ) {
        val intent =
            Intent(
                this,
                ReferenceDeathService::class.java,
            ).apply {
                putExtra(
                    EXTRA_MODE,
                    mode,
                )
            }

        startService(
            intent,
        )

        val message =
            "M77_DEATH START mode=$mode\n" +
                "Watch logcat tag DieselM77Ref for READY/KILL."

        output.text =
            message

        Log.i(
            TAG,
            message.replace(
                '\n',
                ' ',
            ),
        )
    }

    private fun button(
        label: String,
        action: () -> Unit,
    ): Button =
        Button(
            this,
        ).apply {
            text =
                label

            setOnClickListener {
                action()
            }
        }

    private fun matchWrap():
        ViewGroup.LayoutParams =
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    private fun reportPass(
        message: String,
    ) {
        output.text =
            message

        Log.i(
            TAG,
            message.replace(
                '\n',
                ' ',
            ),
        )
    }

    private fun reportFailure(
        marker: String,
        error: Throwable,
    ) {
        val message =
            "$marker FAIL " +
                (
                    error.message
                        ?: error::class.java.simpleName
                )

        output.text =
            message

        Log.e(
            TAG,
            message,
            error,
        )
    }

    companion object {
        private const val TAG =
            "DieselM77Ref"

        const val EXTRA_MODE =
            "mode"

        const val MODE_CONSUMER =
            "consumer"

        const val MODE_PROVIDER =
            "provider"
    }
}
