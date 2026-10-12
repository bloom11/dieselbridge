// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceProviderIdsTest {
    @Test
    fun callerOwnedIdsFitDieselIdentifierLimit() {
        DieselReferenceProviderHarness
            .RESOURCE_IDS
            .forEach {
                id ->
                assertTrue(
                    "$id exceeds Diesel identifier limit",
                    id.length <= 64,
                )
            }

        assertEquals(
            64,
            DieselReferenceProviderHarness
                .OBSERVATION_ID
                .length,
        )
    }

    @Test
    fun resourcesStayInsideReferenceCallerNamespace() {
        val packageName =
            "io.github.bloom11.dieselbridge.reference"

        assertTrue(
            DieselReferenceProviderHarness
                .STATE_ID
                .startsWith(
                    "plugin.$packageName.",
                ),
        )

        assertTrue(
            DieselReferenceProviderHarness
                .ACTION_ID
                .startsWith(
                    "plugin.$packageName.",
                ),
        )

        assertTrue(
            DieselReferenceProviderHarness
                .EVENT_ID
                .startsWith(
                    "plugin.$packageName.",
                ),
        )

        assertTrue(
            DieselReferenceProviderHarness
                .OBSERVATION_ID
                .startsWith(
                    "sensor.observe.plugin.$packageName.",
                ),
        )
    }
}
