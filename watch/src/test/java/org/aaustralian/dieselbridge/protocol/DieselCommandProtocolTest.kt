// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselCommandProtocolTest {

    @Test
    fun parsesSimpleDieselRequest() {
        val message =
            GbProtocol.parseLine(
                """GB({"t":"diesel","cmd":"diagnostics"})""",
            )

        assertTrue(
            message is
                GbMessage.DieselRequestMessage,
        )

        val request =
            (
                message as
                    GbMessage.DieselRequestMessage
            ).request

        assertEquals(
            "diagnostics",
            request.command,
        )

        assertNull(
            request.name,
        )

        assertNull(
            request.requestId,
        )

        assertTrue(
            request.args.isEmpty(),
        )
    }

    @Test
    fun parsesStructuredDieselRequestTarget() {
        val message =
            GbProtocol.parseLine(
                """
                GB({
                  "t":"diesel",
                  "cmd":"test",
                  "name":"vibration"
                })
                """.trimIndent(),
            ) as GbMessage.DieselRequestMessage

        assertEquals(
            "test",
            message.request.command,
        )

        assertEquals(
            "vibration",
            message.request.name,
        )
    }

    @Test
    fun parsesCorrelatedDieselRequest() {
        val message =
            GbProtocol.parseLine(
                """
                GB({
                  "t":"diesel",
                  "id":"req-42",
                  "cmd":"test",
                  "name":"vibration"
                })
                """.trimIndent(),
            ) as GbMessage.DieselRequestMessage

        assertEquals(
            "req-42",
            message.request.requestId,
        )

        assertEquals(
            "test",
            message.request.command,
        )

        assertEquals(
            "vibration",
            message.request.name,
        )
    }

    @Test
    fun emptyRequestIdIsRejected() {
        val message =
            GbProtocol.parseLine(
                """
                GB({
                  "t":"diesel",
                  "id":"",
                  "cmd":"commands"
                })
                """.trimIndent(),
            )

        assertTrue(
            message is
                GbMessage.InvalidDieselRequestMessage,
        )

        val failure =
            (
                message as
                    GbMessage.InvalidDieselRequestMessage
            ).failure

        assertEquals(
            DieselRequestFailureReason.INVALID_REQUEST_ID,
            failure.reason,
        )

        assertNull(
            failure.requestId,
        )

        assertEquals(
            "commands",
            failure.command,
        )
    }

    @Test
    fun dieselWithoutCommandIsExplicitlyInvalid() {
        val message =
            GbProtocol.parseLine(
                """
                GB({
                  "t":"diesel",
                  "id":"missing-cmd-1",
                  "name":"vibration"
                })
                """.trimIndent(),
            )

        assertTrue(
            message is
                GbMessage.InvalidDieselRequestMessage,
        )

        val failure =
            (
                message as
                    GbMessage.InvalidDieselRequestMessage
            ).failure

        assertEquals(
            DieselRequestFailureReason.MISSING_COMMAND,
            failure.reason,
        )

        assertEquals(
            "missing-cmd-1",
            failure.requestId,
        )

        assertNull(
            failure.command,
        )

        assertEquals(
            "vibration",
            failure.name,
        )
    }
}
