// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.aaustralian.dieselbridge.api.DieselDynamicResourceRegistration
import org.aaustralian.dieselbridge.api.DieselPublicDecodeResult
import org.aaustralian.dieselbridge.api.DieselResourceDescriptor
import org.aaustralian.dieselbridge.platform.action.DieselActionDispatcher
import org.aaustralian.dieselbridge.platform.action.DieselActionKey
import org.aaustralian.dieselbridge.platform.action.DieselActionRegistration
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapability
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationOptions
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationUpdate
import org.aaustralian.dieselbridge.protocol.DieselValue

internal interface DieselRemoteProviderResource : AutoCloseable {
    val registrationId: Long
    val isClosed: Boolean
}

internal data class DieselRemoteActionTicket(
    val requestId: Long,
    val deferred: CompletableDeferred<DieselActionResult<DieselValue>>,
)

internal class DieselRemoteActionPendingBook(
    private val maximum: Int = 4,
) {
    private val lock = Any()
    private val nextRequestId = AtomicLong(1L)
    private val pending =
        linkedMapOf<Long, CompletableDeferred<DieselActionResult<DieselValue>>>()

    init {
        require(maximum > 0)
    }

    fun tryOpen(): DieselRemoteActionTicket? =
        synchronized(lock) {
            if (pending.size >= maximum) return@synchronized null

            val requestId = nextRequestId.getAndIncrement()
            val deferred =
                CompletableDeferred<DieselActionResult<DieselValue>>()
            pending[requestId] = deferred
            DieselRemoteActionTicket(requestId, deferred)
        }

    fun complete(
        requestId: Long,
        result: DieselActionResult<DieselValue>,
    ): Boolean =
        synchronized(lock) {
            pending[requestId]?.complete(result) ?: false
        }

    fun remove(requestId: Long) {
        synchronized(lock) {
            pending.remove(requestId)
        }
    }

    fun closeAll() {
        val toClose =
            synchronized(lock) {
                pending.values.toList().also {
                    pending.clear()
                }
            }

        toClose.forEach {
            it.complete(DieselActionResult.Unavailable)
        }
    }

    fun size(): Int =
        synchronized(lock) {
            pending.size
        }
}

internal class DieselRemoteActionProviderResource private constructor(
    override val registrationId: Long,
    private val resourceId: String,
    private val callback: IDieselProviderCallback,
    private val actionRegistration: DieselActionRegistration,
    private val descriptorRegistration: DieselDynamicResourceRegistration,
    private val onCallbackDead: (Long) -> Unit,
) :
    DieselRemoteProviderResource,
    IBinder.DeathRecipient {

    private val closed = AtomicBoolean(false)
    private val pending = DieselRemoteActionPendingBook()

    override val isClosed: Boolean
        get() = closed.get()

    init {
        callback.asBinder().linkToDeath(this, 0)
    }

    suspend fun invoke(
        input: DieselValue,
    ): DieselActionResult<DieselValue> {
        if (closed.get()) return DieselActionResult.Unavailable

        val ticket =
            pending.tryOpen()
                ?: return DieselActionResult.Rejected(
                    "external_provider_busy",
                )

        try {
            callback.onActionRequest(
                registrationId,
                ticket.requestId,
                resourceId,
                DieselIpcCodec.encodeValue(input),
            )
        } catch (_: RemoteException) {
            pending.remove(ticket.requestId)
            return DieselActionResult.Unavailable
        }

        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                ticket.deferred.await()
            }
                ?: DieselActionResult.Failed(
                    "external_provider_timeout",
                    null,
                )
        } finally {
            pending.remove(ticket.requestId)
        }
    }

    fun complete(
        requestId: Long,
        result: DieselActionResult<DieselValue>,
    ): Boolean =
        !closed.get() && pending.complete(requestId, result)

    override fun binderDied() {
        close()
        onCallbackDead(registrationId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        runCatching {
            callback.asBinder().unlinkToDeath(this, 0)
        }
        pending.closeAll()
        actionRegistration.close()
        descriptorRegistration.close()
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 5_000L

        fun create(
            registrationId: Long,
            descriptor: DieselResourceDescriptor,
            callback: IDieselProviderCallback,
            actions: DieselActionDispatcher,
            ownerId: String,
            descriptorRegistration: DieselDynamicResourceRegistration,
            onCallbackDead: (Long) -> Unit,
        ): DieselRemoteActionProviderResource {
            val holder =
                AtomicReference<DieselRemoteActionProviderResource?>(null)

            val registration =
                actions.register(
                    DieselActionKey(
                        descriptor.id,
                        DieselValue::class.java,
                        DieselValue::class.java,
                    ),
                    ownerId,
                ) { input ->
                    holder.get()?.invoke(input)
                        ?: DieselActionResult.Unavailable
                }

            val resource =
                try {
                    DieselRemoteActionProviderResource(
                        registrationId,
                        descriptor.id,
                        callback,
                        registration,
                        descriptorRegistration,
                        onCallbackDead,
                    )
                } catch (error: RemoteException) {
                    registration.close()
                    descriptorRegistration.close()
                    throw error
                }

            holder.set(resource)
            return resource
        }
    }
}

