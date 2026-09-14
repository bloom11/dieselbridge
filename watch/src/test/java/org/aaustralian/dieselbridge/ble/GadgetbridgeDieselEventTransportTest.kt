// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ble

import org.json.JSONObject
import org.aaustralian.dieselbridge.protocol.DieselEvent
import org.aaustralian.dieselbridge.protocol.DieselEventCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GadgetbridgeDieselEventTransportTest {

    @Test
    fun sendsEncodedEventThroughProvidedLineSender() {
        var captured: String? = null

        val transport =
            GadgetbridgeDieselEventTransport {
                    line,
                ->
                captured = line
                true
            }

        val sent =
            transport.send(
                DieselEvent(
                    topic = "sensor.sample",
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
            DieselEventCodec.ANDROID_ACTION,
            intent.getString("action"),
        )

        val event =
            JSONObject(
                intent
                    .getJSONObject("extra")
                    .getString(
                        DieselEventCodec.EXTRA_JSON,
                    ),
            )

        assertEquals(
            "event",
            event.getString("kind"),
        )
    }

    @Test
    fun propagatesSenderFailure() {
        val transport =
            GadgetbridgeDieselEventTransport {
                false
            }

        val sent =
            transport.send(
                DieselEvent(
                    topic = "sensor.sample",
                ),
            )

        assertFalse(sent)
    }
}
