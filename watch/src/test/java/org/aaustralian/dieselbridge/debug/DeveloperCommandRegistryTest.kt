// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.protocol.GbMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperCommandRegistryTest {

    @Test
    fun registrationDrivesBothDiscoveryAndDispatch() {
        val registry =
            DeveloperCommandRegistry()

        val spec =
            DeveloperCommandSpec(
                name = "test",
                summary = "Run a safe platform test",
                effect = DeveloperCommandEffect.SAFE_ACTION,
            )

        var received: GbMessage.DieselCommand? =
            null

        registry.register(spec) { message ->
            received = message
        }

        assertEquals(
            listOf(spec),
            registry.specs(),
        )

        val message =
            GbMessage.DieselCommand(
                command = "test",
                name = "vibration",
            )

        assertTrue(
            registry.dispatch(message),
        )

        assertEquals(
            message,
            received,
        )
    }

    @Test
    fun unknownCommandDoesNotDispatch() {
        val registry =
            DeveloperCommandRegistry()

        assertFalse(
            registry.dispatch(
                GbMessage.DieselCommand(
                    command = "shell",
                ),
            ),
        )
    }

    @Test
    fun duplicateRegistrationIsRejected() {
        val registry =
            DeveloperCommandRegistry()

        val spec =
            DeveloperCommandSpec(
                name = "commands",
                summary = "List commands",
                effect = DeveloperCommandEffect.READ_ONLY,
            )

        registry.register(spec) { }

        var rejected = false

        try {
            registry.register(spec) { }
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }
}
