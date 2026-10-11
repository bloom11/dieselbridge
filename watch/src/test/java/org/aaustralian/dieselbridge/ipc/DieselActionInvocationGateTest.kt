// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselActionInvocationGateTest {
    @Test
    fun gateRejectsInsteadOfQueueingPastLimit() {
        val gate =
            DieselActionInvocationGate(
                maxConcurrent =
                    2,
            )

        assertTrue(
            gate.tryAcquire(),
        )
        assertTrue(
            gate.tryAcquire(),
        )
        assertFalse(
            gate.tryAcquire(),
        )
        assertEquals(
            2,
            gate.activeCount,
        )

        gate.release()

        assertTrue(
            gate.tryAcquire(),
        )
        assertEquals(
            2,
            gate.activeCount,
        )

        gate.release()
        gate.release()

        assertEquals(
            0,
            gate.activeCount,
        )
    }
}
