// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor.observation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.SensorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorObservationManagerTest {

    private class FakeProvider(
        override val providerId: String,
    ) : DieselProvider

    private class FakeObservationCapability(
        private val logicalId: String,
        private val providerId: String,
    ) : SensorObservationCapability {

        override val capabilityId =
            SensorObservationCapabilityId
                .forLogical(
                    logicalId,
                )

        val requestedPeriods =
            mutableListOf<Long>()

        var starts =
            0
            private set

        var cancellations =
            0
            private set

        var activeSessions =
            0
            private set

        var maxActiveSessions =
            0
            private set

        private val updates =
            MutableSharedFlow<
                SensorObservationUpdate
            >(
                extraBufferCapacity =
                    64,
            )

        override fun observe(
            options:
                SensorObservationOptions,
        ): Flow<SensorObservationUpdate> =
            flow {
                starts++
                activeSessions++

                maxActiveSessions =
                    maxOf(
                        maxActiveSessions,
                        activeSessions,
                    )

                requestedPeriods +=
                    options
                        .preferredSamplePeriodMs

                try {
                    emit(
                        SensorObservationUpdate
                            .Started(
                                effectiveSamplePeriodMs =
                                    options
                                        .preferredSamplePeriodMs,
                            ),
                    )

                    updates.collect {
                        emit(it)
                    }

                    awaitCancellation()
                } finally {
                    activeSessions--
                    cancellations++
                }
            }

        suspend fun emitSample(
            value: Float,
        ) {
            updates.emit(
                SensorObservationUpdate
                    .Sample(
                        SensorReading(
                            capabilityId =
                                SensorCapabilityId(
                                    "sensor.$logicalId",
                                ),
                            providerId =
                                providerId,
                            values =
                                listOf(
                                    value,
                                ),
                            timestampNanos =
                                value
                                    .toLong(),
                            accuracy =
                                3,
                            elapsedMs =
                                0L,
                        ),
                    ),
            )
        }
    }

    @Test
    fun fastestConsumerControlsOneSharedProviderSession() =
        runTest {
            val registry =
                CapabilityRegistry()

            val capability =
                FakeObservationCapability(
                    logicalId =
                        "heart_rate",
                    providerId =
                        "provider.hr",
                )

            registry.register(
                capability =
                    capability,
                provider =
                    FakeProvider(
                        "provider.hr",
                    ),
                priority =
                    10,
            )

            val manager =
                SensorObservationManager(
                    registry =
                        registry,
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            val slowClient =
                manager.openClient(
                    "slow",
                )

            val slow =
                slowClient.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "heart_rate",
                        periodMs =
                            10_000L,
                    ),
                )

            runCurrent()

            assertEquals(
                listOf(
                    10_000L,
                ),
                capability
                    .requestedPeriods,
            )

            assertEquals(
                1,
                capability
                    .maxActiveSessions,
            )

            val fastClient =
                manager.openClient(
                    "fast",
                )

            val fast =
                fastClient.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "heart_rate",
                        periodMs =
                            1_000L,
                    ),
                )

            runCurrent()

            assertEquals(
                listOf(
                    10_000L,
                    1_000L,
                ),
                capability
                    .requestedPeriods,
            )

            assertEquals(
                1,
                capability
                    .maxActiveSessions,
            )

            assertEquals(
                1_000L,
                slow.state.value
                    .acquisitionPeriodMs,
            )

            assertEquals(
                1_000L,
                fast.state.value
                    .acquisitionPeriodMs,
            )

            fast.close()
            runCurrent()

            assertEquals(
                listOf(
                    10_000L,
                    1_000L,
                    10_000L,
                ),
                capability
                    .requestedPeriods,
            )

            assertEquals(
                1,
                capability
                    .maxActiveSessions,
            )

            slow.close()
            runCurrent()

            assertEquals(
                0,
                capability
                    .activeSessions,
            )

            manager.close()
        }

    @Test
    fun providerFailoverPreservesSubscription() =
        runTest {
            val registry =
                CapabilityRegistry()

            val low =
                FakeObservationCapability(
                    logicalId =
                        "heart_rate",
                    providerId =
                        "provider.low",
                )

            val high =
                FakeObservationCapability(
                    logicalId =
                        "heart_rate",
                    providerId =
                        "provider.high",
                )

            registry.register(
                capability =
                    low,
                provider =
                    FakeProvider(
                        "provider.low",
                    ),
                priority =
                    10,
            )

            registry.register(
                capability =
                    high,
                provider =
                    FakeProvider(
                        "provider.high",
                    ),
                priority =
                    20,
            )

            val manager =
                SensorObservationManager(
                    registry =
                        registry,
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            val client =
                manager.openClient(
                    "test",
                )

            val subscription =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "heart_rate",
                        periodMs =
                            1_000L,
                    ),
                )

            val sameSubscription =
                subscription

            runCurrent()

            assertEquals(
                "provider.high",
                subscription
                    .state
                    .value
                    .providerId,
            )

            assertEquals(
                SensorSubscriptionPhase.ACTIVE,
                subscription
                    .state
                    .value
                    .phase,
            )

            registry.setAvailability(
                capabilityId =
                    high.id,
                providerId =
                    "provider.high",
                availability =
                    ProviderAvailability
                        .UNAVAILABLE,
                reason =
                    "test",
            )

            runCurrent()

            assertSame(
                sameSubscription,
                subscription,
            )

            assertEquals(
                "provider.low",
                subscription
                    .state
                    .value
                    .providerId,
            )

            assertEquals(
                0,
                high.activeSessions,
            )

            assertEquals(
                1,
                low.activeSessions,
            )

            registry.setAvailability(
                capabilityId =
                    high.id,
                providerId =
                    "provider.high",
                availability =
                    ProviderAvailability
                        .AVAILABLE,
            )

            runCurrent()

            assertEquals(
                "provider.high",
                subscription
                    .state
                    .value
                    .providerId,
            )

            assertEquals(
                1,
                high.activeSessions,
            )

            assertEquals(
                0,
                low.activeSessions,
            )

            client.close()
            runCurrent()

            manager.close()
        }

    @Test
    fun subscriptionWaitsForProviderAndRecoversWithoutResubscribe() =
        runTest {
            val registry =
                CapabilityRegistry()

            val manager =
                SensorObservationManager(
                    registry =
                        registry,
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            val client =
                manager.openClient(
                    "waiting",
                )

            val subscription =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "accelerometer",
                        periodMs =
                            100L,
                    ),
                )

            runCurrent()

            assertEquals(
                SensorSubscriptionPhase
                    .WAITING_FOR_PROVIDER,
                subscription
                    .state
                    .value
                    .phase,
            )

            val capability =
                FakeObservationCapability(
                    logicalId =
                        "accelerometer",
                    providerId =
                        "provider.accel",
                )

            registry.register(
                capability =
                    capability,
                provider =
                    FakeProvider(
                        "provider.accel",
                    ),
            )

            runCurrent()

            assertEquals(
                SensorSubscriptionPhase
                    .ACTIVE,
                subscription
                    .state
                    .value
                    .phase,
            )

            assertEquals(
                "provider.accel",
                subscription
                    .state
                    .value
                    .providerId,
            )

            client.close()
            runCurrent()

            manager.close()
        }

    @Test
    fun boundedConsumerQueueDropsOldestWithoutBlockingProvider() =
        runTest {
            val registry =
                CapabilityRegistry()

            val capability =
                FakeObservationCapability(
                    logicalId =
                        "accelerometer",
                    providerId =
                        "provider.accel",
                )

            registry.register(
                capability =
                    capability,
                provider =
                    FakeProvider(
                        "provider.accel",
                    ),
            )

            val manager =
                SensorObservationManager(
                    registry =
                        registry,
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            val client =
                manager.openClient(
                    "slow-consumer",
                )

            val subscription =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "accelerometer",
                        periodMs =
                            20L,
                        bufferPolicy =
                            SensorBufferPolicy
                                .Bounded(
                                    capacity =
                                        2,
                                ),
                    ),
                )

            runCurrent()

            capability.emitSample(
                1f,
            )
            runCurrent()

            advanceTimeBy(
                20L,
            )

            capability.emitSample(
                2f,
            )
            runCurrent()

            advanceTimeBy(
                20L,
            )

            capability.emitSample(
                3f,
            )
            runCurrent()

            advanceTimeBy(
                20L,
            )

            capability.emitSample(
                4f,
            )
            runCurrent()

            val receive =
                async {
                    subscription
                        .samples
                        .take(
                            2,
                        )
                        .toList()
                }

            runCurrent()

            val samples =
                receive.await()

            assertEquals(
                listOf(
                    3f,
                    4f,
                ),
                samples.map {
                    it.reading
                        .values
                        .single()
                },
            )

            assertEquals(
                listOf(
                    1L,
                    2L,
                ),
                samples.map {
                    it.droppedTotal
                },
            )

            client.close()
            runCurrent()

            manager.close()
        }

    @Test
    fun closingClientClosesEveryOwnedSubscription() =
        runTest {
            val registry =
                CapabilityRegistry()

            val heartRate =
                FakeObservationCapability(
                    logicalId =
                        "heart_rate",
                    providerId =
                        "provider.hr",
                )

            val accelerometer =
                FakeObservationCapability(
                    logicalId =
                        "accelerometer",
                    providerId =
                        "provider.accel",
                )

            val provider =
                FakeProvider(
                    "provider.multi",
                )

            registry.register(
                capability =
                    heartRate,
                provider =
                    provider,
            )

            registry.register(
                capability =
                    accelerometer,
                provider =
                    provider,
            )

            val manager =
                SensorObservationManager(
                    registry =
                        registry,
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            val client =
                manager.openClient(
                    "owned",
                )

            val hr =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "heart_rate",
                        periodMs =
                            1_000L,
                    ),
                )

            val accel =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "accelerometer",
                        periodMs =
                            100L,
                    ),
                )

            runCurrent()

            assertEquals(
                1,
                heartRate
                    .activeSessions,
            )

            assertEquals(
                1,
                accelerometer
                    .activeSessions,
            )

            client.close()
            runCurrent()

            assertTrue(
                hr.isClosed,
            )

            assertTrue(
                accel.isClosed,
            )

            assertEquals(
                SensorSubscriptionPhase.CLOSED,
                hr.state.value.phase,
            )

            assertEquals(
                SensorSubscriptionPhase.CLOSED,
                accel.state.value.phase,
            )

            assertEquals(
                0,
                heartRate
                    .activeSessions,
            )

            assertEquals(
                0,
                accelerometer
                    .activeSessions,
            )

            manager.close()
        }
}
