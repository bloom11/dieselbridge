// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.ipc

import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-session non-queuing admission. The underlying M6 response router
 * still owns the global pending-request limit.
 */
internal class PhoneDieselInvocationGate(
    private val maxConcurrent: Int =
        DEFAULT_MAX_CONCURRENT,
) {
    private val active =
        AtomicInteger(
            0,
        )

    init {
        require(maxConcurrent > 0)
    }

    val activeCount: Int
        get() =
            active.get()

    fun tryAcquire(): Boolean {
        while (true) {
            val current =
                active.get()

            if (current >= maxConcurrent) {
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

        check(remaining >= 0) {
            "Phone invocation gate released without acquisition"
        }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT =
            4
    }
}
