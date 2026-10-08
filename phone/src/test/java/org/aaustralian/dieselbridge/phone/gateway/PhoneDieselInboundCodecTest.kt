// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDieselInboundCodecTest {
    @Test
    fun parsesResponseAndEvent() {
        val response =
            PhoneDieselInboundCodec.parse(
                """{"v":1,"kind":"response","id":"x","cmd":"debug.build.info","status":"ok","data":{"gitSha":"abc"}}""",
            )
        assertTrue(response is PhoneDieselInbound.Response)

        val event =
            PhoneDieselInboundCodec.parse(
                """{"v":1,"kind":"event","topic":"companion.sync.request","data":{"reason":"gadgetbridge_subscribed"}}""",
            )
        assertTrue(event is PhoneDieselInbound.Event)
        event as PhoneDieselInbound.Event
        assertEquals(
            "companion.sync.request",
            event.value.topic,
        )
    }

    @Test
    fun rejectsOversizedPayload() {
        val huge =
            "x".repeat(
                PhoneDieselProtocolRules.MAX_JSON_BYTES + 1,
            )
        assertTrue(
            runCatching {
                PhoneDieselInboundCodec.parse(huge)
            }.isFailure,
        )
    }
}
