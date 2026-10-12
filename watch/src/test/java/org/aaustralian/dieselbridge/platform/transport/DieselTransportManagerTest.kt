// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.platform.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.junit.Assert.*
import org.junit.Test

class DieselTransportManagerTest {
    private class Endpoint(override val maxFrameBytes: Int = 4096) : DieselTransportEndpoint {
        override val incoming: Flow<ByteArray> = MutableSharedFlow(extraBufferCapacity = 8)
        val sent = mutableListOf<ByteArray>()
        var closed = false
        override suspend fun send(frame: ByteArray) { sent += frame.copyOf() }
        override fun close() { closed = true }
    }

    private class Capability(
        override val transportId: String,
        override val providerId: String,
        private val factory: () -> Endpoint = { Endpoint() },
    ) : DieselTransportCapability, DieselProvider {
        val endpoints = mutableListOf<Endpoint>()
        override suspend fun open(): DieselTransportEndpoint = factory().also { endpoints += it }
    }

    @Test fun providerSelectionAppliesOnlyToNewLeases() = runTest {
        val registry = CapabilityRegistry()
        val low = Capability("gadgetbridge.bangle.console", "low")
        val high = Capability("gadgetbridge.bangle.console", "high")
        registry.register(low, low, priority = 10)
        registry.register(high, high, priority = 20)
        val manager = DieselTransportManager(registry)
        val client = manager.openClient("test")
        val first = client.open(high.id) as DieselTransportOpenResult.Opened
        assertEquals(1, high.endpoints.size)
        registry.setAvailability(high.id, high.providerId, ProviderAvailability.UNAVAILABLE, "test")
        val second = client.open(high.id) as DieselTransportOpenResult.Opened
        assertEquals(1, low.endpoints.size)
        assertFalse(high.endpoints.single().closed)
        assertEquals(DieselTransportSendResult.Success, first.lease.send(byteArrayOf(1)))
        assertEquals(DieselTransportSendResult.Success, second.lease.send(byteArrayOf(2)))
        client.close()
        assertTrue(high.endpoints.single().closed)
        assertTrue(low.endpoints.single().closed)
    }

    @Test fun leaseLimitsAndClientOwnershipAreEnforced() = runTest {
        val registry = CapabilityRegistry()
        val cap = Capability("transport.test", "provider")
        registry.register(cap, cap)
        val manager = DieselTransportManager(registry, DieselTransportLimits(maxClients=2,maxLeases=2,maxLeasesPerClient=2))
        val client = manager.openClient("owner")
        assertTrue(client.open(cap.id) is DieselTransportOpenResult.Opened)
        assertTrue(client.open(cap.id) is DieselTransportOpenResult.Opened)
        assertEquals(DieselTransportOpenResult.Rejected("client_lease_limit"), client.open(cap.id))
        assertEquals(2, manager.activeLeaseCount())
        client.close()
        assertEquals(0, manager.activeLeaseCount())
        assertTrue(cap.endpoints.all { it.closed })
    }

    @Test fun frameLimitIsIntersectionOfManagerAndProvider() = runTest {
        val registry = CapabilityRegistry()
        val endpoint = Endpoint(3)
        val cap = Capability("transport.frames", "provider") { endpoint }
        registry.register(cap, cap)
        val manager = DieselTransportManager(registry, DieselTransportLimits(maxFrameBytes=8))
        val lease = (manager.openClient("frames").open(cap.id) as DieselTransportOpenResult.Opened).lease
        assertEquals(3, lease.maxFrameBytes)
        assertEquals(DieselTransportSendResult.Rejected("frame_too_large"), lease.send(byteArrayOf(1,2,3,4)))
        assertTrue(endpoint.sent.isEmpty())
        assertEquals(DieselTransportSendResult.Success, lease.send(byteArrayOf(1,2,3)))
        manager.close()
        assertTrue(lease.isClosed)
        assertTrue(endpoint.closed)
    }

    @Test fun missingProviderIsUnavailable() = runTest {
        val manager = DieselTransportManager(CapabilityRegistry())
        val client = manager.openClient("none")
        assertSame(DieselTransportOpenResult.Unavailable, client.open("transport.missing"))
        assertEquals(0, manager.activeLeaseCount())
        client.close()
    }
}
