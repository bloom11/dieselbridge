// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

/**
 * Concrete-route projection of process-visible Android SensorManager data.
 *
 * Production uses [AndroidSensorManagerSource], where route identity and the
 * corresponding Android Sensor object originate from the same ordered
 * snapshot.
 *
 * The metadata-only constructor is retained for pure route projection and
 * JVM tests. Neither path activates a sensor.
 */
class SensorManagerRouteCatalog private constructor(
    private val snapshotRoutes:
        () -> List<AndroidSensorRoute>,
) : AndroidSensorRouteCatalog {

    internal constructor(
        source: AndroidSensorManagerSource,
    ) : this(
        snapshotRoutes = {
            source
                .snapshot()
                .map { handle ->
                    handle.route
                }
        },
    )

    constructor(
        inventory: SensorInventory,
    ) : this(
        snapshotRoutes = {
            SensorManagerRouteProjector
                .project(
                    inventory.snapshot(),
                )
        },
    )

    override fun snapshot():
        List<AndroidSensorRoute> =
        snapshotRoutes()

    companion object {
        const val PROVIDER_ID =
            "android.sensor_manager"
    }
}

/**
 * Pure deterministic route-id projection shared by the live SensorManager
 * source and metadata-only route catalog.
 *
 * Keeping this function Android-object-free lets the existing JVM tests prove
 * route identity without constructing framework Sensor instances.
 */
internal object SensorManagerRouteProjector {

    fun project(
        entries:
            List<SensorInventoryEntry>,
    ): List<AndroidSensorRoute> {
        /*
         * Sensor.getId() is normally sufficient to distinguish concrete
         * sensors of the same Android type. Keep an ordinal as an explicit
         * collision discriminator rather than assuming every vendor
         * implementation obeys that uniqueness perfectly.
         *
         * The live source supplies deterministic ordering, preserving the
         * route ids already exposed by M4.1a.
         */
        val collisionOrdinals =
            mutableMapOf<
                AndroidIdentity,
                Int
            >()

        return entries
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
                                        entry =
                                            entry,
                                        ordinal =
                                            ordinal,
                                    ),
                                ),
                            logicalId =
                                SensorLogicalId(
                                    entry.logicalId,
                                ),
                            providerId =
                                SensorManagerRouteCatalog
                                    .PROVIDER_ID,
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
        SensorManagerRouteCatalog
            .PROVIDER_ID +
            ":" +
            "${entry.androidType}:" +
            "${entry.androidId}:" +
            ordinal

    private data class AndroidIdentity(
        val androidType: Int,
        val androidId: Int,
    )
}
