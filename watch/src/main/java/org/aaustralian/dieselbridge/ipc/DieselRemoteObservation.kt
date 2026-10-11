// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.event.SensorObservationSampleEvent
import org.aaustralian.dieselbridge.platform.event.SensorObservationStateEvent
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventBridge
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventRegistration
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscription
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionPhase
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionSample
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionState

internal class DieselRemoteObservation(
    val subscription: SensorSubscription,
    private val callback: IDieselObservationCallback,
    eventBus: DieselEventBus,
    eventBridge: SensorObservationEventBridge,
    parentScope: CoroutineScope,
    private val onCallbackDead: (Long) -> Unit,
    private val onTerminal: (Long) -> Unit,
) : IBinder.DeathRecipient {
    val id: Long get() = subscription.id
    private val closed = AtomicBoolean(false)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val samples = BoundedIpcBatchQueue<SensorSubscriptionSample>(32)
    private val stateLock = Any()
    private var pendingState: SensorSubscriptionState? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val bridge: SensorObservationEventRegistration

    init {
        callback.asBinder().linkToDeath(this, 0)

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            eventBus.events.collect { event ->
                when (event) {
                    is SensorObservationStateEvent -> if (event.subscriptionId == id) {
                        synchronized(stateLock) { pendingState = event.state }
                        wake.trySend(Unit)
                    }
                    is SensorObservationSampleEvent -> if (event.subscriptionId == id) {
                        samples.offer(event.sample)
                        wake.trySend(Unit)
                    }
                    else -> Unit
                }
            }
        }

        bridge = eventBridge.attach(subscription, scope)

        scope.launch {
            for (ignored in wake) {
                delay(100L)
                while (isActive && !closed.get()) {
                    val state = synchronized(stateLock) { pendingState.also { pendingState = null } }
                    val drain = samples.drain(8)
                    if (state == null && drain.items.isEmpty()) break

                    try {
                        withContext(Dispatchers.IO) {
                            callback.onUpdate(
                                DieselIpcObservationCodec.encodeUpdate(id, state, drain.items, drain.droppedTotal),
                            )
                        }
                    } catch (_: RemoteException) {
                        onCallbackDead(id)
                        return@launch
                    }

                    if (state?.phase == SensorSubscriptionPhase.CLOSED) {
                        onTerminal(id)
                        return@launch
                    }
                    if (!drain.hasRemaining) break
                }
            }
        }
    }

    override fun binderDied() { onCallbackDead(id) }

    fun close(reason: String, notify: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { callback.asBinder().unlinkToDeath(this, 0) }
        bridge.close()
        subscription.close()
        wake.close()
        job.cancel()
        if (notify) runCatching { callback.onUpdate(DieselIpcObservationCodec.encodeClosed(id, reason)) }
    }
}
