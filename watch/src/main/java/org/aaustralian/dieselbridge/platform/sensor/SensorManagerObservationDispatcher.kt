// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local callback dispatcher for long-lived SensorManager observation.
 *
 * Sensor callbacks can be frequent and must not share the UI/main looper.
 * One service-owned HandlerThread is shared by every SensorManager-backed
 * observation capability.
 *
 * Observation subscriptions must be closed before this dispatcher is closed
 * so listener cancellation/unregistration is requested before the callback
 * looper is retired.
 */
internal class SensorManagerObservationDispatcher :
    AutoCloseable {

    private val closed =
        AtomicBoolean(
            false,
        )

    private val callbackThread =
        HandlerThread(
            THREAD_NAME,
        ).apply {
            start()
        }

    val handler =
        Handler(
            callbackThread.looper,
        )

    override fun close() {
        if (
            !closed.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        callbackThread
            .quitSafely()
    }

    private companion object {
        const val THREAD_NAME =
            "DieselSensorObservation"
    }
}
