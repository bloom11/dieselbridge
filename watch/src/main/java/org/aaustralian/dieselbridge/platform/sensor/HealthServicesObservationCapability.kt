// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapability
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationOptions
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationUpdate

/**
 * Process-local seam for Health Services passive/live heart-rate observation.
 *
 * Health Services controls acquisition cadence. Diesel's requested period is
 * therefore a consumer delivery preference, not a provider configuration
 * guarantee.
 */
interface HealthServicesObservationSource {
    suspend fun supports(
        logicalId: String,
    ): Boolean

    fun hasRequiredPermission(
        logicalId: String,
    ): Boolean

    fun observe(
        logicalId: String,
    ): Flow<HealthServicesObservationSourceUpdate>
}

sealed interface HealthServicesObservationSourceUpdate {

    data object Registered :
        HealthServicesObservationSourceUpdate

    data class SourceDrops(
        val sourceDroppedTotal: Long,
    ) : HealthServicesObservationSourceUpdate {
        init {
            require(sourceDroppedTotal >= 0L)
        }
    }

    data class Sample(
        val sample: HealthServicesSample,
        val sourceDroppedTotal: Long = 0L,
    ) : HealthServicesObservationSourceUpdate {
        init {
            require(sourceDroppedTotal >= 0L)
        }
    }

    data class PermissionLost(
        val requiredPermission: String?,
    ) : HealthServicesObservationSourceUpdate
}

internal class HealthServicesObservationRegistrationException(
    message: String?,
    cause: Throwable,
) : IllegalStateException(
        message,
        cause,
    )

/**
 * Higher-priority Health Services implementation of sensor.observe.heart_rate.
 *
 * This class deliberately contains no Android Health Services API types. The
 * Android adapter is a separate source implementation, which keeps provider
 * mapping and lifecycle behavior JVM-testable.
 *
 * Provider-health callbacks are semantic rather than Android-callback based:
 * every terminal observation failure reports unhealthy, while Registered (or
 * the first Sample when a vendor omits Registered) reports actual health.
 */
