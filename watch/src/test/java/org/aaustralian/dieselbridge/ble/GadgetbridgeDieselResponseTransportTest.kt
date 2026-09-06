// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ble

import org.json.JSONObject
import org.aaustralian.dieselbridge.protocol.DieselResponse
import org.aaustralian.dieselbridge.protocol.DieselResponseCodec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GadgetbridgeDieselResponseTransportTest {

    @Test
    fun sendsEncodedResponseThroughProvidedLineSender() {
        var captured: String? = null

        val transport =
            GadgetbridgeDieselResponseTransport {
                    line,
                ->
                captured = line
                true
            }

        val sent =
            transport.send(
                DieselResponse(
                    requestId = "req-7",
                    command = "commands",
                    status =
                        DieselResponseStatus.OK,
                ),
            )

        assertTrue(sent)
        assertNotNull(captured)

        val intent =
            JSONObject(
                requireNotNull(
                    captured,
                ),
            )

        assertEquals(
            DieselResponseCodec.ANDROID_ACTION,
            intent.getString("action"),
        )
    }

    @Test
    fun propagatesSenderFailure() {
        val transport =
            GadgetbridgeDieselResponseTransport {
                false
            }

        val sent =
            transport.send(
                DieselResponse(
                    requestId = "req-8",
                    command = "commands",
                    status =
                        DieselResponseStatus.OK,
                ),
            )

        assertFalse(sent)
    }
}
