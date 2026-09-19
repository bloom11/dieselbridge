// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.SensorReading
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapability
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventBridge
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
        private val emitSamples: Boolean = true,
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

                if (!emitSamples) {
                    awaitCancellation()
                }

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
                    scope = this,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = this,
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
                    scope = this,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = this,
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
                    scope = this,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = this,
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
                    scope = this,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = this,
                )

            val first =
                runner.start(
                    SensorObservationSmokeProfile.SCREEN_OFF,
                )

            testScheduler.runCurrent()

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

    @Test
    fun healthServicesHrProfileRequiresExactProviderAndSample() =
        runTest {
            val registry = CapabilityRegistry()
            val provider = FakeProvider("wear.health_services")

            registry.register(
                capability =
                    FakeCapability(
                        logicalId = "heart_rate",
                        providerId = provider.providerId,
                    ),
                provider = provider,
                priority = 20,
            )

            val manager =
                SensorObservationManager(
                    registry = registry,
                    scope = this,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = this,
                    wallClockMs = {
                        testScheduler.currentTime
                    },
                )

            runner.start(
                SensorObservationSmokeProfile.HEALTH_SERVICES_HR,
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
                "wear.health_services",
                result.providerId,
            )

            assertTrue(
                result.sampleCount >= 1,
            )

            assertEquals(
                "heart_rate",
                result.logicalId,
            )

            assertTrue(
                "closed" in result.phaseTransitions,
            )

            runner.close()
            manager.close()
        }

    @Test
    fun healthServicesHrProfileRejectsSensorManagerFallback() =
        runTest {
            val registry = CapabilityRegistry()
            val provider = FakeProvider("android.sensor_manager")

            registry.register(
                capability =
                    FakeCapability(
                        logicalId = "heart_rate",
                        providerId = provider.providerId,
                    ),
                provider = provider,
                priority = 10,
            )

            val manager =
                SensorObservationManager(
                    registry = registry,
                    scope = this,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = this,
                    wallClockMs = {
                        testScheduler.currentTime
                    },
                )

            runner.start(
                SensorObservationSmokeProfile.HEALTH_SERVICES_HR,
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

            assertEquals(
                "android.sensor_manager",
                result.providerId,
            )

            assertTrue(
                requireNotNull(
                    result.detail,
                ).contains(
                    "Expected provider wear.health_services",
                ),
            )

            runner.close()
            manager.close()
        }


    @Test
    fun healthServicesHrProfileRejectsFallbackAfterInitialActive() =
        runTest {
            val registry = CapabilityRegistry()

            val healthServices =
                FakeProvider(
                    "wear.health_services",
                )

            val sensorManager =
                FakeProvider(
                    "android.sensor_manager",
                )

            registry.register(
                capability =
                    FakeCapability(
                        logicalId = "heart_rate",
                        providerId = healthServices.providerId,
                        emitSamples = false,
                    ),
                provider = healthServices,
                priority = 20,
            )

            registry.register(
                capability =
                    FakeCapability(
                        logicalId = "heart_rate",
                        providerId = sensorManager.providerId,
                        emitSamples = false,
                    ),
                provider = sensorManager,
                priority = 10,
            )

            val manager =
                SensorObservationManager(
                    registry = registry,
                    scope = this,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = this,
                    wallClockMs = {
                        testScheduler.currentTime
                    },
                )

            runner.start(
                SensorObservationSmokeProfile.HEALTH_SERVICES_HR,
            )

            /*
             * Let the priority-20 provider register and establish ACTIVE
             * before simulating runtime Health Services failure.
             */
            testScheduler.runCurrent()

            assertEquals(
                "wear.health_services",
                runner.latest()?.providerId,
            )

            registry.setAvailability(
                capabilityId =
                    SensorObservationCapabilityId
                        .forLogical(
                            "heart_rate",
                        )
                        .value,
                providerId =
                    healthServices.providerId,
                availability =
                    ProviderAvailability.UNAVAILABLE,
                reason =
                    "test_runtime_failure",
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

            assertEquals(
                "android.sensor_manager",
                result.providerId,
            )

            assertTrue(
                requireNotNull(
                    result.detail,
                ).contains(
                    "left ACTIVE before a sample",
                ),
            )

            assertTrue(
                "closed" in result.phaseTransitions,
            )

            runner.close()
            manager.close()
        }

    @Test
    fun healthServicesHrProfileDistinguishesActiveWithoutSample() =
        runTest {
            val registry = CapabilityRegistry()
            val provider = FakeProvider("wear.health_services")

            registry.register(
                capability =
                    FakeCapability(
                        logicalId = "heart_rate",
                        providerId = provider.providerId,
                        emitSamples = false,
                    ),
                provider = provider,
                priority = 20,
            )

            val manager =
                SensorObservationManager(
                    registry = registry,
                    scope = this,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val runner =
                SensorObservationSmokeRunner(
                    observationManager = manager,
                    scope = this,
                    wallClockMs = {
                        testScheduler.currentTime
                    },
                )

            runner.start(
                SensorObservationSmokeProfile.HEALTH_SERVICES_HR,
            )

            advanceUntilIdle()

            val result =
                requireNotNull(
                    runner.latest(),
                )

            assertEquals(
                SensorObservationSmokeStatus.COMPLETED,
                result.status,
            )

            assertEquals(
                "wear.health_services",
                result.providerId,
            )

            assertEquals(
                0,
                result.sampleCount,
            )

            assertTrue(
                requireNotNull(
                    result.detail,
                ).contains(
                    "sample delivery is not",
                ),
            )

            assertTrue(
                "closed" in result.phaseTransitions,
            )

            runner.close()
            manager.close()
        }


    @Test
    fun eventBusProfilePublishesTypedEventsAndCloses() =
        runTest {
            val registry =
                CapabilityRegistry()

            val provider =
                FakeProvider(
                    "fake.sensor_manager",
                )

            registry.register(
                capability =
                    FakeCapability(
                        logicalId =
                            "accelerometer",
                        providerId =
                            provider.providerId,
                    ),
                provider =
                    provider,
                priority =
                    10,
            )

            val manager =
                SensorObservationManager(
                    registry =
                        registry,
                    scope =
                        this,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val eventBus =
                DieselEventBus()

            val runner =
                SensorObservationSmokeRunner(
                    observationManager =
                        manager,
                    scope =
                        this,
                    eventBus =
                        eventBus,
                    eventBridge =
                        SensorObservationEventBridge(
                            events =
                                eventBus,
                            wallClockMs = {
                                testScheduler.currentTime
                            },
                        ),
                    wallClockMs = {
                        testScheduler.currentTime
                    },
                )

            runner.start(
                SensorObservationSmokeProfile
                    .EVENT_BUS,
            )

            advanceUntilIdle()

            val result =
                requireNotNull(
                    runner.latest(),
                )

            assertEquals(
                SensorObservationSmokeStatus
                    .PASSED,
                result.status,
            )

            assertTrue(
                result.eventStateCount >= 2,
            )

            assertTrue(
                result.eventSampleCount >= 3,
            )

            assertTrue(
                result.eventClosedSeen,
            )

            assertEquals(
                0,
                result.eventSamplesAfterClosed,
            )

            assertTrue(
                result.sampleCount >= 3,
            )

            assertEquals(
                0,
                result.nonAdvancingSequenceCount,
            )

            assertEquals(
                0,
                result.nonAdvancingTimestampCount,
            )

            assertTrue(
                "active" in
                    result.phaseTransitions,
            )

            assertEquals(
                "closed",
                result.phaseTransitions
                    .lastOrNull(),
            )

            runner.close()
            manager.close()
        }

}
