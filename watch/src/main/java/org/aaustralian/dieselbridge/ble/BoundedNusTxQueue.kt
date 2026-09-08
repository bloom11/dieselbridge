// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ble

/**
 * Byte-bounded FIFO for complete NUS lines. The caller owns synchronization.
 * Admission is all-or-nothing so congestion cannot enqueue a partial JSON line.
 * The GATT sender may additionally hold one already-dequeued MTU chunk in flight.
 */
internal class BoundedNusTxQueue(
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    init {
        require(maxBytes > 0)
    }

    private val chunks = ArrayDeque<ByteArray>()
    private var queuedBytes = 0

    fun offer(payload: ByteArray, chunkSize: Int): Boolean {
        require(chunkSize > 0)
        if (payload.size > maxBytes - queuedBytes) return false

        var offset = 0
        while (offset < payload.size) {
            val end = offset + minOf(chunkSize, payload.size - offset)
            chunks.addLast(payload.copyOfRange(offset, end))
            offset = end
        }
        queuedBytes += payload.size
        return true
    }

    fun removeFirstOrNull(): ByteArray? =
        chunks.removeFirstOrNull()?.also { queuedBytes -= it.size }

    fun clear() {
        chunks.clear()
        queuedBytes = 0
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 64 * 1024
    }
}
