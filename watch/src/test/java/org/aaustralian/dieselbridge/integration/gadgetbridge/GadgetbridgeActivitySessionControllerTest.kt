// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.integration.gadgetbridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventBridge
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationManager
import org.aaustralian.dieselbridge.protocol.GbMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GadgetbridgeActivitySessionControllerTest {
    @Test
    fun enableReconfigureDisableReconnectAndClose() = runTest {
        val manager = SensorObservationManager(CapabilityRegistry(), this)
        val session = GadgetbridgeActivitySessionController(
            manager, SensorObservationEventBridge(DieselEventBus()), this)
        assertTrue(session.apply(GbMessage.ActivityControl(true, true, 10)))
        val first = session.state.value
        val hr = requireNotNull(first.heartRateSubscriptionId)
        val stp = requireNotNull(first.stepSubscriptionId)
        assertNotEquals(hr, stp)
        assertTrue(session.apply(GbMessage.ActivityControl(true, true, 20)))
        assertEquals(hr, session.state.value.heartRateSubscriptionId)
        assertEquals(stp, session.state.value.stepSubscriptionId)
        assertEquals(20, session.state.value.intervalSeconds)
        assertTrue(session.apply(GbMessage.ActivityControl(false, true, 20)))
        assertNull(session.state.value.heartRateSubscriptionId)
        assertEquals(stp, session.state.value.stepSubscriptionId)
        session.disable("ble_disconnected")
        assertFalse(session.state.value.enabled)
        assertEquals("ble_disconnected", session.state.value.lastStopReason)
        assertTrue(session.apply(GbMessage.ActivityControl(true, false, 10)))
        assertNotNull(session.state.value.heartRateSubscriptionId)
        assertNotEquals(hr, session.state.value.heartRateSubscriptionId)
        assertTrue(session.apply(GbMessage.ActivityControl(false, false, 0)))
        assertFalse(session.state.value.enabled)
        session.close()
        session.close()
        assertFalse(session.apply(GbMessage.ActivityControl(true, false, 10)))
        manager.close()
    }
}
