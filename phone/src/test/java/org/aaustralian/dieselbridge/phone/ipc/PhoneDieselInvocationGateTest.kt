// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDieselInvocationGateTest {
    @Test
    fun fifthConcurrentInvocationIsRejected() {
        val gate =
            PhoneDieselInvocationGate(
                maxConcurrent = 4,
            )

        repeat(4) {
            assertTrue(
                gate.tryAcquire(),
            )
        }

        assertEquals(
            4,
            gate.activeCount,
        )
        assertFalse(
            gate.tryAcquire(),
        )

        gate.release()

        assertTrue(
            gate.tryAcquire(),
        )
        assertEquals(
            4,
            gate.activeCount,
        )
    }

    @Test(expected = IllegalStateException::class)
    fun releaseWithoutAcquisitionFailsFast() {
        PhoneDieselInvocationGate()
            .release()
    }
}
