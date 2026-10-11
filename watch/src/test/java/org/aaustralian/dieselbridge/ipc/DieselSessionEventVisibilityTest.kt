// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc
import org.aaustralian.dieselbridge.platform.event.SensorObservationStateEvent
import org.aaustralian.dieselbridge.platform.sensor.observation.*
import org.junit.Assert.*
import org.junit.Test
class DieselSessionEventVisibilityTest {
    private val event=SensorObservationStateEvent(1000L,SensorSubscriptionState(
        42L,"accelerometer",SensorSubscriptionPhase.ACTIVE,"private.provider",100L,100L,100L,null,
    ))
    @Test fun sensorEventRequiresOwnedObservation(){
        assertTrue(DieselSessionEventVisibility.isVisible(event){it==42L})
        assertFalse(DieselSessionEventVisibility.isVisible(event){false})
    }
}
