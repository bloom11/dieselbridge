// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.reference

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.aaustralian.dieselbridge.ipc.IDieselActionCallback
import org.aaustralian.dieselbridge.ipc.IDieselClientToken
import org.aaustralian.dieselbridge.ipc.IDieselObservationCallback
import org.aaustralian.dieselbridge.ipc.IDieselPublicService
import org.aaustralian.dieselbridge.ipc.IDieselSession

internal data class ConsumerProofResult(
    val catalogSize: Int,
    val alarmPresent: Boolean,
    val vibrationStatus: String,
    val observationSubscriptionId: Long,
    val sampleValueCount: Int,
)

internal class DieselReferenceClient private constructor(
    private val context: Context,
    private val connection: ServiceConnection,
    private val clientToken: IDieselClientToken,
    val session: IDieselSession,
) : Closeable {

    suspend fun runConsumerProof():
        ConsumerProofResult {
        val catalog =
            session.catalogIds()

        require(
            "companion.alarm.next" in catalog,
        ) {
            "catalog_missing_alarm"
        }

        require(
            "watch.vibration.buzz" in catalog,
        ) {
            "catalog_missing_vibration"
        }

        require(
            "sensor.observe.accelerometer" in catalog,
        ) {
            "catalog_missing_accelerometer"
        }

        val alarm =
            session.getState(
                "companion.alarm.next",
            )

        require(
            alarm.getString(
                "status",
            ) ==
            STATUS_OK,
        ) {
            "alarm_state_failed:" +
                alarm.getString(
                    "status",
                )
        }

        val actionResult =
            CompletableDeferred<Bundle>()

        session.invokeAction(
            "watch.vibration.buzz",
            objectValue(
                "durationMs" to
                    integerValue(
                        60L,
                    ),
            ),
            object :
                IDieselActionCallback.Stub() {
                override fun onResult(
                    result: Bundle,
                ) {
                    actionResult.complete(
                        result,
                    )
                }
            },
        )

        val vibration =
            withTimeout(
                ACTION_TIMEOUT_MS,
            ) {
                actionResult.await()
            }

        val vibrationStatus =
            vibration.getString(
                "status",
            )
                ?: "missing"

        require(
            vibrationStatus ==
            STATUS_OK,
        ) {
            "vibration_failed:$vibrationStatus"
        }

        val updates =
            Channel<Bundle>(
                Channel.UNLIMITED,
            )

        val callback =
            object :
                IDieselObservationCallback.Stub() {
                override fun onUpdate(
                    update: Bundle,
                ) {
                    updates.trySend(
                        update,
                    )
                }
            }

        val opened =
            session.subscribeObservation(
                "sensor.observe.accelerometer",
                100L,
                callback,
            )

        require(
            opened.getString(
                "status",
            ) ==
            STATUS_OK,
        ) {
            "observation_open_failed:" +
                opened.getString(
                    "status",
                )
        }

        val subscriptionId =
            opened.getLong(
                "subscriptionId",
            )

        val sampleCount =
            try {
                withTimeout(
                    OBSERVATION_TIMEOUT_MS,
                ) {
                    while (true) {
                        val update =
                            updates.receive()

                        if (
                            update.getString(
                                "kind",
                            ) ==
                            "observation_closed"
                        ) {
                            error(
                                "observation_closed:" +
                                    update.getString(
                                        "reason",
                                    ),
                            )
                        }

                        @Suppress("DEPRECATION")
                        val samples =
                            update
                                .getParcelableArrayList<Bundle>(
                                    "samples",
                                )

                        val first =
                            samples
                                ?.firstOrNull()

                        if (first != null) {
                            return@withTimeout first
                                .getDoubleArray(
                                    "values",
                                )
                                ?.size
                                ?: 0
                        }
                    }

                    error(
                        "unreachable",
                    )
                }
            } finally {
                session.unsubscribeObservation(
                    subscriptionId,
                )
                updates.close()
            }

        require(sampleCount > 0) {
            "accelerometer_sample_empty"
        }

        return ConsumerProofResult(
            catalogSize =
                catalog.size,
            alarmPresent =
                alarm.getBoolean(
                    "present",
                    false,
                ),
            vibrationStatus =
                vibrationStatus,
            observationSubscriptionId =
                subscriptionId,
            sampleValueCount =
                sampleCount,
        )
    }

    override fun close() {
        runCatching {
            session.close()
        }

        runCatching {
            context.unbindService(
                connection,
            )
        }

        @Suppress("UNUSED_VARIABLE")
        val keepTokenAlive =
            clientToken
    }

    companion object {
        private const val TAG =
            "DieselM77Ref"

        private const val STATUS_OK =
            "ok"

        private const val ACTION_TIMEOUT_MS =
            7_000L

        private const val OBSERVATION_TIMEOUT_MS =
            8_000L

        private const val DIESEL_PACKAGE =
            "io.github.bloom11.dieselbridge"

        private const val DIESEL_SERVICE =
            "org.aaustralian.dieselbridge.ipc.DieselPublicApiService"

        suspend fun connect(
            context: Context,
        ): DieselReferenceClient {
            val appContext =
                context.applicationContext

            val connected =
                CompletableDeferred<
                    IDieselPublicService
                >()

            val connection =
                object :
                    ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        service: IBinder,
                    ) {
                        connected.complete(
                            IDieselPublicService.Stub
                                .asInterface(
                                    service,
                                ),
                        )
                    }

                    override fun onServiceDisconnected(
                        name: ComponentName,
                    ) {
                        if (
                            !connected.isCompleted
                        ) {
                            connected.completeExceptionally(
                                IllegalStateException(
                                    "diesel_service_disconnected",
                                ),
                            )
                        }
                    }

                    override fun onNullBinding(
                        name: ComponentName,
                    ) {
                        if (
                            !connected.isCompleted
                        ) {
                            connected.completeExceptionally(
                                IllegalStateException(
                                    "diesel_service_null_binding",
                                ),
                            )
                        }
                    }
                }

            val intent =
                Intent().apply {
                    component =
                        ComponentName(
                            DIESEL_PACKAGE,
                            DIESEL_SERVICE,
                        )
                }

            val bound =
                appContext.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE,
                )

            check(bound) {
                "diesel_public_service_bind_failed"
            }

            val service =
                try {
                    withTimeout(
                        5_000L,
                    ) {
                        connected.await()
                    }
                } catch (error: Exception) {
                    runCatching {
                        appContext.unbindService(
                            connection,
                        )
                    }
                    throw error
                }

            val info =
                service.serviceInfo

            check(
                info.getBoolean(
                    "runtimeAvailable",
                    false,
                ),
            ) {
                "diesel_runtime_unavailable"
            }

            check(
                info.getInt(
                    "apiMajor",
                ) ==
                1
            ) {
                "unsupported_api_major"
            }

            val token =
                object :
                    IDieselClientToken.Stub() {}

            val opened =
                service.openSession(
                    1,
                    1,
                    token,
                )

            check(
                opened.getString(
                    "status",
                ) ==
                STATUS_OK,
            ) {
                "session_open_failed:" +
                    opened.getString(
                        "status",
                    )
            }

            val binder =
                opened.getBinder(
                    "session",
                )
                    ?: error(
                        "session_binder_missing",
                    )

            val session =
                IDieselSession.Stub
                    .asInterface(
                        binder,
                    )

            Log.i(
                TAG,
                "M77_SESSION_OPEN api=" +
                    opened.getInt(
                        "apiMajor",
                    ) +
                    "." +
                    opened.getInt(
                        "apiMinor",
                    ),
            )

            return DieselReferenceClient(
                context =
                    appContext,
                connection =
                    connection,
                clientToken =
                    token,
                session =
                    session,
            )
        }

        private fun IDieselSession.catalogIds():
            Set<String> {
            val snapshot =
                catalog

            @Suppress("DEPRECATION")
            val resources =
                snapshot
                    .getParcelableArrayList<Bundle>(
                        "resources",
                    )
                    .orEmpty()

            return resources
                .mapNotNull {
                    it.getString(
                        "id",
                    )
                }
                .toSet()
        }

        private fun integerValue(
            value: Long,
        ): Bundle =
            Bundle().apply {
                putString(
                    "type",
                    "integer",
                )
                putLong(
                    "integer",
                    value,
                )
            }

        private fun objectValue(
            vararg fields:
                Pair<String, Bundle>,
        ): Bundle =
            Bundle().apply {
                putString(
                    "type",
                    "object",
                )
                putBundle(
                    "object",
                    Bundle().apply {
                        fields.forEach {
                            (
                                key,
                                value,
                            ) ->
                            putBundle(
                                key,
                                value,
                            )
                        }
                    },
                )
            }
    }
}
