// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform

import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.diagnostic.PlatformDiagnostics
import org.aaustralian.dieselbridge.platform.event.DieselEventBus

/**
 * Process-local Diesel runtime context.
 *
 * This container owns platform infrastructure only. Concrete Bluetooth,
 * sensor, health, display and plugin implementations remain separate
 * providers/modules.
 */
class DieselPlatform(
    val diagnostics: PlatformDiagnostics = PlatformDiagnostics(),
    val events: DieselEventBus = DieselEventBus(),
) {
    val capabilities =
        CapabilityRegistry(
            diagnostics = diagnostics,
        )
}
