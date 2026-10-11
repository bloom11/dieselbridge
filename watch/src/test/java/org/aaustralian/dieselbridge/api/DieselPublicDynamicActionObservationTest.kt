// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.action.DieselActionDispatcher
import org.aaustralian.dieselbridge.platform.action.DieselActionKey
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationManager
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselPublicDynamicActionObservationTest {
    @Test
    fun dynamicActionDispatchesThroughExistingDispatcher() =
        runTest {
            val actions = DieselActionDispatcher()
            val api = DieselPublicApi(dynamicActions = actions)

            val descriptor =
                DieselResourceDescriptor(
                    "plugin.example.client.echo",
                    DieselResourceKind.ACTION,
                    "plugin.example.client.echo.v1",
                    setOf(
                        DieselResourceOperation.INVOKE,
                        DieselResourceOperation.PROVIDE,
                    ),
                    "Echo action",
                )

            val catalogue = api.retainDynamicDescriptor(descriptor)
            val registration =
                actions.register(
                    DieselActionKey(
                        descriptor.id,
                        DieselValue::class.java,
                        DieselValue::class.java,
                    ),
                    "test",
                ) {
                    DieselActionResult.Success(it)
                }

            assertEquals(
                DieselPublicActionResult.Success(
                    DieselValue.Text("hello"),
                ),
                api.invokeAction(
                    descriptor.id,
                    DieselValue.Text("hello"),
                ),
            )

            registration.close()
            catalogue.close()
        }

    @Test
    fun dynamicObservationStillOpensThroughObservationManager() =
        runTest {
            val registry = CapabilityRegistry()
            val manager =
                SensorObservationManager(
                    registry,
                    backgroundScope,
                )
            val client = manager.openClient("public-dynamic-test")
            val api = DieselPublicApi()

            val descriptor =
                DieselResourceDescriptor(
                    "sensor.observe.plugin.example.client.synthetic",
                    DieselResourceKind.OBSERVATION,
                    "sensor.reading.v1",
                    setOf(
                        DieselResourceOperation.SUBSCRIBE,
                        DieselResourceOperation.PROVIDE,
                    ),
                    "Synthetic observation",
                )

            val catalogue = api.retainDynamicDescriptor(descriptor)
            val opened =
                api.openObservation(
                    client,
                    descriptor.id,
                    100L,
                )

            assertTrue(
                opened is DieselPublicObservationOpenResult.Opened,
            )
            opened as DieselPublicObservationOpenResult.Opened
            assertEquals(
                "plugin.example.client.synthetic",
                opened.subscription.logicalId,
            )

            client.close()
            catalogue.close()
            manager.closeAndJoin()
        }
}
