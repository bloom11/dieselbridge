// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedNusTxQueueTest {
    @Test
    fun rejectsWholeLineWithoutCorruptingQueuedFramesAndRecoversAfterDrain() {
        val queue = BoundedNusTxQueue(maxBytes = 10)
        assertTrue(queue.offer("first\r\n".toByteArray(), chunkSize = 3))
        repeat(1000) {
            assertFalse(queue.offer("drop\r\n".toByteArray(), chunkSize = 3))
        }
        assertArrayEquals("fir".toByteArray(), queue.removeFirstOrNull())
        assertArrayEquals("st\r".toByteArray(), queue.removeFirstOrNull())
        assertArrayEquals("\n".toByteArray(), queue.removeFirstOrNull())
        assertNull(queue.removeFirstOrNull())
        assertTrue(queue.offer("next\r\n".toByteArray(), chunkSize = 10))
        assertArrayEquals("next\r\n".toByteArray(), queue.removeFirstOrNull())
    }

    @Test
    fun exactByteBudgetRecoversAsChunksDrainAndClearResetsAccounting() {
        val queue = BoundedNusTxQueue(maxBytes = 8)
        assertTrue(queue.offer(ByteArray(8) { 1 }, chunkSize = 4))
        assertFalse(queue.offer(byteArrayOf(2), chunkSize = 4))
        assertArrayEquals(ByteArray(4) { 1 }, queue.removeFirstOrNull())
        assertTrue(queue.offer(ByteArray(4) { 2 }, chunkSize = 4))
        assertArrayEquals(ByteArray(4) { 1 }, queue.removeFirstOrNull())
        assertArrayEquals(ByteArray(4) { 2 }, queue.removeFirstOrNull())
        assertNull(queue.removeFirstOrNull())
        assertTrue(queue.offer(ByteArray(8), chunkSize = 4))
        queue.clear()
        assertTrue(queue.offer(ByteArray(8), chunkSize = 8))
        assertArrayEquals(ByteArray(8), queue.removeFirstOrNull())
        assertNull(queue.removeFirstOrNull())
        assertFalse(queue.offer(ByteArray(9), chunkSize = 4))
        assertNull(queue.removeFirstOrNull())
    }
}
