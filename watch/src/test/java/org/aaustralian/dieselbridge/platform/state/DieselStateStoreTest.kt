// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DieselStateStoreTest {
    private val key = DieselStateKey("companion.test.state", String::class.java)

    @Test
    fun observerIsStableAcrossPublisherReplacement() {
        var now = 1_000L
        val store = DieselStateStore { now }
        val observed = store.observe(key)
        assertSame(observed, store.observe(key))
        assertNull(observed.value)

        val first = store.registerPublisher(key, "first")
        assertTrue(first.set("alpha"))
        val alpha = requireNotNull(observed.value)
        assertEquals("first", alpha.ownerId)
        assertEquals(1_000L, alpha.updatedAtMs)
        first.close()
        assertNull(observed.value)

        now = 2_000L
        val second = store.registerPublisher(key, "second")
        assertTrue(second.set("beta"))
        val beta = requireNotNull(observed.value)
        assertEquals("beta", beta.value)
        assertEquals("second", beta.ownerId)
        assertTrue(beta.revision > alpha.revision)
        second.close()
    }

    @Test
    fun onlyOnePublisherMayOwnAKey() {
        val store = DieselStateStore()
        val first = store.registerPublisher(key, "first")
        try {
            store.registerPublisher(key, "second")
            fail("Expected duplicate publisher registration to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("already has active publisher"))
        } finally {
            first.close()
        }
    }

    @Test
    fun stalePublisherCannotOverwriteReplacementOwner() {
        val store = DieselStateStore()
        val first = store.registerPublisher(key, "first")
        assertTrue(first.set("old"))
        first.close()

        val second = store.registerPublisher(key, "second")
        assertTrue(second.set("new"))
        assertFalse(first.set("stale"))
        assertFalse(first.clear())
        assertEquals("new", store.current(key)?.value)
        second.close()
    }

    @Test
    fun clearRetainsPublisherOwnership() {
        val store = DieselStateStore()
        val publisher = store.registerPublisher(key, "owner")
        assertTrue(publisher.set("first"))
        val firstRevision = requireNotNull(store.current(key)).revision
        assertTrue(publisher.clear())
        assertNull(store.current(key))
        assertTrue(publisher.set("second"))
        assertTrue(requireNotNull(store.current(key)).revision > firstRevision)
        publisher.close()
    }

    @Test
    fun logicalKeyCannotChangeType() {
        val store = DieselStateStore()
        store.observe(key)
        val incompatible = DieselStateKey(key.id, java.lang.Integer::class.java)
        try {
            store.observe(incompatible)
            fail("Expected state-key type mismatch to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("already registered with type"))
        }
    }

    @Test
    fun closingEmptyPublisherLeavesKeyAvailable() {
        val store = DieselStateStore()
        store.registerPublisher(key, "first").close()
        val second = store.registerPublisher(key, "second")
        assertFalse(second.isClosed)
        assertTrue(second.set("value"))
        second.close()
    }
}
