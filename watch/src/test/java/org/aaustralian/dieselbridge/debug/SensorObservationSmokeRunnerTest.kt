// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.SensorReading
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapability
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationManager
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationOptions
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorObservationSmokeRunnerTest {

    private class FakeProvider(
        override val providerId: String,
    ) : DieselProvider

    private class FakeCapability(
        override val logicalId: String,
        private val providerId: String,
        private val configuredPeriodMs: Long? = null,
        private val fixedTimestamp: Boolean = false,
    ) : SensorObservationCapability {

        override val capabilityId =
            SensorObservationCapabilityId.forLogical(logicalId)

        override fun observe(
            options: SensorObservationOptions,
        ): Flow<SensorObservationUpdate> =
            flow {
                val periodMs =
                    configuredPeriodMs
                        ?: options.preferredSamplePeriodMs

                emit(
                    SensorObservationUpdate.Started(
                        configuredSamplePeriodMs = periodMs,
                    ),
                )

                var sequence = 0L

                while (true) {
                    delay(periodMs)
                    sequence++

                    emit(
                        SensorObservationUpdate.Sample(
                            reading =
                                SensorReading(
                                    capabilityId =
                                        SensorCapabilityId(
                                            "sensor.$logicalId",
                                        ),
                                    providerId = providerId,
                                    values =
                                        listOf(
                                            sequence.toFloat(),
                                        ),
                                    timestampNanos =
                                        if (fixedTimestamp) {
                                            periodMs * 1_000_000L
                                        } else {
                                            sequence * periodMs * 1_000_000L
                                        },
                                    accuracy = 3,
                                    elapsedMs = 0L,
                                ),
                        ),
                    )
                }
            }
    }

    @Test
    fun basicProfileCapturesSamplesAndCloses() =
        runTest {
            val registry = CapabilityRegistry()
            val provider = FakeProvider("fake.sensor_manager")

            registry.register(
                capability =
                    FakeCapability(
                        logicalId = "accelerometer",
                        providerId = provider.providerId,
                    ),
                provider = provider,
                priority = 10,
            )

            val manager =
                SensorObservationManager(
                    registry = registry,
                    scope = backgroundScope,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = backgroundScope,
                    wallClockMs = {
                        testScheduler.currentTime
                    },
                )

            assertNotNull(
                runner.start(
                    SensorObservationSmokeProfile.BASIC,
                ),
            )

            advanceUntilIdle()

            val result =
                requireNotNull(
                    runner.latest(),
                )

            assertEquals(
                SensorObservationSmokeStatus.PASSED,
                result.status,
            )

            assertEquals(
                12,
                result.sampleCount,
            )

            assertEquals(
                1L,
                requireNotNull(
                    result.firstSequence,
                ),
            )

            assertEquals(
                12L,
                requireNotNull(
                    result.lastSequence,
                ),
            )

            assertEquals(
                0,
                result.nonAdvancingSequenceCount,
            )

            assertEquals(
                0,
                result.nonAdvancingTimestampCount,
            )

            assertEquals(
                250L,
                requireNotNull(
                    result.observedMedianIntervalMs,
                ),
            )

            assertTrue(
                "closed" in
                    result.phaseTransitions,
            )

            runner.close()
            manager.close()
        }

    @Test
    fun cadenceProfileVerifiesConfiguredPhysicalClamp() =
        runTest {
            val registry = CapabilityRegistry()
            val provider = FakeProvider("android.sensor_manager")

            registry.register(
                capability =
                    FakeCapability(
                        logicalId = "accelerometer",
                        providerId = provider.providerId,
                        configuredPeriodMs = 200L,
                    ),
                provider = provider,
                priority = 10,
            )

            val manager =
                SensorObservationManager(
                    registry = registry,
                    scope = backgroundScope,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = backgroundScope,
                    routeInspector =
                        SensorObservationSmokeRouteInspector {
                                _,
                                _
                            ->
                            SensorObservationSmokeRoute(
                                routeId =
                                    "android.sensor_manager:1:0:0",
                                wakeUp = true,
                                minDelayUs = 200_000,
                                reportingMode = 0,
                                powerMilliAmps = 0.1f,
                            )
                        },
                    wallClockMs = {
                        testScheduler.currentTime
                    },
                )

            runner.start(
                SensorObservationSmokeProfile.CADENCE,
            )

            advanceUntilIdle()

            val result =
                requireNotNull(
                    runner.latest(),
                )

            assertEquals(
                SensorObservationSmokeStatus.PASSED,
                result.status,
            )

            assertEquals(
                20L,
                requireNotNull(
                    result.requestedPeriodMs,
                ),
            )

            assertEquals(
                200L,
                requireNotNull(
                    result.providerConfiguredPeriodMs,
                ),
            )

            assertEquals(
                200L,
                requireNotNull(
                    result.observedMedianIntervalMs,
                ),
            )

            assertEquals(
                0,
                result.nonAdvancingTimestampCount,
            )

            runner.close()
            manager.close()
        }

    @Test
    fun basicProfileRejectsNonAdvancingSensorTimestamps() =
        runTest {
            val registry = CapabilityRegistry()
            val provider = FakeProvider("fake.sensor_manager")

            registry.register(
                capability =
                    FakeCapability(
                        logicalId = "accelerometer",
                        providerId = provider.providerId,
                        fixedTimestamp = true,
                    ),
                provider = provider,
                priority = 10,
            )

            val manager =
                SensorObservationManager(
                    registry = registry,
                    scope = backgroundScope,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = backgroundScope,
                    wallClockMs = {
                        testScheduler.currentTime
                    },
                )

            runner.start(
                SensorObservationSmokeProfile.BASIC,
            )

            advanceUntilIdle()

            val result =
                requireNotNull(
                    runner.latest(),
                )

            assertEquals(
                SensorObservationSmokeStatus.FAILED,
                result.status,
            )

            assertTrue(
                result.nonAdvancingTimestampCount > 0,
            )

            assertEquals(
                0,
                result.nonAdvancingSequenceCount,
            )

            runner.close()
            manager.close()
        }

    @Test
    fun secondRunIsRejectedWhileOneIsActive() =
        runTest {
            val registry = CapabilityRegistry()
            val provider = FakeProvider("fake.sensor_manager")

            registry.register(
                capability =
                    FakeCapability(
                        logicalId = "accelerometer",
                        providerId = provider.providerId,
                    ),
                provider = provider,
                priority = 10,
            )

            val manager =
                SensorObservationManager(
                    registry = registry,
                    scope = backgroundScope,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = backgroundScope,
                )

            val first =
                runner.start(
                    SensorObservationSmokeProfile.SCREEN_OFF,
                )

            val second =
                runner.start(
                    SensorObservationSmokeProfile.BASIC,
                )

            assertNotNull(
                first,
            )

            assertNull(
                second,
            )

            assertTrue(
                runner.cancel(
                    requireNotNull(
                        first,
                    ),
                ),
            )

            advanceUntilIdle()

            assertEquals(
                SensorObservationSmokeStatus.CANCELLED,
                runner.latest()?.status,
            )

            runner.close()
            manager.close()
        }
}
