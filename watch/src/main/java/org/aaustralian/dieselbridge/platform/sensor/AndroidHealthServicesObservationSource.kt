// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerCallback
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.google.common.util.concurrent.ListenableFuture
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

internal data class HealthServicesObservationPendingSample(
    val sample: HealthServicesSample,
    val sourceDroppedTotal: Long,
)

/**
 * Explicit bounded ingress between Health Services callbacks and coroutines.
 *
 * Callbacks never block. Oldest samples are dropped under overload and the
 * monotonic provider-side loss counter is attached to every dequeued sample.
 */
internal class HealthServicesObservationIngress(
    private val capacity: Int,
) {
    init {
        require(capacity > 0)
    }

    private val lock =
        Any()

    private val queue =
        ArrayDeque<
            HealthServicesSample
        >()

    private var dropped =
        0L

    val sourceDroppedTotal: Long
        get() =
            synchronized(lock) {
                dropped
            }

    fun offer(
        sample: HealthServicesSample,
    ) {
        synchronized(lock) {
            if (
                queue.size >=
                    capacity
            ) {
                queue.removeFirst()

                if (
                    dropped <
                    Long.MAX_VALUE
                ) {
                    dropped++
                }
            }

            queue.addLast(
                sample,
            )
        }
    }

    fun poll():
        HealthServicesObservationPendingSample? =
        synchronized(lock) {
            val sample =
                queue.pollFirst()
                    ?: return@synchronized null

            HealthServicesObservationPendingSample(
                sample =
                    sample,
                sourceDroppedTotal =
                    dropped,
            )
        }
}

/**
 * Android Health Services passive-monitoring adapter.
 *
 * Passive monitoring owns the provider acquisition cadence. Diesel never
 * pretends that preferredSamplePeriodMs configures this source.
 *
 * The optional provider-health callbacks are intentionally process-local.
 * The service wiring phase uses them to mark the priority-20 Health Services
 * observation binding unavailable so CapabilityRegistry can select the
 * SensorManager fallback.
 */
