// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform

import kotlinx.coroutines.CoroutineScope
import org.aaustralian.dieselbridge.platform.capability.BatteryRoute
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.diagnostic.PlatformDiagnostics
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventBridge

/**
 * Process-local Diesel runtime context.
 *
 * The owner supplies [scope] and remains responsible for cancelling it.
 * Concrete Bluetooth, sensor, health, display and plugin implementations
 * remain separate providers/modules.
 */
class DieselPlatform(
    scope: CoroutineScope,
    val diagnostics: PlatformDiagnostics = PlatformDiagnostics(),
    val events: DieselEventBus = DieselEventBus(),
) {
    val capabilities =
        CapabilityRegistry(
            diagnostics = diagnostics,
        )

    /**
     * Process-local adapter for publishing caller-owned sensor subscriptions.
     *
     * The bridge owns no sensor client, provider or hardware registration.
     * Consumers explicitly attach subscriptions using their own lifecycle
     * scope.
     */
    val sensorObservationEvents =
        SensorObservationEventBridge(
            events = events,
        )

    /**
     * Stable battery route above provider selection.
     *
     * Consumers use this route rather than retaining a concrete battery
     * provider, so provider fallback and recovery remain transparent.
     */
    val battery =
        BatteryRoute(
            registry = capabilities,
            scope = scope,
        )
}
