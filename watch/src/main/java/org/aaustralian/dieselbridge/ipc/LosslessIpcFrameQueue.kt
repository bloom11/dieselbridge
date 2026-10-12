// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

internal data class LosslessIpcFrameDrain(
    val frames: List<ByteArray>,
    val hasRemaining: Boolean,
)

/**
 * Bounded FIFO used for protocol frames. Unlike telemetry queues it
 * never drops an old frame to admit a new one: full means failure.
 */
internal class LosslessIpcFrameQueue(
    private val capacity: Int,
) {
    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()

    init {
        require(capacity > 0)
    }

    fun offer(
        frame: ByteArray,
    ): Boolean =
        synchronized(lock) {
            if (queue.size >= capacity) {
                return@synchronized false
            }
            queue.addLast(
                frame.copyOf(),
            )
            true
        }

    fun drain(
        maximum: Int,
    ): LosslessIpcFrameDrain {
        require(maximum > 0)
        return synchronized(lock) {
            val count =
                minOf(
                    maximum,
                    queue.size,
                )
            val frames =
                ArrayList<ByteArray>(
                    count,
                )
            repeat(count) {
                frames +=
                    queue.removeFirst()
            }
            LosslessIpcFrameDrain(
                frames,
                queue.isNotEmpty(),
            )
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
        }
    }
}
