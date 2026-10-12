// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.reference

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.aaustralian.dieselbridge.ipc.IDieselObservationCallback

/**
 * Deliberate process-death harness. It runs in :death so killing itself
 * destroys the remote Binder client token/provider callbacks while the
 * launcher activity can remain alive in the main reference process.
 */
class ReferenceDeathService :
    Service() {

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO,
        )

    private var activeJob:
        Job? =
        null

    private var client:
        DieselReferenceClient? =
        null

    private var provider:
        DieselReferenceProviderHarness? =
        null

    override fun onBind(
        intent: Intent?,
    ): IBinder? =
        null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val mode =
            intent?.getStringExtra(
                ReferenceActivity.EXTRA_MODE,
            )
                ?: return START_NOT_STICKY

        activeJob?.cancel()

        activeJob =
            scope.launch {
                try {
                    when (mode) {
                        MODE_SESSION_DEATH ->
                            holdConsumerSessionThenDie()

                        MODE_PROVIDER_DEATH ->
                            holdProvidersThenDie()

                        else ->
                            error(
                                "unknown_death_mode:$mode",
                            )
                    }
                } catch (
                    error:
                        Throwable,
                ) {
                    Log.e(
                        TAG,
                        "M77_DEATH FAIL mode=$mode " +
                            (
                                error.message
                                    ?: error::class.java.simpleName
                            ),
                        error,
                    )

                    stopSelf(
                        startId,
                    )
                }
            }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeJob?.cancel()

        runCatching {
            provider?.close()
        }
        provider =
            null

        runCatching {
            client?.close()
        }
        client =
            null

        scope.cancel()
        super.onDestroy()
    }

    private suspend fun holdConsumerSessionThenDie() {
        val stableClient =
            DieselReferenceClient.connect(
                this,
            )

        client =
            stableClient

        val opened =
            stableClient.session
                .subscribeObservation(
                    "sensor.observe.accelerometer",
                    100L,
                    object :
                        IDieselObservationCallback.Stub() {
                        override fun onUpdate(
                            update:
                                android.os.Bundle,
                        ) = Unit
                    },
                )

        require(
            opened.getString(
                "status",
            ) ==
            "ok",
        ) {
            "session_death_observation_open:" +
                opened.getString(
                    "status",
                )
        }

        Log.i(
            TAG,
            "M77_SESSION_DEATH READY subscriptionId=" +
                opened.getLong(
                    "subscriptionId",
                ),
        )

        deliberateDeath(
            "M77_SESSION_DEATH",
        )
    }

    private suspend fun holdProvidersThenDie() {
        val stableClient =
            DieselReferenceClient.connect(
                this,
            )

        client =
            stableClient

        val stableProvider =
            DieselReferenceProviderHarness(
                stableClient,
            )

        provider =
            stableProvider

        val ids =
            stableProvider
                .registerForHold()

        Log.i(
            TAG,
            "M77_PROVIDER_DEATH READY resources=" +
                ids.joinToString(
                    ",",
                ),
        )

        deliberateDeath(
            "M77_PROVIDER_DEATH",
        )
    }

    private suspend fun deliberateDeath(
        marker: String,
    ): Nothing {
        delay(
            DEATH_DELAY_MS,
        )

        Log.w(
            TAG,
            "$marker KILL pid=${Process.myPid()}",
        )

        /*
         * No graceful close on purpose. Binder death must be the only
         * cleanup signal observed by DieselBridge.
         */
        Process.killProcess(
            Process.myPid(),
        )

        error(
            "process_kill_returned",
        )
    }

    companion object {
        private const val TAG =
            "DieselM77Ref"

        private const val DEATH_DELAY_MS =
            1_500L

        const val MODE_SESSION_DEATH =
            "session_death"

        const val MODE_PROVIDER_DEATH =
            "provider_death"
    }
}
