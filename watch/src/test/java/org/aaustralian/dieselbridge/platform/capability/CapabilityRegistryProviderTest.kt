// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

import org.aaustralian.dieselbridge.platform.diagnostic.PlatformDiagnostics
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryProviderTest {

    private class TestCapability(
        override val id: String,
    ) : DieselCapability

    private class TestProvider(
        override val providerId: String,
    ) : DieselProvider

    @Test
    fun oneProviderCanExposeMultipleCapabilities() {
        val registry = CapabilityRegistry()
        val provider = TestProvider("provider.multi")

        val first = TestCapability("first")
        val second = TestCapability("second")

        registry.register(
            capability = first,
            provider = provider,
            priority = 50,
        )

        registry.register(
            capability = second,
            provider = provider,
            priority = 80,
        )

        assertSame(first, registry.resolve("first"))
        assertSame(second, registry.resolve("second"))

        assertEquals(
            "provider.multi",
            registry.activeProvider("first")?.providerId,
        )

        assertEquals(
            "provider.multi",
            registry.activeProvider("second")?.providerId,
        )

        assertEquals(
            50,
            registry.activeProvider("first")?.priority,
        )

        assertEquals(
            80,
            registry.activeProvider("second")?.priority,
        )
    }

    @Test
    fun unregisterProviderRemovesAllItsBindingsAndActivatesFallbacks() {
        val registry = CapabilityRegistry()

        val primaryProvider =
            TestProvider("provider.primary")

        val fallbackProvider =
            TestProvider("provider.fallback")

        val firstPrimary = TestCapability("first")
        val secondPrimary = TestCapability("second")
        val firstFallback = TestCapability("first")

        registry.register(
            capability = firstPrimary,
            provider = primaryProvider,
            priority = 100,
        )

        registry.register(
            capability = secondPrimary,
            provider = primaryProvider,
            priority = 100,
        )

        registry.register(
            capability = firstFallback,
            provider = fallbackProvider,
            priority = 50,
        )

        val removed =
            registry.unregisterProvider("provider.primary")

        assertEquals(2, removed.size)

        assertSame(
            firstFallback,
            registry.resolve("first"),
        )

        assertNull(
            registry.resolve("second"),
        )

        assertEquals(
            "provider.fallback",
            registry.activeProvider("first")?.providerId,
        )
    }

    @Test
    fun unknownProviderAvailabilityChangeReturnsFalse() {
        val registry = CapabilityRegistry()

        assertFalse(
            registry.setAvailability(
                capabilityId = "missing",
                providerId = "missing.provider",
                availability = ProviderAvailability.ERROR,
            ),
        )
    }

    @Test
    fun diagnosticsRecordActiveProviderTransitions() {
        val diagnostics =
            PlatformDiagnostics(recentCapacity = 20)

        val registry =
            CapabilityRegistry(diagnostics = diagnostics)

        registry.register(
            capability = TestCapability("test"),
            provider = TestProvider("provider.low"),
            priority = 50,
        )

        registry.register(
            capability = TestCapability("test"),
            provider = TestProvider("provider.high"),
            priority = 100,
        )

        assertTrue(
            diagnostics.state.value.recentRecords.any {
                it.type == "capability.select" &&
                    it.message.contains(
                        "provider.low -> provider.high",
                    )
            },
        )

        registry.setAvailability(
            capabilityId = "test",
            providerId = "provider.high",
            availability = ProviderAvailability.ERROR,
            reason = "simulated failure",
        )

        assertTrue(
            diagnostics.state.value.recentRecords.any {
                it.type == "capability.select" &&
                    it.message.contains(
                        "provider.high -> provider.low",
                    )
            },
        )
    }
}
