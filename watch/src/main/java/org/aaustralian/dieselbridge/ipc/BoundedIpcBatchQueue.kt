// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

internal data class IpcQueueDrain<T>(
    val items: List<T>,
    val droppedTotal: Long,
    val hasRemaining: Boolean,
)

internal class BoundedIpcBatchQueue<T>(private val capacity: Int) {
    private val lock = Any()
    private val queue = ArrayDeque<T>()
    private var dropped = 0L

    init { require(capacity > 0) }

    fun offer(value: T) = synchronized(lock) {
        if (queue.size >= capacity) {
            queue.removeFirst()
            if (dropped < Long.MAX_VALUE) dropped++
        }
        queue.addLast(value)
    }

    fun drain(maximum: Int): IpcQueueDrain<T> {
        require(maximum > 0)
        return synchronized(lock) {
            val count = minOf(maximum, queue.size)
            val items = ArrayList<T>(count)
            repeat(count) { items += queue.removeFirst() }
            IpcQueueDrain(items, dropped, queue.isNotEmpty())
        }
    }
}
