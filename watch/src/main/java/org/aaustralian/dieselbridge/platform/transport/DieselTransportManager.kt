// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.platform.transport

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry

class DieselTransportManager(
    private val registry: CapabilityRegistry,
    private val limits: DieselTransportLimits = DieselTransportLimits(),
) : AutoCloseable {
    private data class ClientRecord(
        val ownerId: String,
        val leases: MutableMap<Long, DieselTransportLease> = linkedMapOf(),
        var pendingOpens: Int = 0,
        var activeSends: Int = 0,
    )

    private val lock = Any()
    private val clients = linkedMapOf<Long, ClientRecord>()
    private var nextClientId = 1L
    private var nextLeaseId = 1L
    private var activeLeases = 0
    private var pendingOpens = 0
    private var closed = false

    fun openClient(ownerId: String): DieselTransportClient {
        require(ownerId.isNotBlank())
        val id = synchronized(lock) {
            check(!closed) { "Transport manager is closed" }
            check(clients.size < limits.maxClients) { "Transport client limit reached" }
            val id = nextClientId++
            clients[id] = ClientRecord(ownerId)
            id
        }
        return DieselTransportClient(id, ownerId, this)
    }

    internal suspend fun open(clientId: Long, resourceId: String): DieselTransportOpenResult {
        if (resourceId.isBlank()) return DieselTransportOpenResult.Rejected("invalid_transport_id")
        val reject = synchronized(lock) {
            val r = clients[clientId]
            when {
                closed -> "manager_closed"
                r == null -> "client_closed"
                r.leases.size + r.pendingOpens >= limits.maxLeasesPerClient -> "client_lease_limit"
                activeLeases + pendingOpens >= limits.maxLeases -> "global_lease_limit"
                else -> { r.pendingOpens++; pendingOpens++; null }
            }
        }
        if (reject != null) return DieselTransportOpenResult.Rejected(reject)

        val capability = registry.resolve(resourceId) as? DieselTransportCapability
        if (capability == null) {
            cancelReservation(clientId)
            return DieselTransportOpenResult.Unavailable
        }

        val endpoint = try {
            withTimeoutOrNull(limits.openTimeoutMs) { capability.open() }
        } catch (c: CancellationException) {
            cancelReservation(clientId); throw c
        } catch (_: Exception) {
            cancelReservation(clientId)
            return DieselTransportOpenResult.Failed("provider_open_failed")
        }
        if (endpoint == null) {
            cancelReservation(clientId)
            return DieselTransportOpenResult.Failed("open_timeout")
        }
        if (endpoint.maxFrameBytes <= 0) {
            cancelReservation(clientId); runCatching { endpoint.close() }
            return DieselTransportOpenResult.Failed("invalid_provider_frame_limit")
        }

        var lease: DieselTransportLease? = null
        val finalizeReject = synchronized(lock) {
            pendingOpens--
            val r = clients[clientId]
            if (r != null && r.pendingOpens > 0) r.pendingOpens--
            when {
                closed -> "manager_closed"
                r == null -> "client_closed"
                else -> {
                    val leaseId = nextLeaseId++
                    lease = DieselTransportLease(
                        leaseId, resourceId, clientId, endpoint,
                        minOf(limits.maxFrameBytes, endpoint.maxFrameBytes), this,
                    )
                    r.leases[leaseId] = requireNotNull(lease)
                    activeLeases++
                    null
                }
            }
        }
        if (finalizeReject != null) {
            runCatching { endpoint.close() }
            return DieselTransportOpenResult.Rejected(finalizeReject)
        }
        return DieselTransportOpenResult.Opened(requireNotNull(lease))
    }

    private fun cancelReservation(clientId: Long) = synchronized(lock) {
        if (pendingOpens > 0) pendingOpens--
        clients[clientId]?.let { if (it.pendingOpens > 0) it.pendingOpens-- }
    }

    internal fun tryAcquireSend(clientId: Long): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val r = clients[clientId] ?: return@synchronized false
        if (r.activeSends >= limits.maxConcurrentSendsPerClient) return@synchronized false
        r.activeSends++
        true
    }

    internal fun releaseSend(clientId: Long) = synchronized(lock) {
        clients[clientId]?.let { if (it.activeSends > 0) it.activeSends-- }
    }

    internal fun sendTimeoutMs(): Long = limits.sendTimeoutMs

    internal fun releaseLease(clientId: Long, leaseId: Long, lease: DieselTransportLease) = synchronized(lock) {
        val r = clients[clientId] ?: return@synchronized
        if (r.leases[leaseId] === lease) {
            r.leases.remove(leaseId)
            if (activeLeases > 0) activeLeases--
        }
    }

    internal fun closeClient(clientId: Long) {
        val leases = synchronized(lock) {
            val r = clients.remove(clientId) ?: return
            activeLeases = maxOf(0, activeLeases - r.leases.size)
            r.leases.values.toList()
        }
        leases.forEach { it.closeFromManager() }
    }

    internal fun isClientActive(clientId: Long): Boolean = synchronized(lock) { !closed && clientId in clients }
    internal fun activeLeaseCount(): Int = synchronized(lock) { activeLeases }

    override fun close() {
        val leases = synchronized(lock) {
            if (closed) return
            closed = true
            clients.values.flatMap { it.leases.values }.also {
                clients.clear(); activeLeases = 0
            }
        }
        leases.forEach { it.closeFromManager() }
    }
}

class DieselTransportClient internal constructor(
    internal val clientId: Long,
    val ownerId: String,
    private val manager: DieselTransportManager,
) : AutoCloseable {
    val isClosed: Boolean get() = !manager.isClientActive(clientId)
    suspend fun open(resourceId: String): DieselTransportOpenResult = manager.open(clientId, resourceId)
    override fun close() = manager.closeClient(clientId)
}

class DieselTransportLease internal constructor(
    val id: Long,
    val resourceId: String,
    internal val clientId: Long,
    private val endpoint: DieselTransportEndpoint,
    val maxFrameBytes: Int,
    private val manager: DieselTransportManager,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    val incoming: Flow<ByteArray> = flow {
        endpoint.incoming.collect { frame ->
            if (closed.get()) return@collect
            if (frame.size > maxFrameBytes) {
                close()
                throw DieselTransportIncomingFrameException("incoming_frame_too_large")
            }
            emit(frame.copyOf())
        }
    }

    suspend fun send(frame: ByteArray): DieselTransportSendResult {
        if (closed.get()) return DieselTransportSendResult.Rejected("lease_closed")
        if (frame.size > maxFrameBytes) return DieselTransportSendResult.Rejected("frame_too_large")
        if (!manager.tryAcquireSend(clientId)) return DieselTransportSendResult.Rejected("send_limit_or_client_closed")
        return try {
            val ok = withTimeoutOrNull(manager.sendTimeoutMs()) {
                endpoint.send(frame.copyOf()); true
            } ?: false
            if (ok) DieselTransportSendResult.Success else DieselTransportSendResult.Failed("send_timeout")
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            DieselTransportSendResult.Failed("provider_send_failed")
        } finally {
            manager.releaseSend(clientId)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        manager.releaseLease(clientId, id, this)
        runCatching { endpoint.close() }
    }

    internal fun closeFromManager() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { endpoint.close() }
    }
}
