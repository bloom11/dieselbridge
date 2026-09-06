// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.sensor.SensorInventory
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec

/**
 * Process-local access to the live Diesel runtime for developer tooling.
 *
 * This deliberately does not persist or duplicate platform state. Diagnostic
 * UIs observe the same DieselPlatform and safe-test runner instances owned by
 * DieselBridgeService.
 */
object DeveloperRuntimeAccess {

    private val mutablePlatform =
        MutableStateFlow<DieselPlatform?>(null)

    val platform: StateFlow<DieselPlatform?> =
        mutablePlatform.asStateFlow()

    private val mutableSafeTestRunner =
        MutableStateFlow<SafePlatformTestRunner?>(null)

    val safeTestRunner:
        StateFlow<SafePlatformTestRunner?> =
        mutableSafeTestRunner.asStateFlow()

    private val mutableSensorInventory =
        MutableStateFlow<SensorInventory?>(null)

    val sensorInventory:
        StateFlow<SensorInventory?> =
        mutableSensorInventory.asStateFlow()

    private val mutableCommandCatalog =
        MutableStateFlow<List<DieselCommandSpec>>(
            emptyList(),
        )

    val commandCatalog:
        StateFlow<List<DieselCommandSpec>> =
        mutableCommandCatalog.asStateFlow()

    fun publishCommandCatalog(
        commands: List<DieselCommandSpec>,
    ) {
        mutableCommandCatalog.value =
            commands.toList()
    }

    fun attach(
        platform: DieselPlatform,
        safePlatformTestRunner: SafePlatformTestRunner,
        sensorInventory: SensorInventory,
    ) {
        /*
         * Publish service-owned tooling before the platform becomes visible so
         * a diagnostic UI observing an active runtime can immediately resolve
         * the matching instances.
         *
         * SensorInventory itself is retained here only by reference. Sensor
         * snapshots remain live and are not duplicated or persisted.
         */
        mutableSafeTestRunner.value =
            safePlatformTestRunner

        mutableSensorInventory.value =
            sensorInventory

        mutablePlatform.value =
            platform
    }

    fun detach(
        platform: DieselPlatform,
    ) {
        if (mutablePlatform.value === platform) {
            mutableSensorInventory.value = null
            mutableSafeTestRunner.value = null
            mutablePlatform.value = null
        }
    }
}
