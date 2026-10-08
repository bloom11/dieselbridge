// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneBuildInfoTest {
    @Test
    fun diagnosticTextPreservesBuildProvenanceAndM6Boundaries() {
        val text =
            PhoneBuildInfo(
                applicationId =
                    "io.github.bloom11.dieselbridge.phone",
                versionName =
                    "0.1.0-dev.4",
                versionCode =
                    4L,
                gitSha =
                    "0123456789abcdef",
                ciRunId =
                    "12345",
                buildTimestampUtc =
                    "2026-10-08 12:00:00 UTC",
            ).diagnosticText()

        assertTrue(
            text.contains(
                "Milestone: M6.1e bundle",
            ),
        )
        assertTrue(
            text.contains(
                "Git SHA: 0123456789abcdef",
            ),
        )
        assertTrue(
            text.contains(
                "Diesel response correlation: implemented",
            ),
        )
        assertTrue(
            text.contains(
                "Android next-alarm provider: implemented",
            ),
        )
        assertTrue(
            text.contains(
                "Alarm change/reconnect resync: implemented",
            ),
        )
        assertTrue(
            text.contains(
                "BLE ownership: stock Gadgetbridge only",
            ),
        )
        assertFalse(
            text.contains(
                "custom BLE",
            ),
        )
    }
}
