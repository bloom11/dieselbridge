// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationManager
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselPublicObservationApiTest {
    private val binding = DieselPublicObservationBinding(
        DieselResourceDescriptor(
            "sensor.observe.accelerometer",
            DieselResourceKind.OBSERVATION,
            "sensor.reading.v1",
            setOf(DieselResourceOperation.SUBSCRIBE),
            "Test accelerometer",
        ),
        "accelerometer",
    )

    @Test fun usesExistingManagerClient() = runTest {
        val manager = SensorObservationManager(CapabilityRegistry(), backgroundScope)
        val client = manager.openClient("public-test")
        val api = DieselPublicApi(observationBindings = listOf(binding))
        val result = api.openObservation(client, binding.descriptor.id, 100L)
        assertTrue(result is DieselPublicObservationOpenResult.Opened)
        result as DieselPublicObservationOpenResult.Opened
        assertEquals("accelerometer", result.subscription.logicalId)
        assertEquals(SensorSubscriptionPhase.WAITING_FOR_PROVIDER, result.subscription.state.value.phase)
        client.close()
        manager.closeAndJoin()
    }

    @Test fun appliesStricterPublicPeriodLimit() = runTest {
        val manager = SensorObservationManager(CapabilityRegistry(), backgroundScope)
        val client = manager.openClient("public-test")
        val api = DieselPublicApi(observationBindings = listOf(binding))
        assertEquals(
            DieselPublicObservationOpenResult.Rejected("period_out_of_bounds"),
            api.openObservation(client, binding.descriptor.id, 1L),
        )
        client.close()
        manager.closeAndJoin()
    }
}
