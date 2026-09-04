// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformDiagnosticsTest {

    @Test
    fun recentRecordsAreBounded() {
        var now = 1000L

        val diagnostics =
            PlatformDiagnostics(
                recentCapacity = 3,
                clock = { now++ },
            )

        diagnostics.record("test", "one")
        diagnostics.record("test", "two")
        diagnostics.record("test", "three")
        diagnostics.record("test", "four")

        val records =
            diagnostics.state.value.recentRecords

        assertEquals(3, records.size)

        assertEquals(
            listOf("two", "three", "four"),
            records.map { it.message },
        )

        assertEquals(
            listOf(1001L, 1002L, 1003L),
            records.map { it.timestampMs },
        )
    }

    @Test
    fun zeroCapacityIsRejected() {
        var rejected = false

        try {
            PlatformDiagnostics(recentCapacity = 0)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }
}
