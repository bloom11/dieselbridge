// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

/**
 * A feature exposed by the Diesel platform.
 *
 * Higher layers such as native UI, Espruino, Bangle compatibility and
 * future plugins should depend on capabilities rather than concrete
 * DieselBridge implementations.
 */
interface DieselCapability {
    /**
     * Stable machine-readable capability identifier.
     *
     * Examples:
     * vibration
     * media
     * notifications
     * sensor.heart_rate
     */
    val id: String
}
