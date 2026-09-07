// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

/**
 * Android SensorManager-specific route record.
 *
 * [descriptor] is provider-neutral identity. [inventory] carries the Android
 * metadata required by the current sensor.list/debug census and, later, by
 * the SensorManager developer probe.
 */
data class AndroidSensorRoute(
    val descriptor: SensorRouteDescriptor,
    val inventory: SensorInventoryEntry,
) {
    init {
        require(
            descriptor.logicalId.value ==
                inventory.logicalId,
        ) {
            "Android sensor route logical id does not match inventory"
        }
    }
}

/**
 * Passive Android SensorManager route catalogue.
 *
 * snapshot() must not register listeners or activate sensors.
 */
fun interface AndroidSensorRouteCatalog {

    fun snapshot(): List<AndroidSensorRoute>
}
