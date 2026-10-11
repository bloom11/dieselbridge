// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ipc

import java.util.concurrent.atomic.AtomicInteger

/**
 * Non-queuing per-session admission gate for remote actions.
 *
 * The core ActionDispatcher intentionally has no hidden queue. The public IPC
 * boundary likewise rejects excess remote work rather than building an
 * unbounded coroutine backlog.
 */
internal class DieselActionInvocationGate(
    private val maxConcurrent: Int =
        DEFAULT_MAX_CONCURRENT,
) {
    private val active =
        AtomicInteger(
            0,
        )

    init {
        require(
            maxConcurrent > 0,
        )
    }

    val activeCount: Int
        get() =
            active.get()

    fun tryAcquire(): Boolean {
        while (true) {
            val current =
                active.get()

            if (
                current >=
                maxConcurrent
            ) {
                return false
            }

            if (
                active.compareAndSet(
                    current,
                    current + 1,
                )
            ) {
                return true
            }
        }
    }

    fun release() {
        val remaining =
            active.decrementAndGet()

        check(
            remaining >= 0,
        ) {
            "Action invocation gate released without acquisition"
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT =
            4
    }
}
