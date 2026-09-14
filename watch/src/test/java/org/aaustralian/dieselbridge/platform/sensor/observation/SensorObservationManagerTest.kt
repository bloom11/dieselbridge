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
        private val testLogicalId: String,
        private val providerId: String,
    ) : SensorObservationCapability {

        override val capabilityId =
            SensorObservationCapabilityId
                .forLogical(
                    testLogicalId,
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
            sourceDroppedTotal: Long = 0L,
        ) {
            updates.emit(
                SensorObservationUpdate
                    .Sample(
                        reading =
                            SensorReading(
                                capabilityId =
                                    SensorCapabilityId(
                                        "sensor.$testLogicalId",
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
                        sourceDroppedTotal =
                            sourceDroppedTotal,
                    ),
            )
        }

        suspend fun emitSourceDrops(
            sourceDroppedTotal: Long,
        ) {
            updates.emit(
                SensorObservationUpdate
                    .SourceDrops(
                        sourceDroppedTotal =
                            sourceDroppedTotal,
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
                    testLogicalId =
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
                    testLogicalId =
                        "heart_rate",
                    providerId =
                        "provider.low",
                )

            val high =
                FakeObservationCapability(
                    testLogicalId =
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
                    testLogicalId =
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
                    testLogicalId =
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
    fun providerAndConsumerDropsRemainIndependent() =
        runTest {
            val registry =
                CapabilityRegistry()

            val capability =
                FakeObservationCapability(
                    testLogicalId =
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
                    "drop-accounting",
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
                                .Latest,
                    ),
                )

            runCurrent()

            capability.emitSample(
                value =
                    1f,
                sourceDroppedTotal =
                    2L,
            )
            runCurrent()

            advanceTimeBy(
                20L,
            )

            /*
             * Latest has capacity one, so this second delivered sample causes
             * one consumer-queue drop while the provider reports four source
             * drops independently.
             */
            capability.emitSample(
                value =
                    2f,
                sourceDroppedTotal =
                    4L,
            )
            runCurrent()

            val receive =
                async {
                    subscription
                        .samples
                        .take(
                            1,
                        )
                        .toList()
                        .single()
                }

            runCurrent()

            val sample =
                receive.await()

            assertEquals(
                2f,
                sample.reading
                    .values
                    .single(),
            )

            assertEquals(
                1L,
                sample.droppedTotal,
            )

            assertEquals(
                4L,
                sample.sourceDroppedTotal,
            )

            client.close()
            runCurrent()

            manager.close()
        }

    @Test
    fun newSubscriberDoesNotInheritEarlierProviderDrops() =
        runTest {
            val registry =
                CapabilityRegistry()

            val capability =
                FakeObservationCapability(
                    testLogicalId =
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

            val firstClient =
                manager.openClient(
                    "first",
                )

            val first =
                firstClient.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "accelerometer",
                        periodMs =
                            20L,
                    ),
                )

            runCurrent()

            capability.emitSample(
                value =
                    1f,
                sourceDroppedTotal =
                    5L,
            )
            runCurrent()

            /*
             * Drain the first sample so its Latest queue does not introduce
             * consumer-queue loss into this source-drop-baseline test.
             */
            val firstInitial =
                async {
                    first.samples
                        .take(
                            1,
                        )
                        .toList()
                        .single()
                }

            runCurrent()

            assertEquals(
                5L,
                firstInitial
                    .await()
                    .sourceDroppedTotal,
            )

            val secondClient =
                manager.openClient(
                    "second",
                )

            val second =
                secondClient.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "accelerometer",
                        periodMs =
                            20L,
                    ),
                )

            runCurrent()

            advanceTimeBy(
                20L,
            )

            capability.emitSample(
                value =
                    2f,
                sourceDroppedTotal =
                    6L,
            )
            runCurrent()

            val firstNext =
                async {
                    first.samples
                        .take(
                            1,
                        )
                        .toList()
                        .single()
                }

            val secondNext =
                async {
                    second.samples
                        .take(
                            1,
                        )
                        .toList()
                        .single()
                }

            runCurrent()

            assertEquals(
                6L,
                firstNext
                    .await()
                    .sourceDroppedTotal,
            )

            assertEquals(
                1L,
                secondNext
                    .await()
                    .sourceDroppedTotal,
            )

            firstClient.close()
            secondClient.close()
            runCurrent()

            manager.close()
        }

    @Test
    fun providerDropCounterResetDoesNotMoveRuntimeTotalBackward() =
        runTest {
            val registry =
                CapabilityRegistry()

            val capability =
                FakeObservationCapability(
                    testLogicalId =
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
                    "counter-reset",
                )

            val subscription =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "accelerometer",
                        periodMs =
                            20L,
                    ),
                )

            runCurrent()

            capability.emitSample(
                value =
                    1f,
                sourceDroppedTotal =
                    5L,
            )
            runCurrent()

            val first =
                async {
                    subscription.samples
                        .take(
                            1,
                        )
                        .toList()
                        .single()
                }

            runCurrent()

            assertEquals(
                5L,
                first
                    .await()
                    .sourceDroppedTotal,
            )

            advanceTimeBy(
                20L,
            )

            /*
             * Simulate a provider-local counter reset. Runtime accounting must
             * accumulate the new-session value rather than moving backward.
             */
            capability.emitSample(
                value =
                    2f,
                sourceDroppedTotal =
                    2L,
            )
            runCurrent()

            val second =
                async {
                    subscription.samples
                        .take(
                            1,
                        )
                        .toList()
                        .single()
                }

            runCurrent()

            assertEquals(
                7L,
                second
                    .await()
                    .sourceDroppedTotal,
            )

            client.close()
            runCurrent()

            manager.close()
        }

    @Test
    fun sourceDropTelemetryUpdatesStateWithoutAnotherSample() =
        runTest {
            val registry =
                CapabilityRegistry()

            val capability =
                FakeObservationCapability(
                    testLogicalId =
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
                    "metrics",
                )

            val subscription =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "accelerometer",
                        periodMs =
                            20L,
                    ),
                )

            runCurrent()

            capability.emitSourceDrops(
                4L,
            )
            runCurrent()

            assertEquals(
                4L,
                subscription
                    .state
                    .value
                    .sourceDroppedTotal,
            )

            client.close()
            runCurrent()

            manager.closeAndJoin()
        }

    @Test
    fun consumerQueueDropsUpdateStateWithoutLaterSample() =
        runTest {
            val registry =
                CapabilityRegistry()

            val capability =
                FakeObservationCapability(
                    testLogicalId =
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
                    "queue-metrics",
                )

            val subscription =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId =
                            "accelerometer",
                        periodMs =
                            20L,
                        bufferPolicy =
                            SensorBufferPolicy.Latest,
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

            assertEquals(
                1L,
                subscription
                    .state
                    .value
                    .subscriptionDroppedTotal,
            )

            client.close()
            runCurrent()

            manager.closeAndJoin()
        }

    @Test
    fun closeAndJoinWaitsForProviderCleanup() =
        runTest {
            val registry =
                CapabilityRegistry()

            val capability =
                FakeObservationCapability(
                    testLogicalId =
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
            )

            val manager =
                SensorObservationManager(
                    registry =
                        registry,
                    scope =
                        backgroundScope,
                )

            val client =
                manager.openClient(
                    "shutdown",
                )

            client.subscribe(
                SensorSubscriptionRequest(
                    logicalId =
                        "heart_rate",
                    periodMs =
                        100L,
                ),
            )

            runCurrent()

            assertEquals(
                1,
                capability.activeSessions,
            )

            manager.closeAndJoin()

            assertEquals(
                0,
                capability.activeSessions,
            )

            assertTrue(
                capability.cancellations >
                    0,
            )
        }

    @Test
    fun closingClientClosesEveryOwnedSubscription() =
        runTest {
            val registry =
                CapabilityRegistry()

            val heartRate =
                FakeObservationCapability(
                    testLogicalId =
                        "heart_rate",
                    providerId =
                        "provider.hr",
                )

            val accelerometer =
                FakeObservationCapability(
                    testLogicalId =
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
