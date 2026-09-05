// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

/**
 * Canonical battery state exposed by the Diesel platform.
 *
 * Provider identity deliberately does not belong in this value. Provider
 * provenance is available separately through CapabilityRegistry.
 */
data class BatteryState(
    val percent: Int,
    val voltageVolts: Double,
    val charging: Boolean,
)
