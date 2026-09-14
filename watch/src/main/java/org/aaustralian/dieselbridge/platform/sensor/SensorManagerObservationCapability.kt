// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapability
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationOptions
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationUpdate

/**
 * Long-lived SensorManager-backed observation capability.
 *
 * Public callers select only a logical sensor id. Route choice, physical
 * cadence limits and callback buffering remain provider implementation detail.
 */
internal class SensorManagerObservationCapability(
    context: Context,
    private val logicalIdValue: String,
    private val source: AndroidSensorManagerSource,
    private val callbackHandler: Handler,
) : SensorObservationCapability {

    private val sensorManager =
        context.applicationContext
            .getSystemService(
                SensorManager::class.java,
            )

    override val capabilityId =
        SensorObservationCapabilityId
            .forLogical(
                logicalIdValue,
            )

    override fun observe(
        options: SensorObservationOptions,
    ): Flow<SensorObservationUpdate> =
        callbackFlow {
            val manager =
                sensorManager

            if (
                manager == null
            ) {
                send(
                    SensorObservationUpdate
                        .Unavailable(
                            reason =
                                REASON_SENSOR_MANAGER_UNAVAILABLE,
                        ),
                )
                close()
                return@callbackFlow
            }

            val requestedPeriodUs =
                sensorManagerRequestedPeriodUs(
                    options
                        .preferredSamplePeriodMs,
                )

            val handle =
                SensorManagerLogicalRouteSelector
                    .selectForObservation(
                        handles =
                            source.snapshot(),
                        logicalId =
                            logicalIdValue,
                        requestedPeriodUs =
                            requestedPeriodUs,
                    )

            if (
                handle == null
            ) {
                send(
                    SensorObservationUpdate
                        .Unavailable(
                            reason =
                                REASON_SENSOR_UNAVAILABLE,
                        ),
                )
                close()
                return@callbackFlow
            }

            if (
                handle.sensor.reportingMode ==
                Sensor.REPORTING_MODE_ONE_SHOT
            ) {
                send(
                    SensorObservationUpdate
                        .Unavailable(
                            reason =
                                REASON_ONE_SHOT_NOT_STREAMABLE,
                        ),
                )
                close()
                return@callbackFlow
            }

            val samplingPlan =
                sensorManagerObservationSamplingPlan(
                    preferredSamplePeriodMs =
                        options
                            .preferredSamplePeriodMs,
                    minDelayUs =
                        handle.route
                            .inventory
                            .minDelayUs,
                )

            val ingress =
                SensorManagerObservationIngress<
                    SensorReading
                >(
                    capacity =
                        SOURCE_BUFFER_CAPACITY,
                )

            /*
             * The signal channel is conflated. SensorManager callbacks never
             * block waiting for coroutine processing and cannot accumulate an
             * unbounded number of wake-up tokens.
             */
            val wakeSignal =
                Channel<Unit>(
                    Channel.CONFLATED,
                )

            val listener =
                object :
                    SensorEventListener {

                    override fun onSensorChanged(
                        event: SensorEvent,
                    ) {
                        ingress.offer(
                            SensorReading(
                                capabilityId =
                                    SensorCapabilityId(
                                        "sensor.$logicalIdValue",
                                    ),
                                providerId =
                                    SensorManagerRouteCatalog
                                        .PROVIDER_ID,
                                values =
                                    event.values
                                        .toList(),
                                timestampNanos =
                                    event.timestamp,
                                accuracy =
                                    event.accuracy,
                                elapsedMs =
                                    0L,
                            ),
                        )

                        wakeSignal
                            .trySend(
                                Unit,
                            )
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {
                        // Accuracy is carried by each SensorEvent.
                    }
                }

            val registered =
                try {
                    manager.registerListener(
                        listener,
                        handle.sensor,
                        samplingPlan
                            .registrationPeriodUs,
                        callbackHandler,
                    )
                } catch (
                    error: SecurityException,
                ) {
                    send(
                        SensorObservationUpdate
                            .PermissionDenied(
                                requiredPermission =
                                    null,
                            ),
                    )
                    close()
                    return@callbackFlow
                } catch (
                    error: IllegalArgumentException,
                ) {
                    send(
                        SensorObservationUpdate
                            .RegistrationRejected(
                                reason =
                                    REASON_INVALID_REGISTRATION,
                            ),
                    )
                    close()
                    return@callbackFlow
                }

            if (
                !registered
            ) {
                send(
                    SensorObservationUpdate
                        .RegistrationRejected(
                            reason =
                                REASON_REGISTRATION_REJECTED,
                        ),
                )
                close()
                return@callbackFlow
            }

            /*
             * SensorManager's period is a configured request. It is not an
             * observed guarantee, so effectiveSamplePeriodMs remains unknown.
             */
            send(
                SensorObservationUpdate
                    .Started(
                        effectiveSamplePeriodMs =
                            null,
                        configuredSamplePeriodMs =
                            samplingPlan
                                .configuredPeriodMs,
                    ),
            )

            var lastReportedSourceDrops =
                0L

            val drainJob =
                launch {
                    /*
                     * A callback may race registration completion and queue a
                     * sample before this coroutine starts.
                     */
                    wakeSignal
                        .trySend(
                            Unit,
                        )

                    for (
                        ignored in
                        wakeSignal
                    ) {
                        while (true) {
                            /*
                             * Drop telemetry is emitted independently from
                             * samples. No later successfully-delivered sensor
                             * event is required to expose an overload.
                             */
                            val currentDrops =
                                ingress
                                    .sourceDroppedTotal

                            if (
                                currentDrops !=
                                lastReportedSourceDrops
                            ) {
                                send(
                                    SensorObservationUpdate
                                        .SourceDrops(
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
                                    SensorObservationUpdate
                                        .SourceDrops(
                                            pending
                                                .sourceDroppedTotal,
                                        ),
                                )

                                lastReportedSourceDrops =
                                    pending
                                        .sourceDroppedTotal
                            }

                            send(
                                SensorObservationUpdate
                                    .Sample(
                                        reading =
                                            pending.value,
                                        sourceDroppedTotal =
                                            pending
                                                .sourceDroppedTotal,
                                    ),
                            )
                        }
                    }
                }

            awaitClose {
                runCatching {
                    manager.unregisterListener(
                        listener,
                        handle.sensor,
                    )
                }

                wakeSignal.close()
                drainJob.cancel()
            }
        }
            /*
             * The explicit SensorManagerObservationIngress is the only sample
             * backlog. Do not add a second hidden Flow buffer here.
             */
            .buffer(
                capacity =
                    0,
            )

    private companion object {
        const val REASON_SENSOR_MANAGER_UNAVAILABLE =
            "sensor_manager_unavailable"

        const val REASON_SENSOR_UNAVAILABLE =
            "sensor_unavailable"

        const val REASON_ONE_SHOT_NOT_STREAMABLE =
            "one_shot_sensor_not_streamable"

        const val REASON_REGISTRATION_REJECTED =
            "sensor_registration_rejected"

        const val REASON_INVALID_REGISTRATION =
            "sensor_registration_invalid_argument"

        const val SOURCE_BUFFER_CAPACITY =
            64
    }
}

internal fun sensorManagerObservationCapabilities(
    context: Context,
    source: AndroidSensorManagerSource,
    callbackHandler: Handler,
): List<SensorObservationCapability> =
    SensorCapabilityCatalog
        .standardLogicalIds
        .map {
            logicalId,
        ->
            SensorManagerObservationCapability(
                context =
                    context,
                logicalIdValue =
                    logicalId,
                source =
                    source,
                callbackHandler =
                    callbackHandler,
            )
        }