internal class DieselRemoteObservationProviderResource private constructor(
    override val registrationId: Long,
    val descriptor: DieselResourceDescriptor,
    private val callback: IDieselProviderCallback,
    private val registry: CapabilityRegistry,
    private val descriptorRegistration: DieselDynamicResourceRegistration,
    private val onCallbackDead: (Long) -> Unit,
    override val providerId: String,
) :
    DieselRemoteProviderResource,
    SensorObservationCapability,
    DieselProvider,
    IBinder.DeathRecipient {

    private data class Acquisition(
        val send: (SensorObservationUpdate) -> Boolean,
        val close: () -> Unit,
        var serverDroppedTotal: Long = 0L,
    )

    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val acquisitions = linkedMapOf<Long, Acquisition>()
    private val nextAcquisitionId = AtomicLong(1L)

    override val isClosed: Boolean
        get() = closed.get()

    override val capabilityId =
        SensorObservationCapabilityId(descriptor.id)

    init {
        callback.asBinder().linkToDeath(this, 0)
    }

    override fun observe(
        options: SensorObservationOptions,
    ): Flow<SensorObservationUpdate> =
        callbackFlow {
            if (closed.get()) {
                trySend(
                    SensorObservationUpdate.Unavailable(
                        "external_provider_closed",
                    ),
                )
                close()
                return@callbackFlow
            }

            val acquisitionId = nextAcquisitionId.getAndIncrement()
            val acquisition =
                Acquisition(
                    send = { update -> trySend(update).isSuccess },
                    close = { close() },
                )

            synchronized(lock) {
                acquisitions[acquisitionId] = acquisition
            }

            try {
                callback.onObservationStart(
                    registrationId,
                    acquisitionId,
                    descriptor.id,
                    options.preferredSamplePeriodMs,
                )
            } catch (_: RemoteException) {
                synchronized(lock) {
                    acquisitions.remove(acquisitionId)
                }
                trySend(
                    SensorObservationUpdate.Unavailable(
                        "external_provider_callback_dead",
                    ),
                )
                close()
                return@callbackFlow
            }

            awaitClose {
                synchronized(lock) {
                    acquisitions.remove(acquisitionId)
                }
                runCatching {
                    callback.onObservationStop(
                        registrationId,
                        acquisitionId,
                    )
                }
            }
        }.buffer(
            capacity = OBSERVATION_INGRESS_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    fun push(
        acquisitionId: Long,
        updateBundle: android.os.Bundle,
    ): String? {
        if (closed.get()) return "provider_closed"

        val acquisition =
            synchronized(lock) {
                acquisitions[acquisitionId]
            } ?: return "unknown_acquisition"

        val serverDrops =
            synchronized(lock) {
                acquisition.serverDroppedTotal
            }

        val update =
            when (
                val decoded =
                    DieselIpcProviderCodec.decodeObservationUpdate(
                        updateBundle,
                        logicalId,
                        providerId,
                        serverDrops,
                    )
            ) {
                is DieselPublicDecodeResult.Invalid ->
                    return decoded.reason

                is DieselPublicDecodeResult.Value ->
                    decoded.value
            }

        if (acquisition.send(update)) return null

        if (update is SensorObservationUpdate.Sample) {
            synchronized(lock) {
                acquisition.serverDroppedTotal =
                    if (acquisition.serverDroppedTotal == Long.MAX_VALUE) {
                        Long.MAX_VALUE
                    } else {
                        acquisition.serverDroppedTotal + 1L
                    }
            }
        }

        return "provider_ingress_full"
    }

    fun setAvailability(
        availability: ProviderAvailability,
        reason: String?,
    ): Boolean =
        !closed.get() &&
            registry.setAvailability(
                descriptor.id,
                providerId,
                availability,
                reason,
            )

    override fun binderDied() {
        close()
        onCallbackDead(registrationId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        runCatching {
            callback.asBinder().unlinkToDeath(this, 0)
        }

        registry.unregister(
            descriptor.id,
            providerId,
        )

        val active =
            synchronized(lock) {
                acquisitions.values.toList().also {
                    acquisitions.clear()
                }
            }

        active.forEach {
            it.close()
        }

        descriptorRegistration.close()
    }

    companion object {
        private const val OBSERVATION_INGRESS_CAPACITY = 32

        fun create(
            registrationId: Long,
            descriptor: DieselResourceDescriptor,
            callback: IDieselProviderCallback,
            registry: CapabilityRegistry,
            priority: Int,
            descriptorRegistration: DieselDynamicResourceRegistration,
            providerId: String,
            onCallbackDead: (Long) -> Unit,
        ): DieselRemoteObservationProviderResource? {
            val resource =
                try {
                    DieselRemoteObservationProviderResource(
                        registrationId,
                        descriptor,
                        callback,
                        registry,
                        descriptorRegistration,
                        onCallbackDead,
                        providerId,
                    )
                } catch (_: RemoteException) {
                    descriptorRegistration.close()
                    return null
                }

            if (resource.isClosed) {
                resource.close()
                return null
            }

            try {
                registry.register(
                    capability = resource,
                    provider = resource,
                    priority = priority,
                )
            } catch (error: RuntimeException) {
                resource.close()
                throw error
            }

            if (resource.isClosed) {
                registry.unregister(
                    descriptor.id,
                    providerId,
                )
                return null
            }

            return resource
        }
    }
}
