// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.content.Context

/**
 * Passive SensorInventory projection of [AndroidSensorManagerSource].
 *
 * The canonical source retains the live Android Sensor object and assigns the
 * matching concrete route. This adapter exposes only census metadata to
 * existing inventory consumers.
 */
class AndroidSensorInventory internal constructor(
    private val source:
        AndroidSensorManagerSource,
) : SensorInventory {

    /**
     * Compatibility constructor for callers that only need an inventory.
     *
     * DieselBridgeService uses a shared source so inventory, route catalog and
     * the future exact-route resolver all use one implementation.
     */
    constructor(
        context: Context,
    ) : this(
        AndroidSensorManagerSource(
            context,
        ),
    )

    override fun snapshot():
        List<SensorInventoryEntry> =
        source
            .snapshot()
            .map { handle ->
                handle
                    .route
                    .inventory
            }
}
