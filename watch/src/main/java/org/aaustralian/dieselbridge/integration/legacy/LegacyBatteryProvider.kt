// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.integration.legacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aaustralian.dieselbridge.ble.BatteryStatus
import org.aaustralian.dieselbridge.platform.capability.BatteryCapability
import org.aaustralian.dieselbridge.platform.capability.BatteryState
import org.aaustralian.dieselbridge.platform.provider.DieselProvider

/**
 * Adapter exposing DieselBridge's existing Android battery source as a
 * canonical Diesel battery capability.
 */
class LegacyBatteryProvider :
    BatteryCapability,
    DieselProvider {

    private val mutableState =
        MutableStateFlow<BatteryState?>(null)

    override val state: StateFlow<BatteryState?> =
        mutableState.asStateFlow()

    override val providerId: String
        get() = PROVIDER_ID

    fun update(status: BatteryStatus) {
        mutableState.value =
            BatteryState(
                percent = status.percent,
                voltageVolts = status.volts,
                charging = status.charging == 1,
            )
    }

    companion object {
        const val PROVIDER_ID = "legacy.battery"
        const val PRIORITY = 50
    }
}
