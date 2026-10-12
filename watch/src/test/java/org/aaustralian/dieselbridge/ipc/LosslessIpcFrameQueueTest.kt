// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LosslessIpcFrameQueueTest {
    @Test
    fun fullQueueRejectsNewFrameInsteadOfDroppingOldFrame() {
        val queue =
            LosslessIpcFrameQueue(
                capacity = 2,
            )

        val first =
            byteArrayOf(
                1,
            )

        assertTrue(
            queue.offer(
                first,
            ),
        )
        assertTrue(
            queue.offer(
                byteArrayOf(
                    2,
                ),
            ),
        )
        assertFalse(
            queue.offer(
                byteArrayOf(
                    3,
                ),
            ),
        )

        first[0] = 9

        val drain =
            queue.drain(
                2,
            )

        assertArrayEquals(
            byteArrayOf(
                1,
            ),
            drain.frames[0],
        )
        assertArrayEquals(
            byteArrayOf(
                2,
            ),
            drain.frames[1],
        )
        assertFalse(
            drain.hasRemaining,
        )
    }
}
