// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aaustralian.dieselbridge.platform.DieselPlatform

/**
 * Process-local access to the live Diesel runtime for developer tooling.
 *
 * This deliberately does not persist or duplicate platform state. Diagnostic
 * UIs observe the same DieselPlatform instance owned by DieselBridgeService.
 */
object DeveloperRuntimeAccess {

    private val mutablePlatform =
        MutableStateFlow<DieselPlatform?>(null)

    val platform: StateFlow<DieselPlatform?> =
        mutablePlatform.asStateFlow()

    private val mutableCommandCatalog =
        MutableStateFlow<List<DeveloperCommandSpec>>(
            emptyList(),
        )

    val commandCatalog:
        StateFlow<List<DeveloperCommandSpec>> =
        mutableCommandCatalog.asStateFlow()

    fun publishCommandCatalog(
        commands: List<DeveloperCommandSpec>,
    ) {
        mutableCommandCatalog.value =
            commands.toList()
    }

    fun attach(platform: DieselPlatform) {
        mutablePlatform.value = platform
    }

    fun detach(platform: DieselPlatform) {
        if (mutablePlatform.value === platform) {
            mutablePlatform.value = null
        }
    }
}
