// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aaustralian.dieselbridge.platform.transport.DieselTransportIncomingFrameException
import org.aaustralian.dieselbridge.platform.transport.DieselTransportLease
import org.aaustralian.dieselbridge.platform.transport.DieselTransportSendResult

internal class DieselRemoteTransportLease(
    private val lease: DieselTransportLease,
    private val callback: IDieselTransportCallback,
    parentScope: CoroutineScope,
    private val onCallbackDead: (Long) -> Unit,
    private val onTerminal: (Long) -> Unit,
) : IBinder.DeathRecipient {
    val id: Long
        get() = lease.id

    val maxFrameBytes: Int
        get() = lease.maxFrameBytes

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val terminalReason = AtomicReference<String?>(null)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val frames = LosslessIpcFrameQueue(FRAME_QUEUE_CAPACITY)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    val isClosed: Boolean
        get() = closed.get()

    init {
        callback.asBinder().linkToDeath(this, 0)
    }

    fun start() {
        if (
            closed.get() ||
            !started.compareAndSet(false, true)
        ) {
            return
        }

        scope.launch {
            dispatchLoop()
        }

        scope.launch(
            start = CoroutineStart.UNDISPATCHED,
        ) {
            collectIncoming()
        }
    }

    suspend fun send(
        frame: ByteArray,
    ): DieselTransportSendResult =
        lease.send(
            frame,
        )

    override fun binderDied() {
        if (
            terminate(
                "callback_died",
                notify = false,
                reportTerminal = false,
            )
        ) {
            onCallbackDead(
                id,
            )
        }
    }

    fun close(
        reason: String,
        notify: Boolean,
    ) {
        terminate(
            reason,
            notify,
            reportTerminal = false,
        )
    }

    private suspend fun collectIncoming() {
        try {
            lease.incoming.collect {
                frame ->
                if (!frames.offer(frame)) {
                    throw IpcBackpressure()
                }
                wake.trySend(Unit)
            }

            setTerminalReason(
                "provider_closed",
            )
        } catch (cancellation: CancellationException) {
            if (!closed.get()) {
                throw cancellation
            }
        } catch (_: IpcBackpressure) {
            if (
                terminate(
                    "ipc_backpressure",
                    notify = true,
                    reportTerminal = true,
                )
            ) {
                return
            }
        } catch (error: DieselTransportIncomingFrameException) {
            if (!closed.get()) {
                setTerminalReason(
                    error.transportReason,
                )
            }
        } catch (_: Exception) {
            if (!closed.get()) {
                setTerminalReason(
                    "provider_receive_failed",
                )
            }
        }
    }

    private suspend fun dispatchLoop() {
        for (ignored in wake) {
            while (!closed.get()) {
                val drain =
                    frames.drain(
                        MAX_FRAMES_PER_DRAIN,
                    )

                for (frame in drain.frames) {
                    try {
                        withContext(
                            Dispatchers.IO,
                        ) {
                            callback.onFrame(
                                id,
                                frame,
                            )
                        }
                    } catch (_: RemoteException) {
                        if (
                            terminate(
                                "callback_dead",
                                notify = false,
                                reportTerminal = false,
                            )
                        ) {
                            onCallbackDead(
                                id,
                            )
                        }
                        return
                    }
                }

                if (!drain.hasRemaining) {
                    val reason =
                        terminalReason.get()

                    if (reason != null) {
                        terminate(
                            reason,
                            notify = true,
                            reportTerminal = true,
                        )
                        return
                    }

                    break
                }
            }
        }
    }

    private fun setTerminalReason(
        reason: String,
    ) {
        terminalReason.compareAndSet(
            null,
            reason,
        )
        wake.trySend(Unit)
    }

    private fun terminate(
        reason: String,
        notify: Boolean,
        reportTerminal: Boolean,
    ): Boolean {
        if (!closed.compareAndSet(false, true)) {
            return false
        }

        runCatching {
            callback.asBinder().unlinkToDeath(
                this,
                0,
            )
        }

        frames.clear()
        wake.close()
        lease.close()
        job.cancel()

        if (notify) {
            try {
                callback.onClosed(
                    id,
                    reason,
                )
            } catch (_: RemoteException) {
                // The lease is already closed locally.
            }
        }

        if (reportTerminal) {
            onTerminal(
                id,
            )
        }

        return true
    }

    private class IpcBackpressure :
        RuntimeException()

    private companion object {
        const val FRAME_QUEUE_CAPACITY = 32
        const val MAX_FRAMES_PER_DRAIN = 8
    }
}
