// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.hardware.Sensor

/**
 * Canonical SensorManager logical-route policy.
 *
 * Exact route identity remains diagnostic-only. Public consumers request
 * logical sensor families and never select Android routes.
 *
 * Spot reads and continuous observation intentionally have different
 * operational requirements:
 *
 *  - spot reads prefer non-wakeup and lower-power routes;
 *  - observation rejects one-shot routes;
 *  - observation prefers a route capable of the requested cadence;
 *  - observation then prefers wake-up delivery for screen-off continuity;
 *  - power and stable route id are deterministic tie-breakers.
 */
internal object SensorManagerLogicalRouteSelector {

    fun select(
        handles: List<AndroidSensorHandle>,
        logicalId: String,
    ): AndroidSensorHandle? =
        selectSpot(
            candidates =
                handles,
            logicalId =
                logicalId,
            routeOf = {
                it.route
            },
        )

    /**
     * Android-object-free spot-read projection for JVM tests.
     */
    fun selectRoute(
        routes: List<AndroidSensorRoute>,
        logicalId: String,
    ): AndroidSensorRoute? =
        selectSpot(
            candidates =
                routes,
            logicalId =
                logicalId,
            routeOf = {
                it
            },
        )

    fun selectForObservation(
        handles: List<AndroidSensorHandle>,
        logicalId: String,
        requestedPeriodUs: Int,
    ): AndroidSensorHandle? =
        selectObservation(
            candidates =
                handles,
            logicalId =
                logicalId,
            requestedPeriodUs =
                requestedPeriodUs,
            routeOf = {
                it.route
            },
        )

    /**
     * Android-object-free observation projection for JVM tests.
     */
    fun selectRouteForObservation(
        routes: List<AndroidSensorRoute>,
        logicalId: String,
        requestedPeriodUs: Int,
    ): AndroidSensorRoute? =
        selectObservation(
            candidates =
                routes,
            logicalId =
                logicalId,
            requestedPeriodUs =
                requestedPeriodUs,
            routeOf = {
                it
            },
        )

    private fun <T> selectSpot(
        candidates: List<T>,
        logicalId: String,
        routeOf: (T) -> AndroidSensorRoute,
    ): T? =
        candidates
            .asSequence()
            .filter {
                routeOf(it)
                    .inventory
                    .logicalId ==
                    logicalId
            }
            .minWithOrNull(
                compareBy<T> {
                    routeOf(it)
                        .inventory
                        .wakeUp
                }
                    .thenBy {
                        routeOf(it)
                            .inventory
                            .powerMilliAmps
                    }
                    .thenBy {
                        routeOf(it)
                            .descriptor
                            .routeId
                            .value
                    },
            )

    private fun <T> selectObservation(
        candidates: List<T>,
        logicalId: String,
        requestedPeriodUs: Int,
        routeOf: (T) -> AndroidSensorRoute,
    ): T? {
        require(
            requestedPeriodUs > 0,
        ) {
            "requestedPeriodUs must be positive"
        }

        return candidates
            .asSequence()
            .filter {
                val inventory =
                    routeOf(it)
                        .inventory

                inventory.logicalId ==
                    logicalId &&
                    inventory.reportingMode !=
                    Sensor.REPORTING_MODE_ONE_SHOT
            }
            .minWithOrNull(
                compareBy<T> {
                    val minDelayUs =
                        routeOf(it)
                            .inventory
                            .minDelayUs
                            .coerceAtLeast(
                                0,
                            )

                    /*
                     * A zero deficit means the route can satisfy the
                     * requested cadence. If every candidate is slower than
                     * requested, prefer the one that misses by the smallest
                     * amount rather than arbitrarily choosing by power.
                     */
                    maxOf(
                        0L,
                        minDelayUs.toLong() -
                            requestedPeriodUs.toLong(),
                    )
                }
                    .thenBy {
                        /*
                         * Continuous background observation prefers a wake-up
                         * route when one exists. Falling back to non-wakeup is
                         * still allowed when hardware exposes no alternative.
                         */
                        if (
                            routeOf(it)
                                .inventory
                                .wakeUp
                        ) {
                            0
                        } else {
                            1
                        }
                    }
                    .thenBy {
                        routeOf(it)
                            .inventory
                            .powerMilliAmps
                    }
                    .thenBy {
                        routeOf(it)
                            .descriptor
                            .routeId
                            .value
                    },
            )
    }
}
