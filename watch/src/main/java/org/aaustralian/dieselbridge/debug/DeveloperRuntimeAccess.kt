// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.sensor.SensorInventory
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.sensor.SensorMatrixExperiment

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

    private val mutableSensorMatrixExperiment =
        MutableStateFlow<SensorMatrixExperiment?>(null)

    val sensorMatrixExperiment: StateFlow<SensorMatrixExperiment?> =
        mutableSensorMatrixExperiment.asStateFlow()

    fun attachSensorMatrixExperiment(experiment: SensorMatrixExperiment) {
        mutableSensorMatrixExperiment.value = experiment
    }

    private val mutableSafeTestRunner =
        MutableStateFlow<SafePlatformTestRunner?>(null)

    val safeTestRunner:
        StateFlow<SafePlatformTestRunner?> =
        mutableSafeTestRunner.asStateFlow()

    private val mutableDeveloperRemoteAccessPolicy =
        MutableStateFlow<DeveloperRemoteAccessPolicy?>(null)

    val developerRemoteAccessPolicy:
        StateFlow<DeveloperRemoteAccessPolicy?> =
        mutableDeveloperRemoteAccessPolicy.asStateFlow()

    private val mutableSensorInventory =
        MutableStateFlow<SensorInventory?>(null)

    val sensorInventory:
        StateFlow<SensorInventory?> =
        mutableSensorInventory.asStateFlow()

    private val mutableHealthServicesRefresh =
        MutableStateFlow<(suspend () -> Unit)?>(null)

    val healthServicesRefresh: StateFlow<(suspend () -> Unit)?> =
        mutableHealthServicesRefresh.asStateFlow()

    fun attachHealthServicesRefresh(refresh: suspend () -> Unit) {
        mutableHealthServicesRefresh.value = refresh
    }

    private val mutableCommandDispatcher =
        MutableStateFlow<(suspend (DieselRequest) -> DieselCommandResult)?>(null)

    val commandDispatcher: StateFlow<(suspend (DieselRequest) -> DieselCommandResult)?> =
        mutableCommandDispatcher.asStateFlow()

    fun attachCommandDispatcher(
        dispatcher: suspend (DieselRequest) -> DieselCommandResult,
    ) {
        mutableCommandDispatcher.value = dispatcher
    }

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
        developerRemoteAccessPolicy: DeveloperRemoteAccessPolicy,
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

        mutableDeveloperRemoteAccessPolicy.value =
            developerRemoteAccessPolicy

        mutablePlatform.value =
            platform
    }

    fun detach(
        platform: DieselPlatform,
    ) {
        if (mutablePlatform.value === platform) {
            mutableDeveloperRemoteAccessPolicy.value = null
            mutableSensorInventory.value = null
            mutableSafeTestRunner.value = null
            mutableSensorMatrixExperiment.value = null
            mutableCommandDispatcher.value = null
            mutableHealthServicesRefresh.value = null
            mutablePlatform.value = null
        }
    }
}
