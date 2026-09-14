// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

internal data class SensorManagerObservationSamplingPlan(
    val requestedPeriodUs: Int,
    val registrationPeriodUs: Int,
    val configuredPeriodMs: Long,
)

/**
 * Convert the logical millisecond preference into the finite Int microsecond
 * domain accepted by SensorManager.
 */
internal fun sensorManagerRequestedPeriodUs(
    preferredSamplePeriodMs: Long,
): Int {
    require(
        preferredSamplePeriodMs > 0L,
    ) {
        "preferredSamplePeriodMs must be positive"
    }

    return preferredSamplePeriodMs
        .coerceAtMost(
            MAX_SENSOR_MANAGER_PERIOD_MS,
        )
        .times(
            1_000L,
        )
        .toInt()
}

/**
 * Apply the selected physical sensor's fastest supported cadence.
 *
 * minDelayUs == 0 is common for fixed/on-change sensors and does not impose a
 * faster-than-requested clamp here.
 */
internal fun sensorManagerObservationSamplingPlan(
    preferredSamplePeriodMs: Long,
    minDelayUs: Int,
): SensorManagerObservationSamplingPlan {
    val requestedPeriodUs =
        sensorManagerRequestedPeriodUs(
            preferredSamplePeriodMs,
        )

    val physicalMinimumUs =
        minDelayUs
            .coerceAtLeast(
                0,
            )

    val registrationPeriodUs =
        maxOf(
            requestedPeriodUs,
            physicalMinimumUs,
        )

    val configuredPeriodMs =
        (
            registrationPeriodUs
                .toLong() +
                999L
        ) /
            1_000L

    return SensorManagerObservationSamplingPlan(
        requestedPeriodUs =
            requestedPeriodUs,
        registrationPeriodUs =
            registrationPeriodUs,
        configuredPeriodMs =
            configuredPeriodMs,
    )
}

private const val MAX_SENSOR_MANAGER_PERIOD_MS =
    Int.MAX_VALUE /
        1_000L
