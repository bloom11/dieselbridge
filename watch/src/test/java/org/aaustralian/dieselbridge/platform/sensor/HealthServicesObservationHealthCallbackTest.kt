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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthServicesObservationHealthCallbackTest {

    @Test
    fun registeredReportsHealthyExactlyOnce() =
        runTest {
            val healthy =
                mutableListOf<String>()

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
                                            1L,
                                    ),
                            ),
                        ),
                )

            healthServicesObservationCapabilities(
                source =
                    source,
                onProviderHealthy = {
                    healthy += it
                },
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        1_000L,
                    ),
                )
                .collect()

            assertEquals(
                listOf(
                    "heart_rate",
                ),
                healthy,
            )
        }

    @Test
    fun missingPermissionReportsSemanticFailure() =
        runTest {
            val failures =
                mutableListOf<
                    Pair<String, String>
                >()

            val source =
                FakeSource(
                    permission =
                        false,
                )

            healthServicesObservationCapabilities(
                source =
                    source,
                onProviderFailure = {
                        logicalId,
                        reason,
                    ->
                    failures +=
                        logicalId to
                            reason
                },
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        1_000L,
                    ),
                )
                .collect()

            assertEquals(
                listOf(
                    "heart_rate" to
                        "permission_denied",
                ),
                failures,
            )
        }

    @Test
    fun registrationFailureReportsSemanticFailure() =
        runTest {
            val failures =
                mutableListOf<
                    Pair<String, String>
                >()

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

            healthServicesObservationCapabilities(
                source =
                    source,
                onProviderFailure = {
                        logicalId,
                        reason,
                    ->
                    failures +=
                        logicalId to
                            reason
                },
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        1_000L,
                    ),
                )
                .collect()

            assertEquals(
                listOf(
                    "heart_rate" to
                        "registration_rejected",
                ),
                failures,
            )
        }

    @Test
    fun permissionLossAfterHealthyReportsFailure() =
        runTest {
            val healthy =
                mutableListOf<String>()

            val failures =
                mutableListOf<
                    Pair<String, String>
                >()

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

            healthServicesObservationCapabilities(
                source =
                    source,
                onProviderHealthy = {
                    healthy += it
                },
                onProviderFailure = {
                        logicalId,
                        reason,
                    ->
                    failures +=
                        logicalId to
                            reason
                },
            )
                .single()
                .observe(
                    SensorObservationOptions(
                        1_000L,
                    ),
                )
                .collect()

            assertEquals(
                listOf(
                    "heart_rate",
                ),
                healthy,
            )

            assertEquals(
                listOf(
                    "heart_rate" to
                        "permission_lost",
                ),
                failures,
            )
        }

    @Test
    fun cancellationDoesNotReportProviderFailure() =
        runTest {
            val started =
                CompletableDeferred<Unit>()

            val failures =
                mutableListOf<
                    Pair<String, String>
                >()

            val source =
                FakeSource(
                    updates =
                        flow {
                            emit(
                                HealthServicesObservationSourceUpdate.Registered,
                            )

                            started.complete(
                                Unit,
                            )

                            awaitCancellation()
                        },
                )

            val capability =
                healthServicesObservationCapabilities(
                    source =
                        source,
                    onProviderFailure = {
                            logicalId,
                            reason,
                        ->
                        failures +=
                            logicalId to
                                reason
                    },
                )
                    .single()

            val job =
                launch {
                    capability
                        .observe(
                            SensorObservationOptions(
                                1_000L,
                            ),
                        )
                        .collect()
                }

            started.await()

            job.cancelAndJoin()

            assertTrue(
                failures.isEmpty(),
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

        override suspend fun supports(
            logicalId: String,
        ): Boolean =
            supported &&
                logicalId ==
                "heart_rate"

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
        > =
            updates
    }
}
