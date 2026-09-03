// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

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

    @Test
    fun registerAndResolveCapability() {
        val registry = CapabilityRegistry()
        val capability = TestCapability("test")

        registry.register(capability)

        assertSame(capability, registry.resolve("test"))
        assertTrue(registry.contains("test"))
        assertEquals(setOf("test"), registry.ids())
    }

    @Test
    fun resolveUnknownCapabilityReturnsNull() {
        val registry = CapabilityRegistry()

        assertNull(registry.resolve("missing"))
        assertFalse(registry.contains("missing"))
    }

    @Test
    fun duplicateCapabilityIsRejected() {
        val registry = CapabilityRegistry()

        registry.register(TestCapability("test"))

        try {
            registry.register(TestCapability("test"))
            fail("Expected duplicate capability registration to fail")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun unregisterRemovesCapability() {
        val registry = CapabilityRegistry()
        val capability = TestCapability("test")

        registry.register(capability)

        assertSame(capability, registry.unregister("test"))
        assertNull(registry.resolve("test"))
    }
}
