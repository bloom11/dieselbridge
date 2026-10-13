// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import org.aaustralian.dieselbridge.api.DieselPublicIngressEvent
import org.aaustralian.dieselbridge.platform.event.DieselEvent
import org.aaustralian.dieselbridge.platform.call.PhoneCallEvent
import org.aaustralian.dieselbridge.platform.event.SensorObservationEvent
import org.aaustralian.dieselbridge.platform.notification.PhoneNotificationEvent

internal object DieselSessionEventVisibility {
    fun isVisible(
        event: DieselEvent,
        ownsObservation: (Long) -> Boolean,
    ): Boolean =
        when (event) {
            is SensorObservationEvent ->
                ownsObservation(
                    event.subscriptionId,
                )

            is PhoneNotificationEvent ->
                true

            is PhoneCallEvent ->
                true

            is DieselPublicIngressEvent ->
                true

            else ->
                false
        }
}
