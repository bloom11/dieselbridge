// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

import kotlinx.coroutines.flow.StateFlow

/**
 * Stateful battery capability.
 *
 * A null state means that the provider does not yet have a battery reading.
 */
interface BatteryCapability : DieselCapability {

    val state: StateFlow<BatteryState?>

    override val id: String
        get() = ID

    companion object {
        const val ID = "battery"
    }
}
