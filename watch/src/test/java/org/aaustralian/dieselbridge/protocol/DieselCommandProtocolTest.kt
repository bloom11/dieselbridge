// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class DieselCommandProtocolTest {

    @Test
    fun parsesSimpleDieselCommand() {
        assertEquals(
            GbMessage.DieselCommand(
                command = "diagnostics",
            ),
            GbProtocol.parseLine(
                """GB({"t":"diesel","cmd":"diagnostics"})""",
            ),
        )
    }

    @Test
    fun parsesStructuredDieselCommandTarget() {
        assertEquals(
            GbMessage.DieselCommand(
                command = "test",
                name = "vibration",
            ),
            GbProtocol.parseLine(
                """GB({"t":"diesel","cmd":"test","name":"vibration"})""",
            ),
        )
    }

    @Test
    fun dieselWithoutCmdRemainsNonExecutable() {
        assertEquals(
            GbMessage.Other("diesel"),
            GbProtocol.parseLine(
                """GB({"t":"diesel","name":"vibration"})""",
            ),
        )
    }
}
