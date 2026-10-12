// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.platform.transport

import kotlinx.coroutines.flow.Flow
import org.aaustralian.dieselbridge.platform.capability.DieselCapability

interface DieselTransportEndpoint : AutoCloseable {
    val maxFrameBytes: Int
    val incoming: Flow<ByteArray>
    suspend fun send(frame: ByteArray)
}

interface DieselTransportCapability : DieselCapability {
    val transportId: String
    override val id: String get() = transportId
    suspend fun open(): DieselTransportEndpoint
}

data class DieselTransportLimits(
    val maxClients: Int = 16,
    val maxLeases: Int = 16,
    val maxLeasesPerClient: Int = 4,
    val maxConcurrentSendsPerClient: Int = 4,
    val maxFrameBytes: Int = 4096,
    val openTimeoutMs: Long = 5000L,
    val sendTimeoutMs: Long = 5000L,
) {
    init {
        require(maxClients > 0)
        require(maxLeases > 0)
        require(maxLeasesPerClient > 0)
        require(maxConcurrentSendsPerClient > 0)
        require(maxFrameBytes > 0)
        require(openTimeoutMs > 0L)
        require(sendTimeoutMs > 0L)
    }
}

sealed interface DieselTransportOpenResult {
    data class Opened(val lease: DieselTransportLease) : DieselTransportOpenResult
    data object Unavailable : DieselTransportOpenResult
    data class Rejected(val reason: String) : DieselTransportOpenResult
    data class Failed(val reason: String) : DieselTransportOpenResult
}

sealed interface DieselTransportSendResult {
    data object Success : DieselTransportSendResult
    data class Rejected(val reason: String) : DieselTransportSendResult
    data class Failed(val reason: String) : DieselTransportSendResult
}

class DieselTransportIncomingFrameException(
    val transportReason: String,
) : IllegalStateException(transportReason)
