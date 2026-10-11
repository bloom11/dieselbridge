// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DieselPublicRuntimeContext(val api: DieselPublicApi, val scope: CoroutineScope) {
    private val active = AtomicBoolean(true)
    val isActive: Boolean get() = active.get()
    internal fun deactivate() { active.set(false) }
}
internal object DieselPublicRuntimeAccess {
    private val lock=Any(); private val mutableRuntime=MutableStateFlow<DieselPublicRuntimeContext?>(null)
    val runtime: StateFlow<DieselPublicRuntimeContext?> = mutableRuntime.asStateFlow()
    fun attach(context: DieselPublicRuntimeContext) = synchronized(lock) { check(mutableRuntime.value==null); mutableRuntime.value=context }
    fun detach(context: DieselPublicRuntimeContext) = synchronized(lock) { if (mutableRuntime.value===context) { context.deactivate(); mutableRuntime.value=null } }
}
