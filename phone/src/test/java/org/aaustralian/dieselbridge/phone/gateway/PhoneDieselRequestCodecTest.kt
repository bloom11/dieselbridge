// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDieselRequestCodecTest {

    @Test
    fun request_matchesExistingDieselAdbGadgetbridgeContract() {
        val request =
            PhoneDieselRequest(
                requestId =
                    "phone-test-1",
                command =
                    "debug.build.info",
            )

        val line =
            PhoneDieselRequestCodec
                .encodeGadgetbridgeLine(
                    request,
                )

        assertTrue(
            line.startsWith(
                "GB(",
            ),
        )
        assertTrue(
            line.endsWith(
                ")",
            ),
        )
        assertFalse(
            line.startsWith(
                "\u0010",
            ),
        )
        assertFalse(
            line.endsWith(
                "\n",
            ),
        )

        val root =
            JSONObject(
                line.substring(
                    3,
                    line.length - 1,
                ),
            )

        assertEquals(
            "diesel",
            root.getString(
                "t",
            ),
        )
        assertEquals(
            1,
            root.getInt(
                "v",
            ),
        )
        assertEquals(
            "request",
            root.getString(
                "kind",
            ),
        )
        assertEquals(
            "phone-test-1",
            root.getString(
                "id",
            ),
        )
        assertEquals(
            "debug.build.info",
            root.getString(
                "cmd",
            ),
        )
        assertFalse(
            root.has(
                "name",
            ),
        )
        assertEquals(
            0,
            root.getJSONObject(
                "args",
            ).length(),
        )
    }

    @Test
    fun requestRejectsInvalidCommandIdentifier() {
        val result =
            runCatching {
                PhoneDieselRequest(
                    requestId =
                        "valid-id",
                    command =
                        "INVALID COMMAND",
                )
            }

        assertTrue(
            result.isFailure,
        )
    }
}
