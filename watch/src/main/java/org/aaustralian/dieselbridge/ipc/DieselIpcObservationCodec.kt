// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import android.os.Bundle
import org.aaustralian.dieselbridge.api.DieselPublicObservationOpenResult
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionSample
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionState

internal object DieselIpcObservationCodec {
    fun encodeOpen(result: DieselPublicObservationOpenResult): Bundle = when (result) {
        is DieselPublicObservationOpenResult.Opened -> Bundle().apply {
            putString("status", DieselIpcCodec.STATUS_OK)
            putLong("subscriptionId", result.subscription.id)
            putBundle("state", encodeState(result.subscription.state.value, 0L))
        }
        is DieselPublicObservationOpenResult.NotFound -> DieselIpcCodec.encodeNotFound(result.resourceId)
        is DieselPublicObservationOpenResult.Rejected -> Bundle().apply {
            putString("status", DieselIpcCodec.STATUS_REJECTED)
            putString("reason", result.reason)
        }
    }

    fun encodeUpdate(
        subscriptionId: Long,
        state: SensorSubscriptionState?,
        samples: List<SensorSubscriptionSample>,
        ipcDroppedTotal: Long,
    ): Bundle = Bundle().apply {
        putString("kind", "observation_update")
        putLong("subscriptionId", subscriptionId)
        putLong("ipcDroppedTotal", ipcDroppedTotal)
        state?.let { putBundle("state", encodeState(it, ipcDroppedTotal)) }
        if (samples.isNotEmpty()) {
            putParcelableArrayList("samples", ArrayList(samples.map { encodeSample(it, ipcDroppedTotal) }))
        }
    }

    fun encodeClosed(subscriptionId: Long, reason: String): Bundle = Bundle().apply {
        putString("kind", "observation_closed")
        putLong("subscriptionId", subscriptionId)
        putString("reason", reason)
    }

    private fun encodeState(state: SensorSubscriptionState, ipcDroppedTotal: Long): Bundle = Bundle().apply {
        putLong("subscriptionId", state.subscriptionId)
        putString("logicalId", state.logicalId)
        putString("phase", state.phase.name.lowercase())
        putLong("requestedPeriodMs", state.requestedPeriodMs)
        state.acquisitionPeriodMs?.let { putLong("acquisitionPeriodMs", it) }
        state.providerEffectivePeriodMs?.let { putLong("providerEffectivePeriodMs", it) }
        state.providerConfiguredPeriodMs?.let { putLong("providerConfiguredPeriodMs", it) }
        state.reason?.let { putString("reason", it) }
        putLong("providerDroppedTotal", state.sourceDroppedTotal)
        putLong("subscriptionDroppedTotal", state.subscriptionDroppedTotal)
        putLong("ipcDroppedTotal", ipcDroppedTotal)
    }

    private fun encodeSample(sample: SensorSubscriptionSample, ipcDroppedTotal: Long): Bundle = Bundle().apply {
        putLong("sequence", sample.sequence)
        putLong("timestampNanos", sample.reading.timestampNanos)
        sample.reading.accuracy?.let { putInt("accuracy", it) }
        putDoubleArray("values", sample.reading.values.map { it.toDouble() }.toDoubleArray())
        putLong("providerDroppedTotal", sample.sourceDroppedTotal)
        putLong("subscriptionDroppedTotal", sample.droppedTotal)
        putLong("ipcDroppedTotal", ipcDroppedTotal)
    }
}
