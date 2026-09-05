// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.integration.legacy

import org.aaustralian.dieselbridge.ble.BatteryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyBatteryProviderTest {

    @Test
    fun startsWithoutReading() {
        val provider = LegacyBatteryProvider()

        assertNull(provider.state.value)
    }

    @Test
    fun convertsLegacyBatteryStatus() {
        val provider = LegacyBatteryProvider()

        provider.update(
            BatteryStatus(
                percent = 73,
                volts = 4.08,
                charging = 1,
            ),
        )

        val state = provider.state.value!!

        assertEquals(73, state.percent)
        assertEquals(4.08, state.voltageVolts, 0.0001)
        assertTrue(state.charging)
    }

    @Test
    fun convertsNotCharging() {
        val provider = LegacyBatteryProvider()

        provider.update(
            BatteryStatus(
                percent = 42,
                volts = 3.91,
                charging = 0,
            ),
        )

        assertFalse(provider.state.value!!.charging)
    }
}
