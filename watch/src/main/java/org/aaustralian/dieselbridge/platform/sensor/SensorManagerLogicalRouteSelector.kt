// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

/**
 * Canonical route preference for logical SensorManager access.
 *
 * Bounded sensor.read and long-lived sensor.observe use this same policy so
 * the access mode cannot silently select a different physical sensor.
 *
 * Preference:
 *  1. matching logical sensor family;
 *  2. non-wakeup before wakeup;
 *  3. lower reported power;
 *  4. stable route id as deterministic tie-break.
 *
 * Exact route selection remains diagnostic-only.
 */
internal object SensorManagerLogicalRouteSelector {

    fun select(
        handles: List<AndroidSensorHandle>,
        logicalId: String,
    ): AndroidSensorHandle? =
        handles
            .asSequence()
            .filter {
                it.route.inventory.logicalId ==
                    logicalId
            }
            .minWithOrNull(
                compareBy<AndroidSensorHandle> {
                    it.route.inventory.wakeUp
                }
                    .thenBy {
                        it.route.inventory.powerMilliAmps
                    }
                    .thenBy {
                        it.route.descriptor.routeId.value
                    },
            )

    /**
     * Android-object-free projection for JVM tests.
     */
    fun selectRoute(
        routes: List<AndroidSensorRoute>,
        logicalId: String,
    ): AndroidSensorRoute? =
        routes
            .asSequence()
            .filter {
                it.inventory.logicalId ==
                    logicalId
            }
            .minWithOrNull(
                compareBy<AndroidSensorRoute> {
                    it.inventory.wakeUp
                }
                    .thenBy {
                        it.inventory.powerMilliAmps
                    }
                    .thenBy {
                        it.descriptor.routeId.value
                    },
            )
}
