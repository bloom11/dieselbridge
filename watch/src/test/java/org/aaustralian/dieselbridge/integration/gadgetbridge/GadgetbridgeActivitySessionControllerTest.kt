// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.integration.gadgetbridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.SensorReading
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapability
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventBridge
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationManager
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationOptions
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationUpdate
import org.aaustralian.dieselbridge.protocol.GbMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GadgetbridgeActivitySessionControllerTest {

    private class FakeProvider(
        override val providerId: String,
    ) : DieselProvider

    private class FakeHeartRateCapability(
        private val providerId: String,
        private val bpm: Float,
    ) : SensorObservationCapability {

        override val capabilityId =
            SensorObservationCapabilityId
                .forLogical("heart_rate")

        override fun observe(
            options: SensorObservationOptions,
        ): Flow<SensorObservationUpdate> =
            flow {
                emit(
                    SensorObservationUpdate.Started(
                        configuredSamplePeriodMs =
                            options.preferredSamplePeriodMs,
                    ),
                )
                emit(
                    SensorObservationUpdate.Sample(
                        reading =
                            SensorReading(
                                capabilityId =
                                    SensorCapabilityId("sensor.heart_rate"),
                                providerId =
                                    providerId,
                                values =
                                    listOf(bpm),
                                timestampNanos =
                                    123_456_789L,
                                accuracy =
                                    3,
                                elapsedMs =
                                    0L,
                            ),
                    ),
                )
                awaitCancellation()
            }
    }

    private data class Fixture(
        val manager: SensorObservationManager,
        val session: GadgetbridgeActivitySessionController,
        val lines: MutableList<String>,
    )

    private fun fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        now: () -> Long,
        acceptTransport: Boolean = true,
    ): Fixture {
        val registry = CapabilityRegistry()
        val provider = FakeProvider("fake.health")

        registry.register(
            capability =
                FakeHeartRateCapability(
                    providerId =
                        provider.providerId,
                    bpm =
                        76.4F,
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
                    scope,
            )

        val bus = DieselEventBus()
        val lines = mutableListOf<String>()

        val session =
            GadgetbridgeActivitySessionController(
                manager =
                    manager,
                bridge =
                    SensorObservationEventBridge(bus),
                eventBus =
                    bus,
                transport =
                    BangleLineTransport {
                        line ->
                        lines += line
                        acceptTransport
                    },
                scope =
                    scope,
                wallClockMs =
                    now,
            )

        return Fixture(
            manager =
                manager,
            session =
                session,
            lines =
                lines,
        )
    }

    @Test
    fun enableReconfigureDisableReconnectAndClose() =
        runTest {
            val fixture =
                fixture(
                    scope = this,
                    now = {
                        testScheduler.currentTime
                    },
                )
            val session = fixture.session

            assertTrue(
                session.apply(
                    GbMessage.ActivityControl(true, true, 10),
                ),
            )

            val first = session.state.value
            val hr = requireNotNull(first.heartRateSubscriptionId)
            val stp = requireNotNull(first.stepSubscriptionId)
            assertNotEquals(hr, stp)

            assertTrue(
                session.apply(
                    GbMessage.ActivityControl(true, true, 20),
                ),
            )
            assertEquals(hr, session.state.value.heartRateSubscriptionId)
            assertEquals(stp, session.state.value.stepSubscriptionId)
            assertEquals(20, session.state.value.intervalSeconds)

            assertTrue(
                session.apply(
                    GbMessage.ActivityControl(false, true, 20),
                ),
            )
            assertNull(session.state.value.heartRateSubscriptionId)
            assertEquals(stp, session.state.value.stepSubscriptionId)

            session.disable("ble_disconnected")
            assertFalse(session.state.value.enabled)
            assertEquals(
                "ble_disconnected",
                session.state.value.lastStopReason,
            )

            assertTrue(
                session.apply(
                    GbMessage.ActivityControl(true, false, 10),
                ),
            )
            assertNotNull(session.state.value.heartRateSubscriptionId)
            assertNotEquals(
                hr,
                session.state.value.heartRateSubscriptionId,
            )

            assertTrue(
                session.apply(
                    GbMessage.ActivityControl(false, false, 0),
                ),
            )
            assertFalse(session.state.value.enabled)

            session.close()
            session.close()
            assertFalse(
                session.apply(
                    GbMessage.ActivityControl(true, false, 10),
                ),
            )
            fixture.manager.close()
        }

    @Test
    fun latestHeartRateIsReportedAtPhoneIntervalWithoutResubscribe() =
        runTest {
            val fixture =
                fixture(
                    scope = this,
                    now = {
                        1_700_000_000_000L +
                            testScheduler.currentTime
                    },
                )

            val session = fixture.session

            assertTrue(
                session.apply(
                    GbMessage.ActivityControl(
                        heartRate = true,
                        steps = false,
                        intervalSeconds = 2,
                    ),
                ),
            )

            runCurrent()

            assertEquals(76, session.state.value.latestHeartRateBpm)
            assertEquals(
                "fake.health",
                session.state.value.heartRateProviderId,
            )

            val originalSubscription =
                requireNotNull(
                    session.state.value.heartRateSubscriptionId,
                )

            advanceTimeBy(1_999L)
            runCurrent()
            assertTrue(fixture.lines.isEmpty())

            advanceTimeBy(1L)
            runCurrent()

            assertEquals(1, fixture.lines.size)

            val report =
                JSONObject(
                    fixture.lines.single(),
                )

            assertEquals("act", report.getString("t"))
            assertEquals(76, report.getInt("hrm"))
            assertEquals(0, report.getInt("stp"))
            assertEquals(1, report.getInt("rt"))

            assertEquals(1L, session.state.value.reportsAttempted)
            assertEquals(1L, session.state.value.reportsQueued)
            assertEquals(0L, session.state.value.reportsRejected)

            assertTrue(
                session.apply(
                    GbMessage.ActivityControl(
                        heartRate = true,
                        steps = false,
                        intervalSeconds = 5,
                    ),
                ),
            )

            assertEquals(
                originalSubscription,
                session.state.value.heartRateSubscriptionId,
            )

            fixture.session.close()
            fixture.manager.close()
        }

    @Test
    fun transportRejectionIsCountedAndDisableStopsReports() =
        runTest {
            val fixture =
                fixture(
                    scope = this,
                    now = {
                        testScheduler.currentTime
                    },
                    acceptTransport = false,
                )

            val session = fixture.session

            assertTrue(
                session.apply(
                    GbMessage.ActivityControl(
                        heartRate = true,
                        steps = false,
                        intervalSeconds = 1,
                    ),
                ),
            )

            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(1L, session.state.value.reportsAttempted)
            assertEquals(0L, session.state.value.reportsQueued)
            assertEquals(1L, session.state.value.reportsRejected)

            session.disable("ble_disconnected")

            advanceTimeBy(10_000L)
            runCurrent()

            assertEquals(1, fixture.lines.size)

            fixture.session.close()
            fixture.manager.close()
        }
}
