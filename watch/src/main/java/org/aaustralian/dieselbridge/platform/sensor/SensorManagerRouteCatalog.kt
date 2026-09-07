// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

/**
 * Concrete-route projection of the process-visible Android SensorManager
 * inventory.
 *
 * This class remains passive. It only transforms the existing inventory and
 * does not hold Sensor objects or register SensorEventListener instances.
 */
class SensorManagerRouteCatalog(
    private val inventory: SensorInventory,
) : AndroidSensorRouteCatalog {

    override fun snapshot():
        List<AndroidSensorRoute> {
        /*
         * Sensor.getId() is normally sufficient to distinguish concrete
         * sensors of the same Android type. Keep an ordinal as an explicit
         * collision discriminator rather than assuming every vendor
         * implementation obeys that uniqueness perfectly.
         *
         * AndroidSensorInventory supplies deterministic ordering, therefore a
         * repeated unchanged census receives the same route ids.
         */
        val collisionOrdinals =
            mutableMapOf<AndroidIdentity, Int>()

        return inventory
            .snapshot()
            .map { entry ->
                val identity =
                    AndroidIdentity(
                        androidType =
                            entry.androidType,
                        androidId =
                            entry.androidId,
                    )

                val ordinal =
                    collisionOrdinals[
                        identity
                    ] ?: 0

                collisionOrdinals[
                    identity
                ] =
                    ordinal + 1

                AndroidSensorRoute(
                    descriptor =
                        SensorRouteDescriptor(
                            routeId =
                                SensorRouteId(
                                    buildRouteId(
                                        entry = entry,
                                        ordinal = ordinal,
                                    ),
                                ),
                            logicalId =
                                SensorLogicalId(
                                    entry.logicalId,
                                ),
                            providerId =
                                PROVIDER_ID,
                        ),
                    inventory =
                        entry,
                )
            }
    }

    private fun buildRouteId(
        entry: SensorInventoryEntry,
        ordinal: Int,
    ): String =
        "$PROVIDER_ID:" +
            "${entry.androidType}:" +
            "${entry.androidId}:" +
            ordinal

    private data class AndroidIdentity(
        val androidType: Int,
        val androidId: Int,
    )

    companion object {
        const val PROVIDER_ID =
            "android.sensor_manager"
    }
}