internal class AndroidHealthServicesObservationSource(
    context: Context,
    private val onPermissionLost:
        (logicalId: String) -> Unit = {},
    private val onRegistrationFailure:
        (
            logicalId: String,
            error: Throwable,
        ) -> Unit =
        { _, _ -> },
) : HealthServicesObservationSource {

    private val appContext =
        context.applicationContext

    private val passiveClient:
        PassiveMonitoringClient by
        lazy {
            HealthServices
                .getClient(
                    appContext,
                )
                .passiveMonitoringClient
        }

    override suspend fun supports(
        logicalId: String,
    ): Boolean {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.R
        ) {
            return false
        }

        val type =
            dataType(
                logicalId,
            )
                ?: return false

        return passiveClient
            .getCapabilitiesAsync()
            .awaitFuture()
            .supportedDataTypesPassiveMonitoring
            .contains(
                type,
            )
    }

    override fun hasRequiredPermission(
        logicalId: String,
    ): Boolean =
        logicalId ==
            LOGICAL_HEART_RATE &&
            appContext
                .checkSelfPermission(
                    Manifest.permission.BODY_SENSORS,
                ) ==
            PackageManager.PERMISSION_GRANTED

    override fun observe(
        logicalId: String,
    ): Flow<
        HealthServicesObservationSourceUpdate
    > =
        flow {
            if (
                Build.VERSION.SDK_INT <
                Build.VERSION_CODES.R
            ) {
                throw UnsupportedOperationException(
                    "Health Services requires API 30",
                )
            }

            val type =
                dataType(
                    logicalId,
                )
                    ?: throw UnsupportedOperationException(
                        "Unsupported passive Health Services sensor: $logicalId",
                    )

            val config =
                PassiveListenerConfig
                    .Builder()
                    .setDataTypes(
                        setOf(
                            type,
                        ),
                    )
                    .build()

            val registrationAttempted =
                AtomicBoolean(
                    false,
                )

            try {
                callbackFlow {
                    val ingress =
                        HealthServicesObservationIngress(
                            capacity =
                                SOURCE_BUFFER_CAPACITY,
                        )

                    /*
                     * The signal is conflated: callback threads never block and
                     * cannot build an unbounded queue of wake-up tokens.
                     */
                    val wakeSignal =
                        Channel<Unit>(
                            Channel.CONFLATED,
                        )

                    val registered =
                        AtomicBoolean(
                            false,
                        )

                    val permissionLost =
                        AtomicBoolean(
                            false,
                        )

                    val registrationFailure =
                        AtomicReference<
                            Throwable?
                        >(
                            null,
                        )

                    var registeredEmitted =
                        false

                    var lastReportedSourceDrops =
                        0L

                    val drainJob =
                        launch {
                            /*
                             * Cover a callback that races registration and
                             * updates state before this coroutine starts.
                             */
                            wakeSignal
                                .trySend(
                                    Unit,
                                )

                            for (
                                ignored in
                                wakeSignal
                            ) {
                                val failure =
                                    registrationFailure
                                        .getAndSet(
                                            null,
                                        )

                                if (
                                    failure !=
                                    null
                                ) {
                                    runCatching {
                                        onRegistrationFailure(
                                            logicalId,
                                            failure,
                                        )
                                    }

                                    close(
                                        HealthServicesObservationRegistrationException(
                                            message =
                                                failure.message,
                                            cause =
                                                failure,
                                        ),
                                    )

                                    break
                                }

                                if (
                                    registered.get() &&
                                    !registeredEmitted
                                ) {
                                    send(
                                        HealthServicesObservationSourceUpdate
                                            .Registered,
                                    )

                                    registeredEmitted =
                                        true
                                }

                                while (true) {
                                    val currentDrops =
                                        ingress
                                            .sourceDroppedTotal

                                    if (
                                        currentDrops !=
                                        lastReportedSourceDrops
                                    ) {
                                        send(
                                            HealthServicesObservationSourceUpdate
                                                .SourceDrops(
                                                    sourceDroppedTotal =
                                                        currentDrops,
                                                ),
                                        )

                                        lastReportedSourceDrops =
                                            currentDrops
                                    }

                                    val pending =
                                        ingress.poll()
                                            ?: break

                                    if (
                                        pending
                                            .sourceDroppedTotal !=
                                        lastReportedSourceDrops
                                    ) {
                                        send(
                                            HealthServicesObservationSourceUpdate
                                                .SourceDrops(
                                                    sourceDroppedTotal =
                                                        pending
                                                            .sourceDroppedTotal,
                                                ),
                                        )

                                        lastReportedSourceDrops =
                                            pending
                                                .sourceDroppedTotal
                                    }

                                    send(
                                        HealthServicesObservationSourceUpdate
                                            .Sample(
                                                sample =
                                                    pending
                                                        .sample,
                                                sourceDroppedTotal =
                                                    pending
                                                        .sourceDroppedTotal,
                                            ),
                                    )
                                }

                                if (
                                    permissionLost
                                        .getAndSet(
                                            false,
                                        )
                                ) {
                                    runCatching {
                                        onPermissionLost(
                                            logicalId,
                                        )
                                    }

                                    send(
                                        HealthServicesObservationSourceUpdate
                                            .PermissionLost(
                                                requiredPermission =
                                                    HealthServicesProvider
                                                        .requiredPermission(
                                                            logicalId,
                                                        ),
                                            ),
                                    )

                                    close()
                                    break
                                }
                            }
                        }

                    val callback =
                        object :
                            PassiveListenerCallback {

                            override fun onRegistered() {
                                registered.set(
                                    true,
                                )

                                wakeSignal
                                    .trySend(
                                        Unit,
                                    )
                            }

                            override fun onRegistrationFailed(
                                throwable: Throwable,
                            ) {
                                registrationFailure
                                    .compareAndSet(
                                        null,
                                        throwable,
                                    )

                                wakeSignal
                                    .trySend(
                                        Unit,
                                    )
                            }

                            override fun onPermissionLost() {
                                permissionLost.set(
                                    true,
                                )

                                wakeSignal
                                    .trySend(
                                        Unit,
                                    )
                            }

                            override fun onNewDataPointsReceived(
                                dataPoints: DataPointContainer,
                            ) {
                                val samples =
                                    extractAll(
                                        logicalId =
                                            logicalId,
                                        data =
                                            dataPoints,
                                    )

                                if (
                                    samples.isEmpty()
                                ) {
                                    return
                                }

                                /*
                                 * Data delivery is also proof that Health
                                 * Services accepted the callback, even if a
                                 * vendor omitted onRegistered().
                                 */
                                registered.set(
                                    true,
                                )

                                samples.forEach(
                                    ingress::offer,
                                )

                                wakeSignal
                                    .trySend(
                                        Unit,
                                    )
                            }
                        }

                    try {
                        registrationAttempted
                            .set(
                                true,
                            )

                        passiveClient
                            .setPassiveListenerCallback(
                                config,
                                DirectExecutor,
                                callback,
                            )
                    } catch (
                        error: Throwable,
                    ) {
                        registrationFailure
                            .compareAndSet(
                                null,
                                error,
                            )

                        wakeSignal
                            .trySend(
                                Unit,
                            )
                    }

                    awaitClose {
                        wakeSignal.close()
                        drainJob.cancel()
                    }
                }
                    /*
                     * HealthServicesObservationIngress is the only sample
                     * backlog. Do not add a second hidden Flow sample buffer.
                     */
                    .buffer(
                        capacity =
                            0,
                    )
                    .collect {
                        emit(
                            it,
                        )
                    }
            } finally {
                /*
                 * Passive callback registration is app-global and persists
                 * until explicitly cleared. Complete bounded cleanup even when
                 * the collecting coroutine was cancelled.
                 */
                if (
                    registrationAttempted.get()
                ) {
                    withContext(
                        NonCancellable,
                    ) {
                        runCatching {
                            withTimeout(
                                CLEAR_TIMEOUT_MS,
                            ) {
                                passiveClient
                                    .clearPassiveListenerCallbackAsync()
                                    .awaitFuture()
                            }
                        }
                    }
                }
            }
        }

    private fun dataType(
        logicalId: String,
    ) =
        when (
            logicalId
        ) {
            LOGICAL_HEART_RATE ->
                DataType
                    .HEART_RATE_BPM

            else ->
                null
        }

    private fun extractAll(
        logicalId: String,
        data: DataPointContainer,
    ): List<
        HealthServicesSample
    > =
        when (
            logicalId
        ) {
            LOGICAL_HEART_RATE ->
                data
                    .getData(
                        DataType
                            .HEART_RATE_BPM,
                    )
                    .map { point ->
                        HealthServicesSample(
                            values =
                                listOf(
                                    point
                                        .value
                                        .toFloat(),
                                ),
                            timestampNanos =
                                point
                                    .timeDurationFromBoot
                                    .toNanos(),
                        )
                    }

            else ->
                emptyList()
        }

    private suspend fun <T> ListenableFuture<T>
        .awaitFuture(): T =
        suspendCancellableCoroutine {
                continuation,
            ->
            addListener(
                {
                    if (
                        continuation
                            .isActive
                    ) {
                        runCatching {
                            get()
                        }
                            .fold(
                                continuation::resume,
                            ) {
                                    error,
                                ->
                                continuation
                                    .resumeWith(
                                        Result.failure(
                                            error,
                                        ),
                                    )
                            }
                    }
                },
                DirectExecutor,
            )

            continuation
                .invokeOnCancellation {
                    cancel(
                        true,
                    )
                }
        }

    private object DirectExecutor :
        Executor {

        override fun execute(
            command: Runnable,
        ) {
            command.run()
        }
    }

    private companion object {
        const val LOGICAL_HEART_RATE =
            "heart_rate"

        const val SOURCE_BUFFER_CAPACITY =
            64

        const val CLEAR_TIMEOUT_MS =
            5_000L
    }
}
