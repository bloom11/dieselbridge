package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRoute
import org.aaustralian.dieselbridge.platform.sensor.SensorInventoryEntry
import org.aaustralian.dieselbridge.platform.sensor.SensorLogicalId
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteDescriptor
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteId
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorProbeStoreTest {
    private val route = AndroidSensorRoute(
        SensorRouteDescriptor(SensorRouteId("test:1"), SensorLogicalId("test"), "test"),
        SensorInventoryEntry(
            logicalId = "test", androidId = 1, androidType = 1, stringType = "test",
            name = "test", vendor = "vendor", version = 1, maxRange = 1f,
            resolution = 1f, powerMilliAmps = 1f, minDelayUs = 1, maxDelayUs = 1,
            fifoReservedEventCount = 0, fifoMaxEventCount = 0, reportingMode = 0, wakeUp = false,
        ),
    )

    @Test fun evicts_oldest_records_and_keeps_run_summaries_bounded() {
        val store = SensorProbeStore(capacity = 2, runCapacity = 1)
        store.begin(SensorScanSummary("a", 1, 0, 1))
        store.add(SensorProbeRecord("a", 0, route, "timeout", null, 1))
        store.add(SensorProbeRecord("a", 1, route, "event", null, 1))
        store.add(SensorProbeRecord("a", 2, route, "event", null, 1))
        store.begin(SensorScanSummary("b", 1, 0, 2))
        assertEquals(listOf(1, 2), store.snapshot().map { it.index })
        assertEquals(null, store.summary("a"))
        assertEquals("b", store.latestSummary()?.runId)
    }
}
