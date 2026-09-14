// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapability
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationOptions
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationUpdate

/**
 * Long-lived SensorManager-backed observation capability.
 *
 * Exact Android routes remain implementation detail. The capability accepts a
 * canonical logical sensor id and applies the same deterministic route
 * preference used by bounded logical reads.
 */
internal class SensorManagerObservationCapability(
    context: Context,
    private val logicalIdValue: String,
    private val source: AndroidSensorManagerSource,
    private val callbackHandler: Handler =
        Handler(
            Looper.getMainLooper(),
        ),
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

            if (manager == null) {
                trySend(
                    SensorObservationUpdate
                        .Unavailable(
                            reason =
                                REASON_SENSOR_MANAGER_UNAVAILABLE,
                        ),
                )
                close()
                return@callbackFlow
            }

            val handle =
                SensorManagerLogicalRouteSelector
                    .select(
                        handles =
                            source.snapshot(),
                        logicalId =
                            logicalIdValue,
                    )

            if (handle == null) {
                trySend(
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
                trySend(
                    SensorObservationUpdate
                        .Unavailable(
                            reason =
                                REASON_ONE_SHOT_NOT_STREAMABLE,
                        ),
                )
                close()
                return@callbackFlow
            }

            val listener =
                object :
                    SensorEventListener {

                    override fun onSensorChanged(
                        event: SensorEvent,
                    ) {
                        val admitted =
                            trySend(
                                SensorObservationUpdate
                                    .Sample(
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
                                    ),
                            )

                        if (
                            admitted.isFailure &&
                            !admitted.isClosed
                        ) {
                            close(
                                SensorObservationIngressOverflowException(),
                            )
                        }
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {
                        // Accuracy is carried by each SensorEvent.
                    }
                }

            val samplePeriodUs =
                options
                    .preferredSamplePeriodMs
                    .coerceAtMost(
                        MAX_PERIOD_MS_FOR_INT_US,
                    )
                    .times(
                        1_000L,
                    )
                    .toInt()

            val registered =
                try {
                    manager.registerListener(
                        listener,
                        handle.sensor,
                        samplePeriodUs,
                        callbackHandler,
                    )
                } catch (
                    error: SecurityException,
                ) {
                    trySend(
                        SensorObservationUpdate
                            .PermissionDenied(
                                requiredPermission =
                                    null,
                            ),
                    )
                    close()
                    return@callbackFlow
                }

            if (!registered) {
                trySend(
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
             * SensorManager samplingPeriodUs is a request, not an observed
             * guarantee. Leave provider effective cadence unknown until it is
             * actually measured or reported by a provider.
             */
            trySend(
                SensorObservationUpdate
                    .Started(
                        effectiveSamplePeriodMs =
                            null,
                    ),
            )

            awaitClose {
                manager.unregisterListener(
                    listener,
                    handle.sensor,
                )
            }
        }

    private companion object {
        const val REASON_SENSOR_MANAGER_UNAVAILABLE =
            "sensor_manager_unavailable"

        const val REASON_SENSOR_UNAVAILABLE =
            "sensor_unavailable"

        const val REASON_ONE_SHOT_NOT_STREAMABLE =
            "one_shot_sensor_not_streamable"

        const val REASON_REGISTRATION_REJECTED =
            "sensor_registration_rejected"

        const val MAX_PERIOD_MS_FOR_INT_US =
            Int.MAX_VALUE /
                1_000L
    }
}

internal fun sensorManagerObservationCapabilities(
    context: Context,
    source: AndroidSensorManagerSource,
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
            )
        }


private class SensorObservationIngressOverflowException :
    IllegalStateException(
        "sensor observation provider ingress overflow",
    )
