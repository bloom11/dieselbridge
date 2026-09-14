// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import org.aaustralian.dieselbridge.protocol.DieselEvent
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PublicSensorSubscriptionControllerTest {

    private class FakeProvider(
        override val providerId: String,
    ) : DieselProvider

    private class FakeObservationCapability(
        private val logicalIdValue: String,
        private val providerId: String,
    ) : SensorObservationCapability {

        override val capabilityId =
            SensorObservationCapabilityId
                .forLogical(
                    logicalIdValue,
                )

        var cancellations =
            0
            private set

        private val updates =
            MutableSharedFlow<
                SensorObservationUpdate
            >(
                extraBufferCapacity =
                    32,
            )

        override fun observe(
            options: SensorObservationOptions,
        ): Flow<SensorObservationUpdate> =
            flow {
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
                                    "sensor.$logicalIdValue",
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

    private class CapturingTransport(
        private val accept:
            (DieselEvent) -> Boolean = {
                true
            },
    ) : org.aaustralian.dieselbridge.protocol.DieselEventTransport {

        val events =
            mutableListOf<DieselEvent>()

        override fun send(
            event: DieselEvent,
        ): Boolean {
            events +=
                event

            return accept(
                event,
            )
        }
    }

    private data class Fixture(
        val manager: SensorObservationManager,
        val capability: FakeObservationCapability,
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(
        logicalId: String =
            "accelerometer",
    ): Fixture {
        val registry =
            CapabilityRegistry()

        val capability =
            FakeObservationCapability(
                logicalIdValue =
                    logicalId,
                providerId =
                    "provider.test",
            )

        registry.register(
            capability =
                capability,
            provider =
                FakeProvider(
                    "provider.test",
                ),
            priority =
                10,
        )

        return Fixture(
            manager =
                SensorObservationManager(
                    registry =
                        registry,
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                ),
            capability =
                capability,
        )
    }

    @Test
    fun rejectsUnknownAndOutOfBoundsRequests() =
        runTest {
            val fixture =
                fixture()

            val transport =
                CapturingTransport()

            val controller =
                PublicSensorSubscriptionController(
                    client =
                        fixture.manager
                            .openClient(
                                "public",
                            ),
                    eventTransport =
                        transport,
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            assertEquals(
                PublicSensorSubscriptionRejectReason.UNKNOWN_SENSOR,
                (
                    controller.subscribe(
                        "vendor_secret",
                    ) as
                        PublicSensorSubscribeResult.Rejected
                ).reason,
            )

            assertEquals(
                PublicSensorSubscriptionRejectReason.PERIOD_OUT_OF_BOUNDS,
                (
                    controller.subscribe(
                        logicalId =
                            "accelerometer",
                        periodMs =
                            249L,
                    ) as
                        PublicSensorSubscribeResult.Rejected
                ).reason,
            )

            assertEquals(
                PublicSensorSubscriptionRejectReason.LEASE_OUT_OF_BOUNDS,
                (
                    controller.subscribe(
                        logicalId =
                            "accelerometer",
                        leaseMs =
                            4_999L,
                    ) as
                        PublicSensorSubscribeResult.Rejected
                ).reason,
            )

            assertTrue(
                controller
                    .snapshots()
                    .isEmpty(),
            )
        }

    @Test
    fun enforcesSmallRemoteSubscriptionLimit() =
        runTest {
            val fixture =
                fixture()

            val controller =
                PublicSensorSubscriptionController(
                    client =
                        fixture.manager
                            .openClient(
                                "public",
                            ),
                    eventTransport =
                        CapturingTransport(),
                    scope =
                        backgroundScope,
                    limits =
                        PublicSensorSubscriptionLimits(
                            maxSubscriptions =
                                2,
                        ),
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            assertTrue(
                controller.subscribe(
                    "accelerometer",
                ) is
                    PublicSensorSubscribeResult.Opened,
            )
            assertTrue(
                controller.subscribe(
                    "accelerometer",
                ) is
                    PublicSensorSubscribeResult.Opened,
            )

            val third =
                controller.subscribe(
                    "accelerometer",
                )

            assertEquals(
                PublicSensorSubscriptionRejectReason
                    .SUBSCRIPTION_LIMIT_REACHED,
                (
                    third as
                        PublicSensorSubscribeResult.Rejected
                ).reason,
            )
            assertEquals(
                2,
                controller
                    .snapshots()
                    .size,
            )
        }

    @Test
    fun publishesStateAndSampleEvents() =
        runTest {
            val fixture =
                fixture()

            val transport =
                CapturingTransport()

            val controller =
                PublicSensorSubscriptionController(
                    client =
                        fixture.manager
                            .openClient(
                                "public",
                            ),
                    eventTransport =
                        transport,
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            val opened =
                controller.subscribe(
                    logicalId =
                        "accelerometer",
                    periodMs =
                        500L,
                    leaseMs =
                        10_000L,
                ) as
                    PublicSensorSubscribeResult.Opened

            runCurrent()

            fixture.capability
                .emitSample(
                    3.5f,
                )

            runCurrent()

            assertTrue(
                transport.events
                    .any {
                        it.topic ==
                            PublicSensorSubscriptionController
                                .TOPIC_SENSOR_STATE
                    },
            )

            val sampleEvent =
                transport.events
                    .last {
                        it.topic ==
                            PublicSensorSubscriptionController
                                .TOPIC_SENSOR_SAMPLE
                    }

            assertEquals(
                opened.snapshot
                    .subscriptionId,
                (
                    sampleEvent
                        .data[
                            "subscriptionId"
                        ] as
                        DieselValue.Integer
                ).value,
            )

            assertEquals(
                "sensor.accelerometer",
                (
                    sampleEvent
                        .data[
                            "capability"
                        ] as
                        DieselValue.Text
                ).value,
            )

            assertEquals(
                0L,
                (
                    sampleEvent
                        .data[
                            "transportDroppedTotal"
                        ] as
                        DieselValue.Integer
                ).value,
            )
        }

    @Test
    fun transportRejectionIsCountedAndReportedByNextSample() =
        runTest {
            val fixture =
                fixture()

            var rejectNextSample =
                true

            val transport =
                CapturingTransport {
                        event,
                    ->
                    if (
                        event.topic ==
                        PublicSensorSubscriptionController
                            .TOPIC_SENSOR_SAMPLE &&
                        rejectNextSample
                    ) {
                        rejectNextSample =
                            false
                        false
                    } else {
                        true
                    }
                }

            val controller =
                PublicSensorSubscriptionController(
                    client =
                        fixture.manager
                            .openClient(
                                "public",
                            ),
                    eventTransport =
                        transport,
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            controller.subscribe(
                "accelerometer",
            )

            runCurrent()

            fixture.capability
                .emitSample(
                    1.0f,
                )
            runCurrent()

            fixture.capability
                .emitSample(
                    2.0f,
                )
            runCurrent()

            val sampleEvents =
                transport.events
                    .filter {
                        it.topic ==
                            PublicSensorSubscriptionController
                                .TOPIC_SENSOR_SAMPLE
                    }

            assertEquals(
                2,
                sampleEvents.size,
            )

            assertEquals(
                1L,
                (
                    sampleEvents
                        .last()
                        .data[
                            "transportDroppedTotal"
                        ] as
                        DieselValue.Integer
                ).value,
            )

            assertEquals(
                1L,
                controller
                    .snapshots()
                    .single()
                    .transportDroppedTotal,
            )
        }

    @Test
    fun leaseExpiryClosesSubscriptionAndReleasesProvider() =
        runTest {
            val fixture =
                fixture()

            val transport =
                CapturingTransport()

            val controller =
                PublicSensorSubscriptionController(
                    client =
                        fixture.manager
                            .openClient(
                                "public",
                            ),
                    eventTransport =
                        transport,
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            val opened =
                controller.subscribe(
                    logicalId =
                        "accelerometer",
                    leaseMs =
                        5_000L,
                ) as
                    PublicSensorSubscribeResult.Opened

            runCurrent()

            assertEquals(
                1,
                controller
                    .snapshots()
                    .size,
            )

            advanceTimeBy(
                5_001L,
            )
            runCurrent()

            assertTrue(
                controller
                    .snapshots()
                    .isEmpty(),
            )
            assertTrue(
                fixture.capability
                    .cancellations >
                    0,
            )

            val closed =
                transport.events
                    .last {
                        it.topic ==
                            PublicSensorSubscriptionController
                                .TOPIC_SENSOR_STATE &&
                            (
                                it.data[
                                    "subscriptionId"
                                ] as
                                    DieselValue.Integer
                            ).value ==
                            opened.snapshot
                                .subscriptionId
                    }

            assertEquals(
                "closed",
                (
                    closed.data[
                        "phase"
                    ] as
                        DieselValue.Text
                ).value,
            )
            assertEquals(
                "lease_expired",
                (
                    closed.data[
                        "reason"
                    ] as
                        DieselValue.Text
                ).value,
            )
        }

    @Test
    fun unsubscribeIsIdempotentAtControllerBoundary() =
        runTest {
            val fixture =
                fixture()

            val controller =
                PublicSensorSubscriptionController(
                    client =
                        fixture.manager
                            .openClient(
                                "public",
                            ),
                    eventTransport =
                        CapturingTransport(),
                    scope =
                        backgroundScope,
                    monotonicMs = {
                        testScheduler
                            .currentTime
                    },
                )

            val opened =
                controller.subscribe(
                    "accelerometer",
                ) as
                    PublicSensorSubscribeResult.Opened

            assertTrue(
                controller.unsubscribe(
                    opened.snapshot
                        .subscriptionId,
                ),
            )

            assertFalse(
                controller.unsubscribe(
                    opened.snapshot
                        .subscriptionId,
                ),
            )
        }
}
