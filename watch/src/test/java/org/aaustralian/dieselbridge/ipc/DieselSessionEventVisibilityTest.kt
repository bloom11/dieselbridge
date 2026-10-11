// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import org.aaustralian.dieselbridge.api.DieselPublicIngressEvent
import org.aaustralian.dieselbridge.platform.event.SensorObservationStateEvent
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionPhase
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionState
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselSessionEventVisibilityTest {
    @Test
    fun sensorEventRequiresOwnedObservation() {
        val event =
            SensorObservationStateEvent(
                timestampMs = 1_000L,
                state =
                    SensorSubscriptionState(
                        subscriptionId = 42L,
                        logicalId = "accelerometer",
                        phase = SensorSubscriptionPhase.ACTIVE,
                        providerId = "private.provider",
                        requestedPeriodMs = 100L,
                        acquisitionPeriodMs = 100L,
                        providerEffectivePeriodMs = 100L,
                        reason = null,
                    ),
            )

        assertTrue(
            DieselSessionEventVisibility.isVisible(
                event,
            ) {
                it == 42L
            },
        )

        assertFalse(
            DieselSessionEventVisibility.isVisible(
                event,
            ) {
                false
            },
        )
    }

    @Test
    fun genericPublicIngressEventDoesNotPretendToBeSensorOwned() {
        val event =
            DieselPublicIngressEvent(
                "plugin.example.client.tick",
                2_000L,
                DieselValue.Integer(
                    1L,
                ),
            )

        assertTrue(
            DieselSessionEventVisibility.isVisible(
                event,
            ) {
                false
            },
        )
    }
}
