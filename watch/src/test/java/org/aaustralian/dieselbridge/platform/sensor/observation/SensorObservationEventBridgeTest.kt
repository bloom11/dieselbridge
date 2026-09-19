// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor.observation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.event.DieselEvent
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.event.SensorObservationSampleEvent
import org.aaustralian.dieselbridge.platform.event.SensorObservationStateEvent
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.SensorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorObservationEventBridgeTest {

    private class FakeSubscription(
        initialState: SensorSubscriptionState,
    ) : SensorSubscription {

        override val id: Long =
            initialState.subscriptionId

        override val logicalId: String =
            initialState.logicalId

        private val mutableState =
            MutableStateFlow(
                initialState,
            )

        override val state:
            StateFlow<SensorSubscriptionState> =
            mutableState

        private val mutableSamples =
            MutableSharedFlow<
                SensorSubscriptionSample
            >(
                extraBufferCapacity =
                    8,
            )

        override val samples:
            Flow<SensorSubscriptionSample> =
            mutableSamples

        private var closed =
            false

        var closeCalls =
            0
            private set

        override val isClosed: Boolean
            get() =
                closed

        fun updateState(
            value: SensorSubscriptionState,
        ) {
            mutableState.value =
                value
        }

        suspend fun emitSample(
            sample: SensorSubscriptionSample,
        ) {
            mutableSamples.emit(
                sample,
            )
        }

        override fun close() {
            closeCalls++
            closed =
                true
        }
    }

    @Test
    fun publishesTypedStateAndSampleEvents() =
        runTest {
            val eventBus =
                DieselEventBus()

            var now =
                1_000L

            val bridge =
                SensorObservationEventBridge(
                    events =
                        eventBus,
                    wallClockMs = {
                        now++
                    },
                )

            val subscription =
                FakeSubscription(
                    initialState =
                        state(
                            phase =
                                SensorSubscriptionPhase
                                    .WAITING_FOR_PROVIDER,
                        ),
                )

            val received =
                mutableListOf<
                    DieselEvent
                >()

            val collector =
                launch {
                    eventBus
                        .events
                        .collect {
                            received +=
                                it
                        }
                }

            runCurrent()

            val registration =
                bridge.attach(
                    subscription =
                        subscription,
                    scope =
                        this,
                )

            runCurrent()

            subscription.updateState(
                state(
                    phase =
                        SensorSubscriptionPhase
                            .ACTIVE,
                    providerId =
                        "wear.health_services",
                    providerDroppedTotal =
                        3L,
                    subscriptionDroppedTotal =
                        2L,
                ),
            )

            runCurrent()

            subscription.emitSample(
                sample(
                    providerDroppedTotal =
                        3L,
                    subscriptionDroppedTotal =
                        2L,
                ),
            )

            runCurrent()

            assertEquals(
                3,
                received.size,
            )

            val waiting =
                received[
                    0
                ] as
                    SensorObservationStateEvent

            assertEquals(
                "sensor.subscription.state",
                waiting.type,
            )
            assertEquals(
                1_000L,
                waiting.timestampMs,
            )
            assertEquals(
                SensorSubscriptionPhase
                    .WAITING_FOR_PROVIDER,
                waiting.state.phase,
            )

            val active =
                received[
                    1
                ] as
                    SensorObservationStateEvent

            assertEquals(
                "wear.health_services",
                active.state.providerId,
            )
            assertEquals(
                3L,
                active.providerDroppedTotal,
            )
            assertEquals(
                2L,
                active.subscriptionDroppedTotal,
            )

            val sampleEvent =
                received[
                    2
                ] as
                    SensorObservationSampleEvent

            assertEquals(
                "sensor.sample",
                sampleEvent.type,
            )
            assertEquals(
                1_002L,
                sampleEvent.timestampMs,
            )
            assertEquals(
                7L,
                sampleEvent.subscriptionId,
            )
            assertEquals(
                "heart_rate",
                sampleEvent.logicalId,
            )
            assertEquals(
                "wear.health_services",
                sampleEvent
                    .sample
                    .reading
                    .providerId,
            )
            assertEquals(
                123_456L,
                sampleEvent
                    .sample
                    .reading
                    .timestampNanos,
            )
            assertEquals(
                listOf(
                    77.0F,
                ),
                sampleEvent
                    .sample
                    .reading
                    .values,
            )
            assertEquals(
                3L,
                sampleEvent.providerDroppedTotal,
            )
            assertEquals(
                2L,
                sampleEvent.subscriptionDroppedTotal,
            )

            assertEquals(
                0,
                subscription.closeCalls,
            )

            registration.close()
            registration.join()
            collector.cancel()
        }

    @Test
    fun terminalClosedStateIsPublishedAndStopsBridgeWithoutClosingSubscription() =
        runTest {
            val eventBus =
                DieselEventBus()

            val bridge =
                SensorObservationEventBridge(
                    events =
                        eventBus,
                    wallClockMs = {
                        2_000L
                    },
                )

            val subscription =
                FakeSubscription(
                    initialState =
                        state(
                            phase =
                                SensorSubscriptionPhase
                                    .ACTIVE,
                            providerId =
                                "wear.health_services",
                        ),
                )

            val received =
                mutableListOf<
                    DieselEvent
                >()

            val collector =
                launch {
                    eventBus
                        .events
                        .collect {
                            received +=
                                it
                        }
                }

            runCurrent()

            val registration =
                bridge.attach(
                    subscription =
                        subscription,
                    scope =
                        this,
                )

            runCurrent()

            subscription.updateState(
                state(
                    phase =
                        SensorSubscriptionPhase
                            .CLOSED,
                    providerId =
                        "wear.health_services",
                ),
            )

            runCurrent()
            registration.join()

            assertEquals(
                2,
                received.size,
            )

            val terminal =
                received
                    .last() as
                    SensorObservationStateEvent

            assertEquals(
                SensorSubscriptionPhase
                    .CLOSED,
                terminal
                    .state
                    .phase,
            )
            assertTrue(
                registration.isClosed,
            )
            assertEquals(
                0,
                subscription.closeCalls,
            )
            assertTrue(
                !subscription.isClosed,
            )

            collector.cancel()
        }

    @Test
    fun closingRegistrationStopsForwardingWithoutClosingSubscription() =
        runTest {
            val eventBus =
                DieselEventBus()

            val bridge =
                SensorObservationEventBridge(
                    events =
                        eventBus,
                    wallClockMs = {
                        3_000L
                    },
                )

            val subscription =
                FakeSubscription(
                    initialState =
                        state(
                            phase =
                                SensorSubscriptionPhase
                                    .WAITING_FOR_PROVIDER,
                        ),
                )

            val received =
                mutableListOf<
                    DieselEvent
                >()

            val collector =
                launch {
                    eventBus
                        .events
                        .collect {
                            received +=
                                it
                        }
                }

            runCurrent()

            val registration =
                bridge.attach(
                    subscription =
                        subscription,
                    scope =
                        this,
                )

            runCurrent()

            assertEquals(
                1,
                received.size,
            )

            registration.close()
            registration.join()
            runCurrent()

            subscription.updateState(
                state(
                    phase =
                        SensorSubscriptionPhase
                            .ACTIVE,
                    providerId =
                        "wear.health_services",
                ),
            )

            subscription.emitSample(
                sample(),
            )

            runCurrent()

            assertEquals(
                1,
                received.size,
            )
            assertEquals(
                0,
                subscription.closeCalls,
            )
            assertTrue(
                !subscription.isClosed,
            )

            collector.cancel()
        }

    @Test
    fun currentStateIsPublishedBeforeSampleWhenBothBecomeReadyTogether() =
        runTest {
            val eventBus =
                DieselEventBus()

            var now =
                4_000L

            val bridge =
                SensorObservationEventBridge(
                    events =
                        eventBus,
                    wallClockMs = {
                        now++
                    },
                )

            val subscription =
                FakeSubscription(
                    initialState =
                        state(
                            phase =
                                SensorSubscriptionPhase
                                    .WAITING_FOR_PROVIDER,
                        ),
                )

            val received =
                mutableListOf<
                    DieselEvent
                >()

            val collector =
                launch {
                    eventBus
                        .events
                        .collect {
                            received +=
                                it
                        }
                }

            runCurrent()

            val registration =
                bridge.attach(
                    subscription =
                        subscription,
                    scope =
                        this,
                )

            runCurrent()

            /*
             * Do not yield between making ACTIVE visible and emitting the
             * first sample. The bridge must still serialize EventBus output
             * as WAITING -> ACTIVE -> sample.
             */
            subscription.updateState(
                state(
                    phase =
                        SensorSubscriptionPhase
                            .ACTIVE,
                    providerId =
                        "wear.health_services",
                ),
            )

            subscription.emitSample(
                sample(),
            )

            runCurrent()

            assertEquals(
                3,
                received.size,
            )

            assertEquals(
                SensorSubscriptionPhase
                    .ACTIVE,
                (
                    received[
                        1
                    ] as
                        SensorObservationStateEvent
                )
                    .state
                    .phase,
            )

            assertTrue(
                received[
                    2
                ] is
                    SensorObservationSampleEvent,
            )

            registration.close()
            registration.join()
            collector.cancel()
        }

    @Test
    fun queuedSampleIsNotPublishedAfterTerminalClosedState() =
        runTest {
            val eventBus =
                DieselEventBus()

            val bridge =
                SensorObservationEventBridge(
                    events =
                        eventBus,
                    wallClockMs = {
                        4_500L
                    },
                )

            val subscription =
                FakeSubscription(
                    initialState =
                        state(
                            phase =
                                SensorSubscriptionPhase
                                    .ACTIVE,
                            providerId =
                                "wear.health_services",
                        ),
                )

            val received =
                mutableListOf<
                    DieselEvent
                >()

            val collector =
                launch {
                    eventBus
                        .events
                        .collect {
                            received +=
                                it
                        }
                }

            runCurrent()

            val registration =
                bridge.attach(
                    subscription =
                        subscription,
                    scope =
                        this,
                )

            runCurrent()

            /*
             * Queue a sample, then make CLOSED visible before the scheduler
             * gives the bridge a chance to forward that sample.
             */
            subscription.emitSample(
                sample(),
            )

            subscription.updateState(
                state(
                    phase =
                        SensorSubscriptionPhase
                            .CLOSED,
                ),
            )

            runCurrent()
            registration.join()

            assertEquals(
                2,
                received.size,
            )

            assertTrue(
                received.none {
                    it is
                        SensorObservationSampleEvent
                },
            )

            val terminal =
                received
                    .last() as
                    SensorObservationStateEvent

            assertEquals(
                SensorSubscriptionPhase
                    .CLOSED,
                terminal
                    .state
                    .phase,
            )

            assertEquals(
                0,
                subscription.closeCalls,
            )

            collector.cancel()
        }

    @Test
    fun cancellingCallerScopeStopsForwardingWithoutClosingSubscription() =
        runTest {
            val eventBus =
                DieselEventBus()

            val bridge =
                SensorObservationEventBridge(
                    events =
                        eventBus,
                    wallClockMs = {
                        5_000L
                    },
                )

            val subscription =
                FakeSubscription(
                    initialState =
                        state(
                            phase =
                                SensorSubscriptionPhase
                                    .WAITING_FOR_PROVIDER,
                        ),
                )

            val received =
                mutableListOf<
                    DieselEvent
                >()

            val collector =
                launch {
                    eventBus
                        .events
                        .collect {
                            received +=
                                it
                        }
                }

            runCurrent()

            val ownerJob =
                SupervisorJob(
                    coroutineContext[
                        Job
                    ],
                )

            val ownerScope =
                CoroutineScope(
                    coroutineContext +
                        ownerJob,
                )

            val registration =
                bridge.attach(
                    subscription =
                        subscription,
                    scope =
                        ownerScope,
                )

            runCurrent()

            assertEquals(
                1,
                received.size,
            )

            ownerJob.cancel()
            registration.join()
            runCurrent()

            subscription.updateState(
                state(
                    phase =
                        SensorSubscriptionPhase
                            .ACTIVE,
                    providerId =
                        "wear.health_services",
                ),
            )

            subscription.emitSample(
                sample(),
            )

            runCurrent()

            assertEquals(
                1,
                received.size,
            )
            assertTrue(
                registration.isClosed,
            )
            assertEquals(
                0,
                subscription.closeCalls,
            )
            assertTrue(
                !subscription.isClosed,
            )

            collector.cancel()
        }

    private fun state(
        phase: SensorSubscriptionPhase,
        providerId: String? = null,
        providerDroppedTotal: Long = 0L,
        subscriptionDroppedTotal: Long = 0L,
    ): SensorSubscriptionState =
        SensorSubscriptionState(
            subscriptionId =
                7L,
            logicalId =
                "heart_rate",
            phase =
                phase,
            providerId =
                providerId,
            requestedPeriodMs =
                1_000L,
            acquisitionPeriodMs =
                providerId
                    ?.let {
                        1_000L
                    },
            providerEffectivePeriodMs =
                null,
            reason =
                null,
            providerConfiguredPeriodMs =
                null,
            sourceDroppedTotal =
                providerDroppedTotal,
            subscriptionDroppedTotal =
                subscriptionDroppedTotal,
        )

    private fun sample(
        providerDroppedTotal: Long = 0L,
        subscriptionDroppedTotal: Long = 0L,
    ): SensorSubscriptionSample =
        SensorSubscriptionSample(
            sequence =
                11L,
            reading =
                SensorReading(
                    capabilityId =
                        SensorCapabilityId(
                            "sensor.heart_rate",
                        ),
                    providerId =
                        "wear.health_services",
                    values =
                        listOf(
                            77.0F,
                        ),
                    timestampNanos =
                        123_456L,
                    accuracy =
                        3,
                    elapsedMs =
                        12L,
                ),
            droppedTotal =
                subscriptionDroppedTotal,
            sourceDroppedTotal =
                providerDroppedTotal,
        )
}