internal class HealthServicesObservationCapability(
    private val logicalIdValue: String,
    private val source: HealthServicesObservationSource,
    private val onProviderHealthy:
        (logicalId: String) -> Unit =
        {},
    private val onProviderFailure:
        (
            logicalId: String,
            reason: String,
        ) -> Unit =
        { _, _ -> },
) : SensorObservationCapability {

    override val capabilityId =
        SensorObservationCapabilityId
            .forLogical(
                logicalIdValue,
            )

    override fun observe(
        options: SensorObservationOptions,
    ): Flow<SensorObservationUpdate> =
        flow {
            /*
             * SensorObservationOptions already validates positivity. Read the
             * value explicitly to document that it is intentionally not passed
             * to Health Services as an acquisition cadence.
             */
            options.preferredSamplePeriodMs

            var failureReported =
                false

            fun reportFailure(
                reason: String,
            ) {
                if (
                    failureReported
                ) {
                    return
                }

                failureReported =
                    true

                runCatching {
                    onProviderFailure(
                        logicalIdValue,
                        reason,
                    )
                }
            }

            fun reportHealthy() {
                runCatching {
                    onProviderHealthy(
                        logicalIdValue,
                    )
                }
            }

            try {
                if (
                    !source.hasRequiredPermission(
                        logicalIdValue,
                    )
                ) {
                    reportFailure(
                        REASON_PERMISSION_DENIED,
                    )

                    emit(
                        SensorObservationUpdate
                            .PermissionDenied(
                                requiredPermission =
                                    HealthServicesProvider
                                        .requiredPermission(
                                            logicalIdValue,
                                        ),
                            ),
                    )
                    return@flow
                }

                if (
                    !source.supports(
                        logicalIdValue,
                    )
                ) {
                    reportFailure(
                        REASON_PASSIVE_UNSUPPORTED,
                    )

                    emit(
                        SensorObservationUpdate
                            .Unavailable(
                                reason =
                                    REASON_PASSIVE_UNSUPPORTED,
                            ),
                    )
                    return@flow
                }

                var started =
                    false

                source
                    .observe(
                        logicalIdValue,
                    )
                    .collect { update ->
                        when (update) {
                            HealthServicesObservationSourceUpdate.Registered -> {
                                if (!started) {
                                    started =
                                        true

                                    reportHealthy()

                                    emit(
                                        SensorObservationUpdate
                                            .Started(
                                                effectiveSamplePeriodMs =
                                                    null,
                                                configuredSamplePeriodMs =
                                                    null,
                                            ),
                                    )
                                }
                            }

                            is HealthServicesObservationSourceUpdate.SourceDrops ->
                                emit(
                                    SensorObservationUpdate
                                        .SourceDrops(
                                            sourceDroppedTotal =
                                                update
                                                    .sourceDroppedTotal,
                                        ),
                                )

                            is HealthServicesObservationSourceUpdate.Sample -> {
                                /*
                                 * A data callback is also proof of successful
                                 * registration on vendor implementations that
                                 * omit the optional onRegistered callback.
                                 */
                                if (!started) {
                                    started =
                                        true

                                    reportHealthy()

                                    emit(
                                        SensorObservationUpdate
                                            .Started(
                                                effectiveSamplePeriodMs =
                                                    null,
                                                configuredSamplePeriodMs =
                                                    null,
                                            ),
                                    )
                                }

                                emit(
                                    SensorObservationUpdate
                                        .Sample(
                                            reading =
                                                SensorReading(
                                                    capabilityId =
                                                        SensorCapabilityId(
                                                            "sensor.$logicalIdValue",
                                                        ),
                                                    providerId =
                                                        HealthServicesProvider
                                                            .PROVIDER_ID,
                                                    values =
                                                        update
                                                            .sample
                                                            .values,
                                                    timestampNanos =
                                                        update
                                                            .sample
                                                            .timestampNanos,
                                                    accuracy =
                                                        null,
                                                    elapsedMs =
                                                        0L,
                                                ),
                                            sourceDroppedTotal =
                                                update
                                                    .sourceDroppedTotal,
                                        ),
                                )
                            }

                            is HealthServicesObservationSourceUpdate.PermissionLost -> {
                                reportFailure(
                                    REASON_PERMISSION_LOST,
                                )

                                emit(
                                    SensorObservationUpdate
                                        .PermissionDenied(
                                            requiredPermission =
                                                update
                                                    .requiredPermission,
                                        ),
                                )
                            }
                        }
                    }

                /*
                 * A continuous observation source completing without
                 * cancellation is unhealthy even when no explicit terminal
                 * callback preceded it.
                 */
                reportFailure(
                    REASON_PROVIDER_STREAM_COMPLETED,
                )
            } catch (
                cancellation: CancellationException,
            ) {
                throw cancellation
            } catch (
                error: SecurityException,
            ) {
                reportFailure(
                    REASON_PERMISSION_DENIED,
                )

                emit(
                    SensorObservationUpdate
                        .PermissionDenied(
                            requiredPermission =
                                HealthServicesProvider
                                    .requiredPermission(
                                        logicalIdValue,
                                    ),
                        ),
                )
            } catch (
                error: UnsupportedOperationException,
            ) {
                reportFailure(
                    REASON_PASSIVE_UNSUPPORTED,
                )

                emit(
                    SensorObservationUpdate
                        .Unavailable(
                            reason =
                                error.message
                                    ?: REASON_PASSIVE_UNSUPPORTED,
                        ),
                )
            } catch (
                error: HealthServicesObservationRegistrationException,
            ) {
                if (
                    error.cause is
                        SecurityException
                ) {
                    reportFailure(
                        REASON_PERMISSION_DENIED,
                    )

                    emit(
                        SensorObservationUpdate
                            .PermissionDenied(
                                requiredPermission =
                                    HealthServicesProvider
                                        .requiredPermission(
                                            logicalIdValue,
                                        ),
                            ),
                    )
                } else {
                    reportFailure(
                        REASON_REGISTRATION_REJECTED,
                    )

                    emit(
                        SensorObservationUpdate
                            .RegistrationRejected(
                                reason =
                                    error.message,
                            ),
                    )
                }
            }
        }

    private companion object {
        const val REASON_PASSIVE_UNSUPPORTED =
            "health_services_passive_unsupported"

        const val REASON_PERMISSION_DENIED =
            "permission_denied"

        const val REASON_PERMISSION_LOST =
            "permission_lost"

        const val REASON_REGISTRATION_REJECTED =
            "registration_rejected"

        const val REASON_PROVIDER_STREAM_COMPLETED =
            "provider_stream_completed"
    }
}

internal fun healthServicesObservationCapabilities(
    source: HealthServicesObservationSource,
    onProviderHealthy:
        (logicalId: String) -> Unit =
        {},
    onProviderFailure:
        (
            logicalId: String,
            reason: String,
        ) -> Unit =
        { _, _ -> },
): List<SensorObservationCapability> =
    listOf(
        HealthServicesObservationCapability(
            logicalIdValue =
                "heart_rate",
            source =
                source,
            onProviderHealthy =
                onProviderHealthy,
            onProviderFailure =
                onProviderFailure,
        ),
    )
