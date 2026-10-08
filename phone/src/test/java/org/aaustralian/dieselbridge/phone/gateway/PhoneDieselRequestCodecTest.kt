// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDieselRequestCodecTest {
    @Test
    fun structuredArgsMatchGadgetbridgeContract() {
        val line =
            PhoneDieselRequestCodec.encodeGadgetbridgeLine(
                PhoneDieselRequest(
                    requestId = "phone-test",
                    command =
                        "companion.alarm.next.sync",
                    args =
                        mapOf(
                            "kind" to
                                PhoneDieselValue.Text(
                                    "scheduled",
                                ),
                            "triggerAtMs" to
                                PhoneDieselValue.Integer(
                                    1234L,
                                ),
                        ),
                ),
            )

        assertTrue(line.startsWith("GB("))
        assertTrue(line.endsWith(")"))

        val root =
            JSONObject(
                line.substring(3, line.length - 1),
            )
        assertEquals("diesel", root.getString("t"))
        assertEquals(
            "scheduled",
            root.getJSONObject("args")
                .getString("kind"),
        )
        assertEquals(
            1234L,
            root.getJSONObject("args")
                .getLong("triggerAtMs"),
        )
    }
}
