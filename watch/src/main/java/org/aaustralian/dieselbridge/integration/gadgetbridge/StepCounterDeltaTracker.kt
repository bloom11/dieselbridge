// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.integration.gadgetbridge

/**
 * Converts an Android-style cumulative step counter into Gadgetbridge
 * realtime interval deltas.
 *
 * The first sample establishes a baseline and is never emitted as a delta.
 * A provider-domain change or counter rollback establishes a fresh baseline
 * so reboot/fallback cannot become a large or negative realtime step count.
 *
 * The baseline advances only when the containing realtime line is admitted
 * by the physical line transport. Local queue rejection therefore preserves
 * the pending delta for a later report.
 */
internal class StepCounterDeltaTracker {

    data class State(
        val providerId: String? = null,
        val baselineRaw: Long? = null,
        val latestRaw: Long? = null,
        val pendingDelta: Int = 0,
        val domainResetCount: Long = 0L,
    )

    private var providerId: String? = null
    private var baselineRaw: Long? = null
    private var latestRaw: Long? = null
    private var domainResetCount: Long = 0L

    fun snapshot(): State =
        State(
            providerId = providerId,
            baselineRaw = baselineRaw,
            latestRaw = latestRaw,
            pendingDelta = pendingDelta(),
            domainResetCount = domainResetCount,
        )

    fun observeProvider(
        newProviderId: String,
    ): State {
        require(newProviderId.isNotBlank())

        val oldProviderId = providerId

        if (
            oldProviderId != null &&
            oldProviderId != newProviderId
        ) {
            baselineRaw = null
            latestRaw = null
            domainResetCount++
        }

        providerId = newProviderId
        return snapshot()
    }

    fun observeSample(
        sampleProviderId: String,
        rawCounter: Long,
    ): State {
        require(rawCounter >= 0L)

        observeProvider(sampleProviderId)

        val previousRaw = latestRaw

        when {
            previousRaw == null -> {
                baselineRaw = rawCounter
                latestRaw = rawCounter
            }

            rawCounter < previousRaw -> {
                baselineRaw = rawCounter
                latestRaw = rawCounter
                domainResetCount++
            }

            else ->
                latestRaw = rawCounter
        }

        return snapshot()
    }

    fun pendingDelta(): Int {
        val baseline = baselineRaw ?: return 0
        val latest = latestRaw ?: return 0

        if (latest <= baseline) return 0

        return (latest - baseline)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun acknowledgeQueued(
        delta: Int,
    ): State {
        require(delta >= 0)

        val pending = pendingDelta()

        require(delta <= pending) {
            "Cannot acknowledge more steps than are pending"
        }

        if (delta == 0) return snapshot()

        val baseline =
            requireNotNull(baselineRaw)

        baselineRaw =
            baseline + delta.toLong()

        return snapshot()
    }

    fun reset(): State {
        providerId = null
        baselineRaw = null
        latestRaw = null
        domainResetCount = 0L
        return snapshot()
    }
}
