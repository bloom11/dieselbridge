// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.integration.gadgetbridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorBufferPolicy
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationClient
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventBridge
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventRegistration
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationManager
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscription
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionRequest
import org.aaustralian.dieselbridge.protocol.GbMessage

/**
 * Session-local owner of the HR and step logical subscriptions. The shared
 * manager owns hardware; the bridge only forwards each subscription's stream.
 * M5.1a deliberately sends no activity reports yet (M5.1b/c add the codec).
 * Lock protects binder RX, disconnect and service-shutdown entry points.
 */
class GadgetbridgeActivitySessionController(
    private val manager: SensorObservationManager,
    private val bridge: SensorObservationEventBridge,
    private val scope: CoroutineScope,
) : AutoCloseable {
    data class Snapshot(
        val enabled: Boolean = false,
        val heartRateRequested: Boolean = false,
        val stepsRequested: Boolean = false,
        val intervalSeconds: Int = 10,
        val heartRateSubscriptionId: Long? = null,
        val stepSubscriptionId: Long? = null,
        val lastStopReason: String? = null,
        val lastError: String? = null,
    )

    private class Owned(
        val subscription: SensorSubscription,
        val registration: SensorObservationEventRegistration,
    ) {
        fun release() {
            registration.close() // stop publication first
            subscription.close() // only the session owner closes hardware lease
        }
    }

    private val lock = Any()
    private val mutableState = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = mutableState.asStateFlow()
    private var client: SensorObservationClient? = null
    private var hr: Owned? = null
    private var steps: Owned? = null
    private var closed = false

    /** Repeated commands and reporting-interval updates never resubscribe. */
    fun apply(control: GbMessage.ActivityControl): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (!control.enabled) {
            releaseAll()
            mutableState.value = Snapshot(intervalSeconds = control.intervalSeconds,
                lastStopReason = "phone_disabled")
            return@synchronized true
        }
        try {
            val owner = client ?: manager.openClient("gadgetbridge-activity").also { client = it }
            if (!control.heartRate) { hr?.release(); hr = null }
            if (!control.steps) { steps?.release(); steps = null }
            if (control.heartRate && hr == null) hr = open(owner, "heart_rate")
            if (control.steps && steps == null) steps = open(owner, "step_counter")
            mutableState.value = Snapshot(enabled = true,
                heartRateRequested = control.heartRate, stepsRequested = control.steps,
                intervalSeconds = control.intervalSeconds,
                heartRateSubscriptionId = hr?.subscription?.id,
                stepSubscriptionId = steps?.subscription?.id)
            true
        } catch (error: Exception) {
            releaseAll() // no half-open session after an admission failure
            mutableState.value = Snapshot(lastStopReason = "start_failed",
                lastError = error.javaClass.simpleName + (error.message?.let { ": $it" } ?: ""))
            false
        }
    }

    /** Temporary link stop: a new activity command after reconnect can restart. */
    fun disable(reason: String) = synchronized(lock) {
        if (!closed) {
            releaseAll()
            mutableState.value = Snapshot(lastStopReason = reason)
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            releaseAll()
            mutableState.value = Snapshot(lastStopReason = "controller_closed")
        }
    }

    private fun open(owner: SensorObservationClient, logicalId: String): Owned {
        val subscription = owner.subscribe(
            SensorSubscriptionRequest(logicalId, 1_000L, SensorBufferPolicy.Latest))
        try {
            return Owned(subscription, bridge.attach(subscription, scope))
        } catch (error: Exception) {
            subscription.close()
            throw error
        }
    }

    private fun releaseAll() {
        hr?.release(); hr = null
        steps?.release(); steps = null
        client?.close(); client = null
    }
}
