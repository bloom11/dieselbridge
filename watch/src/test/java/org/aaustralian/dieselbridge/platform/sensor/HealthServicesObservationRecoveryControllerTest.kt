package org.aaustralian.dieselbridge.platform.sensor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthServicesObservationRecoveryControllerTest {

    @Test
    fun unavailableProbeBacksOffThenPromotesWhenHealthy() =
        runTest {
            var available =
                false

            var probes =
                0

            val promotions =
                mutableListOf<String>()

            val controller =
                HealthServicesObservationRecoveryController(
                    scope =
                        backgroundScope,
                    probeAvailable = {
                        probes++
                        available
                    },
                    promoteAvailable = {
                        promotions += it
                    },
                    retryDelaysMs =
                        listOf(
                            10L,
                            20L,
                            40L,
                        ),
                )

            controller.providerFailed(
                "heart_rate",
            )

            runCurrent()

            assertEquals(
                0,
                probes,
            )

            advanceTimeBy(
                10L,
            )
            runCurrent()

            assertEquals(
                1,
                probes,
            )
            assertEquals(
                emptyList<String>(),
                promotions,
            )

            available =
                true

            advanceTimeBy(
                20L,
            )
            runCurrent()

            assertEquals(
                2,
                probes,
            )
            assertEquals(
                listOf(
                    "heart_rate",
                ),
                promotions,
            )

            controller.close()
        }

    @Test
    fun duplicateFailureWhileWaitingDoesNotCreateDuplicateRecovery() =
        runTest {
            var probes =
                0

            val controller =
                HealthServicesObservationRecoveryController(
                    scope =
                        backgroundScope,
                    probeAvailable = {
                        probes++
                        false
                    },
                    promoteAvailable = {},
                    retryDelaysMs =
                        listOf(
                            10L,
                            20L,
                        ),
                )

            controller.providerFailed(
                "heart_rate",
            )

            controller.providerFailed(
                "heart_rate",
            )

            advanceTimeBy(
                10L,
            )
            runCurrent()

            assertEquals(
                1,
                probes,
            )

            advanceTimeBy(
                20L,
            )
            runCurrent()

            assertEquals(
                2,
                probes,
            )

            controller.close()
        }

    @Test
    fun failureRacingPromotionUsesNextBackoffStep() =
        runTest {
            var promotions =
                0

            lateinit var controller:
                HealthServicesObservationRecoveryController

            controller =
                HealthServicesObservationRecoveryController(
                    scope =
                        backgroundScope,
                    probeAvailable = {
                        true
                    },
                    promoteAvailable = {
                        promotions++

                        if (
                            promotions ==
                            1
                        ) {
                            controller.providerFailed(
                                it,
                            )
                        }
                    },
                    retryDelaysMs =
                        listOf(
                            10L,
                            20L,
                            40L,
                        ),
                )

            controller.providerFailed(
                "heart_rate",
            )

            advanceTimeBy(
                10L,
            )
            runCurrent()

            assertEquals(
                1,
                promotions,
            )

            advanceTimeBy(
                19L,
            )
            runCurrent()

            assertEquals(
                1,
                promotions,
            )

            advanceTimeBy(
                1L,
            )
            runCurrent()

            assertEquals(
                2,
                promotions,
            )

            controller.close()
        }

    @Test
    fun providerHealthyCancelsPendingRetryAndResetsBackoff() =
        runTest {
            var available =
                false

            var probes =
                0

            var promotions =
                0

            val controller =
                HealthServicesObservationRecoveryController(
                    scope =
                        backgroundScope,
                    probeAvailable = {
                        probes++
                        available
                    },
                    promoteAvailable = {
                        promotions++
                    },
                    retryDelaysMs =
                        listOf(
                            10L,
                            20L,
                            40L,
                        ),
                )

            controller.providerFailed(
                "heart_rate",
            )

            advanceTimeBy(
                10L,
            )
            runCurrent()

            assertEquals(
                1,
                probes,
            )

            controller.providerHealthy(
                "heart_rate",
            )

            advanceTimeBy(
                100L,
            )
            runCurrent()

            assertEquals(
                1,
                probes,
            )

            available =
                true

            controller.providerFailed(
                "heart_rate",
            )

            advanceTimeBy(
                9L,
            )
            runCurrent()

            assertEquals(
                0,
                promotions,
            )

            advanceTimeBy(
                1L,
            )
            runCurrent()

            assertEquals(
                2,
                probes,
            )
            assertEquals(
                1,
                promotions,
            )

            controller.close()
        }

    @Test
    fun closeCancelsPendingRecovery() =
        runTest {
            var probes =
                0

            val controller =
                HealthServicesObservationRecoveryController(
                    scope =
                        backgroundScope,
                    probeAvailable = {
                        probes++
                        true
                    },
                    promoteAvailable = {},
                    retryDelaysMs =
                        listOf(
                            10L,
                        ),
                )

            controller.providerFailed(
                "heart_rate",
            )

            controller.close()

            advanceTimeBy(
                100L,
            )
            runCurrent()

            assertEquals(
                0,
                probes,
            )
        }
}
