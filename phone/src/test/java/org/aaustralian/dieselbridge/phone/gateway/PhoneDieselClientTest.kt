// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDieselClientTest {

    @Test
    fun buildInfoUsesInjectedIdAndOneWayTransport() {
        val submitted =
            mutableListOf<String>()

        val client =
            PhoneDieselClient(
                transport =
                    PhoneDieselTransport {
                        line,
                        ->
                        submitted +=
                            line
                    },
                requestIdFactory = {
                    "phone-fixed"
                },
                clockMs = {
                    123456789L
                },
            )

        val dispatch =
            client
                .sendBuildInfo()

        assertEquals(
            "phone-fixed",
            dispatch
                .request
                .requestId,
        )
        assertEquals(
            "debug.build.info",
            dispatch
                .request
                .command,
        )
        assertEquals(
            123456789L,
            dispatch
                .submittedAtMs,
        )
        assertEquals(
            listOf(
                dispatch
                    .gadgetbridgeLine,
            ),
            submitted,
        )
        assertTrue(
            dispatch
                .gadgetbridgeLine
                .contains(
                    "\"id\":\"phone-fixed\"",
                ),
        )
    }
}
