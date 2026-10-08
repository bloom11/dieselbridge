// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneBuildInfoTest {

    @Test
    fun diagnosticText_preservesBuildProvenanceAndM6Boundaries() {
        val text =
            PhoneBuildInfo(
                applicationId = "io.github.bloom11.dieselbridge.phone",
                versionName = "0.1.0-dev.1",
                versionCode = 1L,
                gitSha = "0123456789abcdef",
                ciRunId = "12345",
                buildTimestampUtc = "2026-10-08 12:00:00 UTC",
            ).diagnosticText()

        assertTrue(
            text.contains(
                "Package: io.github.bloom11.dieselbridge.phone",
            ),
        )
        assertTrue(text.contains("Git SHA: 0123456789abcdef"))
        assertTrue(text.contains("CI run: 12345"))
        assertTrue(text.contains("BLE ownership: stock Gadgetbridge only"))
        assertTrue(text.contains("Gadgetbridge transport: not implemented"))
        assertTrue(text.contains("Android next-alarm provider: not implemented"))
        assertFalse(text.contains("connected to watch"))
    }
}
