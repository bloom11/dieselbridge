// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Stable battery state above provider selection.
 *
 * Consumers subscribe to [state] once. When CapabilityRegistry changes the
 * active battery provider, collection of the old provider is cancelled and
 * collection continues from the newly selected provider.
 */
class BatteryRoute(
    registry: CapabilityRegistry,
    scope: CoroutineScope,
) {

    private val mutableState =
        MutableStateFlow<BatteryState?>(null)

    val state: StateFlow<BatteryState?> =
        mutableState.asStateFlow()

    init {
        scope.launch {
            registry
                .observeActive(BatteryCapability.ID)
                .collectLatest { selection ->
                    val battery =
                        selection?.capability as? BatteryCapability

                    if (battery == null) {
                        mutableState.value = null
                    } else {
                        battery.state.collect { state ->
                            mutableState.value = state
                        }
                    }
                }
        }
    }
}
