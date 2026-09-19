// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.event

import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionSample
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionState

/**
 * Typed process-local events produced from a caller-owned sensor subscription.
 *
 * These are platform events, not Diesel wire-protocol envelopes.
 * The event bus distributes them but never owns the underlying hardware
 * registration or subscription lifecycle.
 */
sealed interface SensorObservationEvent :
    DieselEvent {

    val subscriptionId: Long
    val logicalId: String
}

/**
 * One observation lifecycle/state update.
 */
data class SensorObservationStateEvent(
    override val timestampMs: Long,
    val state: SensorSubscriptionState,
) : SensorObservationEvent {

    override val type: String =
        TYPE

    override val subscriptionId: Long
        get() =
            state.subscriptionId

    override val logicalId: String
        get() =
            state.logicalId

    val providerDroppedTotal: Long
        get() =
            state.sourceDroppedTotal

    val subscriptionDroppedTotal: Long
        get() =
            state.subscriptionDroppedTotal

    companion object {
        const val TYPE =
            "sensor.subscription.state"
    }
}

/**
 * One consumer-delivered logical sensor sample.
 *
 * [timestampMs] is the wall-clock publication time of this platform event.
 * The sensor's monotonic/event timestamp remains
 * [SensorSubscriptionSample.reading.timestampNanos].
 */
data class SensorObservationSampleEvent(
    override val timestampMs: Long,
    override val subscriptionId: Long,
    override val logicalId: String,
    val sample: SensorSubscriptionSample,
) : SensorObservationEvent {

    override val type: String =
        TYPE

    val providerDroppedTotal: Long
        get() =
            sample.sourceDroppedTotal

    val subscriptionDroppedTotal: Long
        get() =
            sample.droppedTotal

    companion object {
        const val TYPE =
            "sensor.sample"
    }
}
