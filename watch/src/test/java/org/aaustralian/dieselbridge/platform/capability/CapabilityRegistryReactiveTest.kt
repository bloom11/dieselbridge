// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CapabilityRegistryReactiveTest {

    private class TestCapability(
        override val id: String,
    ) : DieselCapability

    private class TestProvider(
        override val providerId: String,
    ) : DieselProvider

    @Test
    fun observerCreatedBeforeRegistrationTracksSelection() {
        val registry = CapabilityRegistry()
        val observed = registry.observeActive(TEST_CAPABILITY)

        assertNull(observed.value)

        val lowCapability = TestCapability(TEST_CAPABILITY)
        val lowProvider = TestProvider("provider.low")

        registry.register(
            capability = lowCapability,
            provider = lowProvider,
            priority = 10,
        )

        assertEquals("provider.low", observed.value?.providerId)
        assertSame(lowCapability, observed.value?.capability)
    }

    @Test
    fun observerTracksFallbackAndRecoveryWithoutResubscribing() {
        val registry = CapabilityRegistry()

        val lowCapability = TestCapability(TEST_CAPABILITY)
        val highCapability = TestCapability(TEST_CAPABILITY)

        val lowProvider = TestProvider("provider.low")
        val highProvider = TestProvider("provider.high")

        registry.register(
            capability = lowCapability,
            provider = lowProvider,
            priority = 10,
        )

        val observed = registry.observeActive(TEST_CAPABILITY)
        val originalFlow = observed

        assertEquals("provider.low", observed.value?.providerId)

        registry.register(
            capability = highCapability,
            provider = highProvider,
            priority = 100,
        )

        assertEquals("provider.high", observed.value?.providerId)
        assertSame(highCapability, observed.value?.capability)

        registry.setAvailability(
            capabilityId = TEST_CAPABILITY,
            providerId = "provider.high",
            availability = ProviderAvailability.ERROR,
            reason = "test failure",
        )

        assertEquals("provider.low", observed.value?.providerId)
        assertSame(lowCapability, observed.value?.capability)

        registry.setAvailability(
            capabilityId = TEST_CAPABILITY,
            providerId = "provider.high",
            availability = ProviderAvailability.AVAILABLE,
        )

        assertEquals("provider.high", observed.value?.providerId)
        assertSame(highCapability, observed.value?.capability)

        val observedAgain =
            registry.observeActive(TEST_CAPABILITY)

        assertSame(originalFlow, observedAgain)
    }

    @Test
    fun observerEmitsNullWhenLastUsableProviderDisappears() {
        val registry = CapabilityRegistry()

        val capability = TestCapability(TEST_CAPABILITY)
        val provider = TestProvider("provider.only")

        registry.register(
            capability = capability,
            provider = provider,
        )

        val observed = registry.observeActive(TEST_CAPABILITY)

        assertEquals("provider.only", observed.value?.providerId)

        registry.unregister(
            capabilityId = TEST_CAPABILITY,
            providerId = "provider.only",
        )

        assertNull(observed.value)
    }

    @Test
    fun observerTracksProviderWideRemovalAndFallback() {
        val registry = CapabilityRegistry()

        val lowCapability = TestCapability(TEST_CAPABILITY)
        val highCapability = TestCapability(TEST_CAPABILITY)

        registry.register(
            capability = lowCapability,
            provider = TestProvider("provider.low"),
            priority = 10,
        )

        registry.register(
            capability = highCapability,
            provider = TestProvider("provider.high"),
            priority = 100,
        )

        val observed = registry.observeActive(TEST_CAPABILITY)

        assertEquals("provider.high", observed.value?.providerId)

        registry.unregisterProvider("provider.high")

        assertEquals("provider.low", observed.value?.providerId)
        assertSame(lowCapability, observed.value?.capability)
    }

    companion object {
        private const val TEST_CAPABILITY = "test.reactive"
    }
}
