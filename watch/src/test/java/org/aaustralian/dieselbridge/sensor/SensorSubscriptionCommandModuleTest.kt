// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationManager
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselEventTransport
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SensorSubscriptionCommandModuleTest {

    private data class Fixture(
        val registry: DieselCommandRegistry,
        val controller:
            PublicSensorSubscriptionController,
    )

    private fun kotlinx.coroutines.test.TestScope.fixture(
        limits:
            PublicSensorSubscriptionLimits =
            PublicSensorSubscriptionLimits(),
    ): Fixture {
        val capabilityRegistry =
            CapabilityRegistry()

        val observationManager =
            SensorObservationManager(
                registry =
                    capabilityRegistry,
                scope =
                    backgroundScope,
                monotonicMs = {
                    testScheduler
                        .currentTime
                },
            )

        val controller =
            PublicSensorSubscriptionController(
                client =
                    observationManager
                        .openClient(
                            "diesel-protocol-test",
                        ),
                eventTransport =
                    DieselEventTransport {
                        true
                    },
                scope =
                    backgroundScope,
                limits =
                    limits,
                monotonicMs = {
                    testScheduler
                        .currentTime
                },
            )

        val registry =
            DieselCommandRegistry()

        registry.install(
            SensorSubscriptionCommandModule(
                controller =
                    controller,
                monotonicMs = {
                    testScheduler
                        .currentTime
                },
            ),
        )

        return Fixture(
            registry =
                registry,
            controller =
                controller,
        )
    }

    @Test
    fun registersThreePublicSubscriptionCommands() =
        runTest {
            val fixture =
                fixture()

            assertEquals(
                listOf(
                    SensorSubscriptionCommandModule
                        .COMMAND_SENSOR_SUBSCRIBE,
                    SensorSubscriptionCommandModule
                        .COMMAND_SENSOR_UNSUBSCRIBE,
                    SensorSubscriptionCommandModule
                        .COMMAND_SENSOR_SUBSCRIPTIONS,
                ),
                fixture.registry
                    .commands(),
            )
        }

    @Test
    fun unknownLogicalTargetIsRejected() =
        runTest {
            val fixture =
                fixture()

            val result =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-1",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_SUBSCRIBE,
                            name =
                                "vendor_secret",
                        ),
                    )

            assertEquals(
                DieselResponseStatus
                    .UNKNOWN_TARGET,
                result.status,
            )
            assertEquals(
                "unknown_sensor_capability",
                (
                    result.data[
                        "reason"
                    ] as
                        DieselValue.Text
                ).value,
            )
        }

    @Test
    fun canonicalTargetCanOpenWhileProviderIsUnavailable() =
        runTest {
            val fixture =
                fixture()

            val result =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-2",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_SUBSCRIBE,
                            name =
                                "accelerometer",
                            args =
                                mapOf(
                                    "periodMs" to
                                        DieselValue.Integer(
                                            500L,
                                        ),
                                    "leaseMs" to
                                        DieselValue.Integer(
                                            10_000L,
                                        ),
                                ),
                        ),
                    )

            runCurrent()

            assertEquals(
                DieselResponseStatus.OK,
                result.status,
            )
            assertEquals(
                "sensor.accelerometer",
                (
                    result.data[
                        "capability"
                    ] as
                        DieselValue.Text
                ).value,
            )
            assertEquals(
                500L,
                (
                    result.data[
                        "requestedPeriodMs"
                    ] as
                        DieselValue.Integer
                ).value,
            )
            assertEquals(
                10_000L,
                (
                    result.data[
                        "leaseMs"
                    ] as
                        DieselValue.Integer
                ).value,
            )

            val snapshot =
                fixture.controller
                    .snapshots()
                    .single()

            assertEquals(
                "accelerometer",
                snapshot.logicalId,
            )
            assertEquals(
                org.aaustralian.dieselbridge
                    .platform.sensor.observation
                    .SensorSubscriptionPhase
                    .WAITING_FOR_PROVIDER,
                snapshot.phase,
            )
        }

    @Test
    fun invalidSubscribeArgumentsAreRejected() =
        runTest {
            val fixture =
                fixture()

            val wrongType =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-3",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_SUBSCRIBE,
                            name =
                                "accelerometer",
                            args =
                                mapOf(
                                    "periodMs" to
                                        DieselValue.Text(
                                            "fast",
                                        ),
                                ),
                        ),
                    )

            assertEquals(
                DieselResponseStatus
                    .INVALID_REQUEST,
                wrongType.status,
            )

            val outOfBounds =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-4",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_SUBSCRIBE,
                            name =
                                "accelerometer",
                            args =
                                mapOf(
                                    "periodMs" to
                                        DieselValue.Integer(
                                            249L,
                                        ),
                                ),
                        ),
                    )

            assertEquals(
                DieselResponseStatus
                    .INVALID_REQUEST,
                outOfBounds.status,
            )

            val unknownArgument =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-5",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_SUBSCRIBE,
                            name =
                                "accelerometer",
                            args =
                                mapOf(
                                    "routeId" to
                                        DieselValue.Text(
                                            "forbidden",
                                        ),
                                ),
                        ),
                    )

            assertEquals(
                DieselResponseStatus
                    .INVALID_REQUEST,
                unknownArgument.status,
            )
        }

    @Test
    fun remoteSubscriptionLimitMapsToRateLimited() =
        runTest {
            val fixture =
                fixture(
                    limits =
                        PublicSensorSubscriptionLimits(
                            maxSubscriptions =
                                1,
                        ),
                )

            val first =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-6",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_SUBSCRIBE,
                            name =
                                "accelerometer",
                        ),
                    )

            val second =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-7",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_SUBSCRIBE,
                            name =
                                "gyroscope",
                        ),
                    )

            assertEquals(
                DieselResponseStatus.OK,
                first.status,
            )
            assertEquals(
                DieselResponseStatus
                    .RATE_LIMITED,
                second.status,
            )
            assertEquals(
                "sensor_subscription_limit",
                (
                    second.data[
                        "reason"
                    ] as
                        DieselValue.Text
                ).value,
            )
        }

    @Test
    fun unsubscribeIsIdempotentAtProtocolBoundary() =
        runTest {
            val fixture =
                fixture()

            val opened =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-8",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_SUBSCRIBE,
                            name =
                                "light",
                        ),
                    )

            val subscriptionId =
                (
                    opened.data[
                        "subscriptionId"
                    ] as
                        DieselValue.Integer
                ).value

            val first =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-9",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_UNSUBSCRIBE,
                            args =
                                mapOf(
                                    "subscriptionId" to
                                        DieselValue.Integer(
                                            subscriptionId,
                                        ),
                                ),
                        ),
                    )

            val second =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-10",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_UNSUBSCRIBE,
                            args =
                                mapOf(
                                    "subscriptionId" to
                                        DieselValue.Integer(
                                            subscriptionId,
                                        ),
                                ),
                        ),
                    )

            assertEquals(
                DieselResponseStatus.OK,
                first.status,
            )
            assertEquals(
                DieselResponseStatus.OK,
                second.status,
            )

            assertTrue(
                (
                    first.data[
                        "closed"
                    ] as
                        DieselValue.Flag
                ).value,
            )
            assertTrue(
                (
                    second.data[
                        "closed"
                    ] as
                        DieselValue.Flag
                ).value,
            )

            assertTrue(
                (
                    first.data[
                        "wasActive"
                    ] as
                        DieselValue.Flag
                ).value,
            )
            assertEquals(
                false,
                (
                    second.data[
                        "wasActive"
                    ] as
                        DieselValue.Flag
                ).value,
            )
        }

    @Test
    fun subscriptionsListsOnlyPublicControllerState() =
        runTest {
            val fixture =
                fixture()

            fixture.registry
                .dispatch(
                    DieselRequest(
                        requestId =
                            "req-11",
                        command =
                            SensorSubscriptionCommandModule
                                .COMMAND_SENSOR_SUBSCRIBE,
                        name =
                            "pressure",
                        args =
                            mapOf(
                                "leaseMs" to
                                    DieselValue.Integer(
                                        10_000L,
                                    ),
                            ),
                    ),
                )

            runCurrent()

            val result =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-12",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_SUBSCRIPTIONS,
                        ),
                    )

            assertEquals(
                DieselResponseStatus.OK,
                result.status,
            )
            assertEquals(
                1L,
                (
                    result.data[
                        "count"
                    ] as
                        DieselValue.Integer
                ).value,
            )

            val entry =
                (
                    result.data[
                        "subscriptions"
                    ] as
                        DieselValue.ListValue
                )
                    .value
                    .single() as
                    DieselValue.ObjectValue

            assertEquals(
                "pressure",
                (
                    entry.value[
                        "target"
                    ] as
                        DieselValue.Text
                ).value,
            )
            assertEquals(
                "waiting_for_provider",
                (
                    entry.value[
                        "phase"
                    ] as
                        DieselValue.Text
                ).value,
            )
            assertEquals(
                10_000L,
                (
                    entry.value[
                        "expiresInMs"
                    ] as
                        DieselValue.Integer
                ).value,
            )
            assertEquals(
                0L,
                (
                    entry.value[
                        "droppedTotal"
                    ] as
                        DieselValue.Integer
                ).value,
            )
        }

    @Test
    fun unsubscribeAndListRejectMalformedRequests() =
        runTest {
            val fixture =
                fixture()

            val unsubscribe =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-13",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_UNSUBSCRIBE,
                            name =
                                "accelerometer",
                            args =
                                mapOf(
                                    "subscriptionId" to
                                        DieselValue.Integer(
                                            1L,
                                        ),
                                ),
                        ),
                    )

            val list =
                fixture.registry
                    .dispatch(
                        DieselRequest(
                            requestId =
                                "req-14",
                            command =
                                SensorSubscriptionCommandModule
                                    .COMMAND_SENSOR_SUBSCRIPTIONS,
                            args =
                                mapOf(
                                    "unexpected" to
                                        DieselValue.Integer(
                                            1L,
                                        ),
                                ),
                        ),
                    )

            assertEquals(
                DieselResponseStatus
                    .INVALID_REQUEST,
                unsubscribe.status,
            )
            assertEquals(
                DieselResponseStatus
                    .INVALID_REQUEST,
                list.status,
            )
        }
}
