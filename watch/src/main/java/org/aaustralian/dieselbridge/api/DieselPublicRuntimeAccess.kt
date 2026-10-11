// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventBridge
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationManager
import org.aaustralian.dieselbridge.platform.state.DieselStateStore

internal class DieselPublicRuntimeContext(
    val api: DieselPublicApi,
    val scope: CoroutineScope,
    val observationManager: SensorObservationManager,
    val eventBus: DieselEventBus,
    val observationEventBridge: SensorObservationEventBridge,
    val states: DieselStateStore,
) {
    private val active = AtomicBoolean(true)
    val isActive: Boolean get() = active.get()
    internal fun deactivate() { active.set(false) }
}

internal object DieselPublicRuntimeAccess {
    private val lock = Any()
    private val mutableRuntime = MutableStateFlow<DieselPublicRuntimeContext?>(null)
    val runtime: StateFlow<DieselPublicRuntimeContext?> = mutableRuntime.asStateFlow()

    fun attach(context: DieselPublicRuntimeContext) = synchronized(lock) {
        check(mutableRuntime.value == null) { "A public Diesel runtime is already attached" }
        mutableRuntime.value = context
    }

    fun detach(context: DieselPublicRuntimeContext) = synchronized(lock) {
        if (mutableRuntime.value === context) {
            context.deactivate()
            mutableRuntime.value = null
        }
    }
}
