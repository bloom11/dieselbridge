// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

import org.aaustralian.dieselbridge.platform.diagnostic.PlatformDiagnostics
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.aaustralian.dieselbridge.platform.provider.ProviderStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CapabilityRegistryTest {

    private class TestCapability(
        override val id: String,
    ) : DieselCapability

    private class TestProvider(
        override val providerId: String,
    ) : DieselProvider

    @Test
    fun registerAndResolveCapability() {
        val registry = CapabilityRegistry()
        val capability = TestCapability("test")
        val provider = TestProvider("provider.test")

        registry.register(
            capability = capability,
            provider = provider,
            priority = 50,
        )

        assertSame(capability, registry.resolve("test"))
        assertTrue(registry.contains("test"))
        assertEquals(setOf("test"), registry.ids())

        val active = registry.activeProvider("test")

        assertEquals("provider.test", active?.providerId)
        assertEquals(ProviderStatus.ACTIVE, active?.status)
        assertEquals(50, active?.priority)
    }

    @Test
    fun resolveUnknownCapabilityReturnsNull() {
        val registry = CapabilityRegistry()

        assertNull(registry.resolve("missing"))
        assertNull(registry.activeProvider("missing"))
        assertFalse(registry.contains("missing"))
    }

    @Test
    fun duplicateProviderBindingIsRejected() {
        val registry = CapabilityRegistry()

        registry.register(
            capability = TestCapability("test"),
            provider = TestProvider("provider.test"),
        )

        try {
            registry.register(
                capability = TestCapability("test"),
                provider = TestProvider("provider.test"),
            )

            fail("Expected duplicate provider binding to fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun highestPriorityProviderBecomesActive() {
        val registry = CapabilityRegistry()

        val lowCapability = TestCapability("test")
        val highCapability = TestCapability("test")

        registry.register(
            capability = lowCapability,
            provider = TestProvider("provider.low"),
            priority = 50,
        )

        registry.register(
            capability = highCapability,
            provider = TestProvider("provider.high"),
            priority = 100,
        )

        assertSame(highCapability, registry.resolve("test"))

        val providers =
            registry.providers("test")
                .associateBy { it.providerId }

        assertEquals(
            ProviderStatus.ACTIVE,
            providers["provider.high"]?.status,
        )

        assertEquals(
            ProviderStatus.STANDBY,
            providers["provider.low"]?.status,
        )
    }

    @Test
    fun equalPriorityUsesRegistrationOrder() {
        val registry = CapabilityRegistry()

        val first = TestCapability("test")
        val second = TestCapability("test")

        registry.register(
            capability = first,
            provider = TestProvider("provider.first"),
            priority = 100,
        )

        registry.register(
            capability = second,
            provider = TestProvider("provider.second"),
            priority = 100,
        )

        assertSame(first, registry.resolve("test"))
        assertEquals(
            "provider.first",
            registry.activeProvider("test")?.providerId,
        )
    }

    @Test
    fun providerFailureFallsBackAndRecoveryRestoresPriority() {
        val registry = CapabilityRegistry()

        val highCapability = TestCapability("test")
        val lowCapability = TestCapability("test")

        registry.register(
            capability = highCapability,
            provider = TestProvider("provider.high"),
            priority = 100,
        )

        registry.register(
            capability = lowCapability,
            provider = TestProvider("provider.low"),
            priority = 50,
        )

        assertSame(highCapability, registry.resolve("test"))

        assertTrue(
            registry.setAvailability(
                capabilityId = "test",
                providerId = "provider.high",
                availability = ProviderAvailability.ERROR,
                reason = "simulated failure",
            ),
        )

        assertSame(lowCapability, registry.resolve("test"))

        val failedProviders =
            registry.providers("test")
                .associateBy { it.providerId }

        assertEquals(
            ProviderStatus.ERROR,
            failedProviders["provider.high"]?.status,
        )

        assertEquals(
            ProviderStatus.ACTIVE,
            failedProviders["provider.low"]?.status,
        )

        assertEquals(
            "simulated failure",
            failedProviders["provider.high"]?.reason,
        )

        assertTrue(
            registry.setAvailability(
                capabilityId = "test",
                providerId = "provider.high",
                availability = ProviderAvailability.AVAILABLE,
            ),
        )

        assertSame(highCapability, registry.resolve("test"))

        val recoveredProviders =
            registry.providers("test")
                .associateBy { it.providerId }

        assertEquals(
            ProviderStatus.ACTIVE,
            recoveredProviders["provider.high"]?.status,
        )

        assertEquals(
            ProviderStatus.STANDBY,
            recoveredProviders["provider.low"]?.status,
        )
    }

    @Test
    fun unavailableProviderIsNotSelected() {
        val registry = CapabilityRegistry()

        registry.register(
            capability = TestCapability("test"),
            provider = TestProvider("provider.unavailable"),
            priority = 100,
            initialAvailability = ProviderAvailability.UNAVAILABLE,
        )

        assertNull(registry.resolve("test"))

        assertEquals(
            ProviderStatus.UNAVAILABLE,
            registry.providers("test").single().status,
        )
    }

    @Test
    fun unregisterOneProviderActivatesFallback() {
        val registry = CapabilityRegistry()

        val highCapability = TestCapability("test")
        val lowCapability = TestCapability("test")

        registry.register(
            capability = highCapability,
            provider = TestProvider("provider.high"),
            priority = 100,
        )

        registry.register(
            capability = lowCapability,
            provider = TestProvider("provider.low"),
            priority = 50,
        )

        assertSame(
            highCapability,
            registry.unregister(
                capabilityId = "test",
                providerId = "provider.high",
            ),
        )

        assertSame(lowCapability, registry.resolve("test"))
        assertEquals(
            "provider.low",
            registry.activeProvider("test")?.providerId,
        )
    }

    @Test
    fun diagnosticsTrackProviderSelectionAndStatus() {
        val diagnostics =
            PlatformDiagnostics(recentCapacity = 10)

        val registry =
            CapabilityRegistry(diagnostics = diagnostics)

        registry.register(
            capability = TestCapability("test"),
            provider = TestProvider("provider.test"),
            priority = 75,
        )

        var snapshot = diagnostics.state.value

        assertEquals(1, snapshot.providers.size)
        assertEquals(
            ProviderStatus.ACTIVE,
            snapshot.providers.single().status,
        )

        assertTrue(
            snapshot.recentRecords.any {
                it.type == "capability.register"
            },
        )

        registry.setAvailability(
            capabilityId = "test",
            providerId = "provider.test",
            availability = ProviderAvailability.ERROR,
            reason = "test error",
        )

        snapshot = diagnostics.state.value

        assertEquals(
            ProviderStatus.ERROR,
            snapshot.providers.single().status,
        )

        assertEquals(
            "test error",
            snapshot.providers.single().reason,
        )

        assertTrue(
            snapshot.recentRecords.any {
                it.type == "provider.status"
            },
        )
    }
}
