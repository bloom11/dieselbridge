package org.aaustralian.dieselbridge.platform.sensor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationOptions
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthServicesObservationCapabilityTest {

    @Test
    fun registeredAndSampleMapToProviderNeutralObservation() =
        runTest {
            val source =
                FakeSource(
                    updates =
                        flowOf(
                            HealthServicesObservationSourceUpdate.Registered,
                            HealthServicesObservationSourceUpdate.Sample(
                                sample =
                                    HealthServicesSample(
                                        values =
                                            listOf(
                                                72f,
                                            ),
                                        timestampNanos =
                                            42L,
                                    ),
                            ),
                        ),
                )

            val updates =
                mutableListOf<
                    SensorObservationUpdate
                >()

            healthServicesObservationCapabilities(
                source,
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        preferredSamplePeriodMs =
                            1_000L,
                    ),
                )
                .collect {
                    updates += it
                }

            assertEquals(
                2,
                updates.size,
            )

            val started =
                updates[0]
                    as SensorObservationUpdate.Started

            assertNull(
                started.effectiveSamplePeriodMs,
            )
            assertNull(
                started.configuredSamplePeriodMs,
            )

            val sample =
                updates[1]
                    as SensorObservationUpdate.Sample

            assertEquals(
                "wear.health_services",
                sample.reading.providerId,
            )
            assertEquals(
                "sensor.heart_rate",
                sample.reading.capabilityId.value,
            )
            assertEquals(
                listOf(
                    72f,
                ),
                sample.reading.values,
            )
            assertEquals(
                42L,
                sample.reading.timestampNanos,
            )
        }

    @Test
    fun missingPermissionStopsBeforeSupportAndRegistration() =
        runTest {
            val source =
                FakeSource(
                    permission =
                        false,
                )

            val updates =
                mutableListOf<
                    SensorObservationUpdate
                >()

            healthServicesObservationCapabilities(
                source,
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        1_000L,
                    ),
                )
                .collect {
                    updates += it
                }

            val denied =
                updates.single()
                    as SensorObservationUpdate.PermissionDenied

            assertEquals(
                "android.permission.BODY_SENSORS",
                denied.requiredPermission,
            )
            assertEquals(
                0,
                source.supportCalls,
            )
            assertEquals(
                0,
                source.observeCalls,
            )
        }

    @Test
    fun unsupportedPassiveHeartRateIsUnavailable() =
        runTest {
            val source =
                FakeSource(
                    supported =
                        false,
                )

            val updates =
                mutableListOf<
                    SensorObservationUpdate
                >()

            healthServicesObservationCapabilities(
                source,
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        1_000L,
                    ),
                )
                .collect {
                    updates += it
                }

            assertEquals(
                1,
                updates.size,
            )
            assertTrue(
                updates.single() is
                    SensorObservationUpdate.Unavailable,
            )
            assertEquals(
                0,
                source.observeCalls,
            )
        }

    @Test
    fun sampleCanImplicitlyEstablishStartedState() =
        runTest {
            val source =
                FakeSource(
                    updates =
                        flowOf(
                            HealthServicesObservationSourceUpdate.Sample(
                                sample =
                                    HealthServicesSample(
                                        values =
                                            listOf(
                                                76f,
                                            ),
                                        timestampNanos =
                                            99L,
                                    ),
                            ),
                        ),
                )

            val updates =
                mutableListOf<
                    SensorObservationUpdate
                >()

            healthServicesObservationCapabilities(
                source,
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        1_000L,
                    ),
                )
                .collect {
                    updates += it
                }

            assertTrue(
                updates[0] is
                    SensorObservationUpdate.Started,
            )
            assertTrue(
                updates[1] is
                    SensorObservationUpdate.Sample,
            )
        }

    @Test
    fun sourceDropTelemetryIsPreserved() =
        runTest {
            val source =
                FakeSource(
                    updates =
                        flowOf(
                            HealthServicesObservationSourceUpdate.Registered,
                            HealthServicesObservationSourceUpdate.SourceDrops(
                                sourceDroppedTotal =
                                    3L,
                            ),
                            HealthServicesObservationSourceUpdate.Sample(
                                sample =
                                    HealthServicesSample(
                                        values =
                                            listOf(
                                                80f,
                                            ),
                                        timestampNanos =
                                            100L,
                                    ),
                                sourceDroppedTotal =
                                    3L,
                            ),
                        ),
                )

            val updates =
                mutableListOf<
                    SensorObservationUpdate
                >()

            healthServicesObservationCapabilities(
                source,
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        1_000L,
                    ),
                )
                .collect {
                    updates += it
                }

            val drops =
                updates
                    .filterIsInstance<
                        SensorObservationUpdate.SourceDrops
                    >()
                    .single()

            val sample =
                updates
                    .filterIsInstance<
                        SensorObservationUpdate.Sample
                    >()
                    .single()

            assertEquals(
                3L,
                drops.sourceDroppedTotal,
            )
            assertEquals(
                3L,
                sample.sourceDroppedTotal,
            )
        }

    @Test
    fun permissionLossMapsToPermissionDenied() =
        runTest {
            val source =
                FakeSource(
                    updates =
                        flowOf(
                            HealthServicesObservationSourceUpdate.Registered,
                            HealthServicesObservationSourceUpdate.PermissionLost(
                                requiredPermission =
                                    "android.permission.BODY_SENSORS",
                            ),
                        ),
                )

            val updates =
                mutableListOf<
                    SensorObservationUpdate
                >()

            healthServicesObservationCapabilities(
                source,
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        1_000L,
                    ),
                )
                .collect {
                    updates += it
                }

            assertTrue(
                updates[0] is
                    SensorObservationUpdate.Started,
            )

            val denied =
                updates[1]
                    as SensorObservationUpdate.PermissionDenied

            assertEquals(
                "android.permission.BODY_SENSORS",
                denied.requiredPermission,
            )
        }

    @Test
    fun registrationFailureMapsToRegistrationRejected() =
        runTest {
            val source =
                FakeSource(
                    updates =
                        flow {
                            throw HealthServicesObservationRegistrationException(
                                "registration failed",
                                IllegalStateException(
                                    "provider rejected request",
                                ),
                            )
                        },
                )

            val updates =
                mutableListOf<
                    SensorObservationUpdate
                >()

            healthServicesObservationCapabilities(
                source,
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        1_000L,
                    ),
                )
                .collect {
                    updates += it
                }

            val rejected =
                updates.single()
                    as SensorObservationUpdate.RegistrationRejected

            assertEquals(
                "registration failed",
                rejected.reason,
            )
        }

    @Test
    fun cancellationReleasesSourceCollection() =
        runTest {
            val released =
                CompletableDeferred<Unit>()

            val source =
                FakeSource(
                    updates =
                        flow {
                            emit(
                                HealthServicesObservationSourceUpdate.Registered,
                            )

                            try {
                                awaitCancellation()
                            } finally {
                                released.complete(
                                    Unit,
                                )
                            }
                        },
                )

            val capability =
                healthServicesObservationCapabilities(
                    source,
                ).single()

            val started =
                CompletableDeferred<Unit>()

            val job =
                launch {
                    capability
                        .observe(
                            SensorObservationOptions(
                                1_000L,
                            ),
                        )
                        .collect {
                            if (
                                it is
                                    SensorObservationUpdate.Started
                            ) {
                                started.complete(
                                    Unit,
                                )
                            }
                        }
                }

            started.await()
            job.cancelAndJoin()
            released.await()

            assertTrue(
                released.isCompleted,
            )
        }

    private class FakeSource(
        private val permission:
            Boolean = true,
        private val supported:
            Boolean = true,
        private val updates:
            Flow<
                HealthServicesObservationSourceUpdate
            > =
            flowOf(),
    ) : HealthServicesObservationSource {

        var supportCalls =
            0
            private set

        var observeCalls =
            0
            private set

        override suspend fun supports(
            logicalId: String,
        ): Boolean {
            supportCalls++

            return supported &&
                logicalId ==
                "heart_rate"
        }

        override fun hasRequiredPermission(
            logicalId: String,
        ): Boolean =
            permission &&
                logicalId ==
                "heart_rate"

        override fun observe(
            logicalId: String,
        ): Flow<
            HealthServicesObservationSourceUpdate
        > {
            observeCalls++
            return updates
        }
    }
}
