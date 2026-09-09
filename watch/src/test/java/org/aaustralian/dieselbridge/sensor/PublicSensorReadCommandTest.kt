// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import kotlinx.coroutines.runBlocking
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.sensor.SensorCapability
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.SensorReadOptions
import org.aaustralian.dieselbridge.platform.sensor.SensorReadResult
import org.aaustralian.dieselbridge.platform.sensor.SensorReading
import org.aaustralian.dieselbridge.platform.sensor.SensorInventory
import org.aaustralian.dieselbridge.platform.sensor.SensorManagerRouteCatalog
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicSensorReadCommandTest {
    private class FakeSensor(
        override val capabilityId: SensorCapabilityId,
        private val result: SensorReadResult,
    ) : SensorCapability {
        override val id = capabilityId.value
        override suspend fun read(options: SensorReadOptions) = result
    }

    private val routes = SensorManagerRouteCatalog(
        object : SensorInventory {
            override fun snapshot() = emptyList<org.aaustralian.dieselbridge.platform.sensor.SensorInventoryEntry>()
        },
    )

    private fun registry(capability: SensorCapability? = null): DieselCommandRegistry {
        val capabilities = CapabilityRegistry()
        capability?.let {
            capabilities.register(it, object : DieselProvider { override val providerId = "fake" })
        }
        return DieselCommandRegistry().apply {
            install(SensorCommandModule(routes, capabilities))
        }
    }

    private fun request(name: String? = "accelerometer", timeout: DieselValue? = null, extra: Boolean = false) =
        DieselRequest(
            requestId = "read",
            command = "sensor.read",
            name = name,
            args = buildMap {
                timeout?.let { put("timeoutMs", it) }
                if (extra) put("routeId", DieselValue.Text("must-not-be-accepted"))
            },
        )

    @Test
    fun logicalReadNeverRequiresRouteIdAndReturnsRawEvent() = runBlocking {
        val result = registry(
            FakeSensor(
                SensorCapabilityId("sensor.accelerometer"),
                SensorReadResult.Event(
                    SensorReading(
                        SensorCapabilityId("sensor.accelerometer"), "fake", listOf(1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY), 42L, 3, 9L,
                    ),
                ),
            ),
        ).dispatch(request())
        assertEquals(DieselResponseStatus.OK, result.status)
        assertEquals(DieselValue.Text("event"), result.data["outcome"])
        assertEquals(DieselValue.Text("sensor.accelerometer"), result.data["capability"])
        assertEquals(DieselValue.Text("fake"), result.data["providerId"])
        assertEquals(DieselValue.Integer(4), result.data["valueCount"])
        val values = (result.data["values"] as DieselValue.ListValue).value
        assertEquals(listOf(DieselValue.Decimal(1.0), DieselValue.Null, DieselValue.Null, DieselValue.Null), values)
        val nonFinite = (result.data["nonFinite"] as DieselValue.ObjectValue).value
        assertEquals(DieselValue.ListValue(listOf(DieselValue.Integer(1))), nonFinite["nan"])
        assertEquals(DieselValue.ListValue(listOf(DieselValue.Integer(2))), nonFinite["positiveInfinity"])
        assertEquals(DieselValue.ListValue(listOf(DieselValue.Integer(3))), nonFinite["negativeInfinity"])
        assertFalse(result.data.containsKey("routeId"))
    }

    @Test
    fun unknownAndUnavailableTargetsHaveDistinctStatuses() = runBlocking {
        val unknown = registry().dispatch(request("not_a_sensor"))
        assertEquals(DieselResponseStatus.UNKNOWN_TARGET, unknown.status)
        val unavailable = registry().dispatch(request("accelerometer"))
        assertEquals(DieselResponseStatus.UNAVAILABLE, unavailable.status)
        val knownUnavailable = registry(
            FakeSensor(SensorCapabilityId("sensor.accelerometer"), SensorReadResult.Unavailable),
        ).dispatch(request())
        assertEquals(DieselResponseStatus.UNAVAILABLE, knownUnavailable.status)
    }

    @Test
    fun allStandardLogicalTargetsReportUnavailableWithoutAProvider() = runBlocking {
        val targets = listOf(
            "accelerometer",
            "gyroscope",
            "magnetic_field",
            "light",
            "pressure",
            "ambient_temperature",
            "heart_rate",
            "step_counter",
        )
        targets.forEach { target ->
            val result = registry().dispatch(request(target))
            assertEquals("target=$target", DieselResponseStatus.UNAVAILABLE, result.status)
        }
    }

    @Test
    fun validationRejectsNameMissingWrongTimeoutAndRouteSelection() = runBlocking {
        val sensor = FakeSensor(SensorCapabilityId("sensor.accelerometer"), SensorReadResult.Timeout)
        val registry = registry(sensor)
        assertEquals(DieselResponseStatus.INVALID_REQUEST, registry.dispatch(request(null)).status)
        assertEquals(DieselResponseStatus.INVALID_REQUEST, registry.dispatch(request(timeout = DieselValue.Text("5000"))).status)
        assertEquals(DieselResponseStatus.INVALID_REQUEST, registry.dispatch(request(timeout = DieselValue.Integer(499))).status)
        assertEquals(DieselResponseStatus.INVALID_REQUEST, registry.dispatch(request(extra = true)).status)
        val timeout = registry.dispatch(request(timeout = DieselValue.Integer(15000)))
        assertEquals(DieselResponseStatus.OK, timeout.status)
        assertEquals(DieselValue.Text("timeout"), timeout.data["outcome"])
    }

    @Test
    fun permissionAndRegistrationOutcomesRemainOperationalResults() = runBlocking {
        val registry = registry(
            FakeSensor(SensorCapabilityId("sensor.accelerometer"), SensorReadResult.PermissionDenied(null)),
        )
        val denied = registry.dispatch(request())
        assertEquals(DieselResponseStatus.OK, denied.status)
        assertEquals(DieselValue.Text("permission_denied"), denied.data["outcome"])
        val rejected = this@PublicSensorReadCommandTest.registry(
            FakeSensor(SensorCapabilityId("sensor.accelerometer"), SensorReadResult.RegistrationRejected("registration_returned_false")),
        ).dispatch(request())
        assertEquals(DieselResponseStatus.OK, rejected.status)
        assertEquals(DieselValue.Text("registration_rejected"), rejected.data["outcome"])
        assertTrue(rejected.data.containsKey("reason"))
    }
}
