// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import java.util.ArrayDeque

internal data class SensorManagerIngressItem<T>(
    val value: T,
    val sourceDroppedTotal: Long,
)

/**
 * Callback-safe bounded FIFO used between SensorManager's HandlerThread and
 * the coroutine observation runtime.
 *
 * When full, the oldest pending sample is discarded so realtime consumers
 * retain the freshest hardware data. Every eviction is counted exactly.
 */
internal class SensorManagerObservationIngress<T>(
    private val capacity: Int,
) {

    init {
        require(
            capacity > 0,
        ) {
            "Ingress capacity must be positive"
        }
    }

    private val lock =
        Any()

    private val queue =
        ArrayDeque<T>(
            capacity,
        )

    private var droppedTotal =
        0L

    fun offer(
        value: T,
    ): Long =
        synchronized(lock) {
            if (
                queue.size >=
                capacity
            ) {
                queue.removeFirst()

                if (
                    droppedTotal <
                    Long.MAX_VALUE
                ) {
                    droppedTotal++
                }
            }

            queue.addLast(
                value,
            )

            droppedTotal
        }

    fun poll():
        SensorManagerIngressItem<T>? =
        synchronized(lock) {
            if (
                queue.isEmpty()
            ) {
                return@synchronized null
            }

            SensorManagerIngressItem(
                value =
                    queue.removeFirst(),
                sourceDroppedTotal =
                    droppedTotal,
            )
        }

    val sourceDroppedTotal:
        Long
        get() =
            synchronized(lock) {
                droppedTotal
            }

    val size:
        Int
        get() =
            synchronized(lock) {
                queue.size
            }
}
