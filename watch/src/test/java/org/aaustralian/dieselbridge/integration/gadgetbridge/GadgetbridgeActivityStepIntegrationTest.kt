// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.integration.gadgetbridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GadgetbridgeActivityStepIntegrationTest {

    private class FakeProvider(
        override val providerId: String,
    ) : DieselProvider

    private class FakeStepCapability(
        private val providerId: String,
    ) : SensorObservationCapability {

        override val capabilityId =
            SensorObservationCapabilityId
                .forLogical(
                    "step_counter",
                )

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
                    sample(
                        raw = 1_000F,
                        timestampNanos = 1_000_000L,
                    ),
                )

                delay(1_000L)

                emit(
                    sample(
                        raw = 1_005F,
                        timestampNanos = 2_000_000L,
                    ),
                )

                awaitCancellation()
            }

        private fun sample(
            raw: Float,
            timestampNanos: Long,
        ): SensorObservationUpdate.Sample =
            SensorObservationUpdate.Sample(
                reading =
                    SensorReading(
                        capabilityId =
                            SensorCapabilityId(
                                "sensor.step_counter",
                            ),
                        providerId =
                            providerId,
                        values =
                            listOf(raw),
                        timestampNanos =
                            timestampNanos,
                        accuracy = 3,
                        elapsedMs = 0L,
                    ),
            )
    }

    @Test
    fun stepOnlySessionReportsDeltaAndRetriesRejectedDelta() =
        runTest {
            val registry =
                CapabilityRegistry()

            val provider =
                FakeProvider(
                    "fake.steps",
                )

            registry.register(
                capability =
                    FakeStepCapability(
                        provider.providerId,
                    ),
                provider =
                    provider,
                priority =
                    10,
            )

            val manager =
                SensorObservationManager(
                    registry = registry,
                    scope = this,
                    monotonicMs = {
                        testScheduler.currentTime
                    },
                )

            val bus =
                DieselEventBus()

            val lines =
                mutableListOf<String>()

            var accept =
                false

            val session =
                GadgetbridgeActivitySessionController(
                    manager = manager,
                    bridge =
                        SensorObservationEventBridge(
                            bus,
                        ),
                    eventBus = bus,
                    transport =
                        BangleLineTransport {
                            line ->
                            lines += line
                            accept
                        },
                    scope = this,
                    wallClockMs = {
                        1_700_000_000_000L +
                            testScheduler.currentTime
                    },
                )

            assertTrue(
                session.apply(
                    GbMessage.ActivityControl(
                        heartRate = false,
                        steps = true,
                        intervalSeconds = 2,
                    ),
                ),
            )

            runCurrent()

            assertEquals(
                1_000L,
                session.state.value.stepBaselineRaw,
            )
            assertEquals(
                0,
                session.state.value.pendingStepDelta,
            )

            val subscriptionId =
                session.state.value.stepSubscriptionId

            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(
                1_005L,
                session.state.value.latestStepCounterRaw,
            )
            assertEquals(
                5,
                session.state.value.pendingStepDelta,
            )

            advanceTimeBy(1_000L)
            runCurrent()

            assertEquals(1, lines.size)

            val rejected =
                JSONObject(lines[0])

            assertEquals(0, rejected.getInt("hrm"))
            assertEquals(5, rejected.getInt("stp"))
            assertEquals(1, rejected.getInt("rt"))

            assertEquals(
                1_000L,
                session.state.value.stepBaselineRaw,
            )
            assertEquals(
                5,
                session.state.value.pendingStepDelta,
            )
            assertEquals(
                1L,
                session.state.value.reportsRejected,
            )

            accept = true

            advanceTimeBy(2_000L)
            runCurrent()

            assertEquals(2, lines.size)

            val accepted =
                JSONObject(lines[1])

            assertEquals(5, accepted.getInt("stp"))
            assertEquals(
                1_005L,
                session.state.value.stepBaselineRaw,
            )
            assertEquals(
                0,
                session.state.value.pendingStepDelta,
            )
            assertEquals(
                1L,
                session.state.value.reportsQueued,
            )

            assertTrue(
                session.apply(
                    GbMessage.ActivityControl(
                        heartRate = false,
                        steps = true,
                        intervalSeconds = 5,
                    ),
                ),
            )

            assertEquals(
                subscriptionId,
                session.state.value.stepSubscriptionId,
            )
            assertEquals(
                1_005L,
                session.state.value.stepBaselineRaw,
            )

            session.close()
            manager.close()
        }
}
