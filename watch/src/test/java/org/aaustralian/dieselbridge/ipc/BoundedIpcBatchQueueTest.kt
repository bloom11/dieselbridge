// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedIpcBatchQueueTest {
    @Test fun dropsOldestAndCountsLoss() {
        val queue = BoundedIpcBatchQueue<Int>(3)
        queue.offer(1); queue.offer(2); queue.offer(3); queue.offer(4); queue.offer(5)
        val first = queue.drain(2)
        assertEquals(listOf(3, 4), first.items)
        assertEquals(2L, first.droppedTotal)
        assertTrue(first.hasRemaining)
        val second = queue.drain(8)
        assertEquals(listOf(5), second.items)
        assertEquals(2L, second.droppedTotal)
        assertFalse(second.hasRemaining)
    }
}
