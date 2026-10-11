// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc
import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.aaustralian.dieselbridge.api.DieselPublicApi
import org.aaustralian.dieselbridge.api.DieselPublicEncodedEvent
import org.aaustralian.dieselbridge.platform.event.DieselEventBus

internal class DieselRemoteEventSubscription(
    val id: Long,
    private val resourceIds: Set<String>,
    private val callback: IDieselEventCallback,
    eventBus: DieselEventBus,
    private val api: DieselPublicApi,
    parentScope: CoroutineScope,
    private val ownsObservation: (Long) -> Boolean,
    private val onCallbackDead: (Long) -> Unit,
) : IBinder.DeathRecipient {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val queue = BoundedIpcBatchQueue<DieselPublicEncodedEvent>(32)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        callback.asBinder().linkToDeath(this, 0)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eventBus.events.collect { event ->
                if (!DieselSessionEventVisibility.isVisible(event, ownsObservation)) return@collect
                val encoded = resourceIds.asSequence().mapNotNull { api.encodeEvent(it, event) }.firstOrNull()
                    ?: return@collect
                queue.offer(encoded); wake.trySend(Unit)
            }
        }
        scope.launch {
            for (ignored in wake) {
                delay(100L)
                while (isActive && !closed.get()) {
                    val drain = queue.drain(8)
                    if (drain.items.isEmpty()) break
                    try {
                        withContext(Dispatchers.IO) {
                            callback.onEvents(DieselIpcEventCodec.batch(id, drain.items, drain.droppedTotal))
                        }
                    } catch (_: RemoteException) {
                        if (closeInternal()) onCallbackDead(id)
                        return@launch
                    }
                    if (!drain.hasRemaining) break
                }
            }
        }
    }
    override fun binderDied() { if (closeInternal()) onCallbackDead(id) }
    fun close() { closeInternal() }
    private fun closeInternal(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        runCatching { callback.asBinder().unlinkToDeath(this, 0) }
        wake.close(); job.cancel(); return true
    }
}
