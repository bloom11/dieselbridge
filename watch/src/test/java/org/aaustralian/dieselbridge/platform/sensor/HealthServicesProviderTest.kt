package org.aaustralian.dieselbridge.platform.sensor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthServicesProviderTest {
    @Test
    fun exposesOnly_supported_health_capabilities() = runBlocking {
        val source = object : HealthServicesSource {
            override suspend fun supports(logicalId: String) = logicalId == "heart_rate"
            override suspend fun read(logicalId: String, timeoutMs: Long) = HealthServicesSample(listOf(72f), 42L)
        }
        val provider = HealthServicesProvider(source)
        val heartRate = provider.capabilities().first { it.capabilityId.value == "sensor.heart_rate" }
        val steps = provider.capabilities().first { it.capabilityId.value == "sensor.step_counter" }

        val heartResult = heartRate.read(SensorReadOptions(1_000L))
        assertTrue(heartResult is SensorReadResult.Event)
        assertEquals("wear.health_services", (heartResult as SensorReadResult.Event).reading.providerId)
        assertEquals(42L, heartResult.reading.timestampNanos)
        assertTrue(steps.read(SensorReadOptions(1_000L)) is SensorReadResult.Unavailable)
    }
}
