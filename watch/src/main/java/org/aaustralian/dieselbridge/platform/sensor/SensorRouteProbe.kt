// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

/**
 * Bounded diagnostic access to one exact concrete sensor route.
 *
 * This abstraction deliberately exposes no Android Sensor object to command
 * modules. Route resolution and framework sampling stay inside the platform
 * sensor implementation.
 */
internal interface SensorRouteProbe {

    suspend fun probe(
        routeId: SensorRouteId,
        timeoutMs: Long,
    ): SensorRouteProbeOutcome
}

/**
 * Result of resolving and probing one concrete sensor route.
 *
 * RouteUnavailable is an expected diagnostic observation: route identities
 * describe a process-visible census and the underlying framework inventory can
 * change between discovery and a later probe.
 */
internal sealed interface SensorRouteProbeOutcome {

    object RouteUnavailable :
        SensorRouteProbeOutcome

    data class Sample(
        val route: AndroidSensorRoute,
        val outcome: BoundedSensorSampleOutcome,
    ) : SensorRouteProbeOutcome
}

/**
 * Android SensorManager implementation of [SensorRouteProbe].
 *
 * The route is resolved against a fresh canonical SensorManager snapshot.
 * Only the exact Sensor carried by that resolved handle is activated, and
 * [AndroidSensorSampler] guarantees bounded first-event sampling and cleanup.
 */
internal class AndroidSensorRouteProbe(
    private val source:
        AndroidSensorManagerSource,
    private val sampler:
        AndroidSensorSampler,
) : SensorRouteProbe {

    override suspend fun probe(
        routeId: SensorRouteId,
        timeoutMs: Long,
    ): SensorRouteProbeOutcome {
        val handle =
            source.resolve(
                routeId,
            )
                ?: return SensorRouteProbeOutcome
                    .RouteUnavailable

        val outcome =
            sampler.sample(
                handle = handle,
                timeoutMs = timeoutMs,
            )

        return SensorRouteProbeOutcome
            .Sample(
                route = handle.route,
                outcome = outcome,
            )
    }
}
