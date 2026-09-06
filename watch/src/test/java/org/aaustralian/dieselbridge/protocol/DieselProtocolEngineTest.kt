// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselProtocolEngineTest {

    @Test
    fun engineAddsCorrelationWithoutCommandSpecificTransportCode() {
        val registry =
            DieselCommandRegistry()

        registry.register(
            "sensor",
        ) { context ->
            DieselCommandResult.ok(
                data =
                    mapOf(
                        "target" to
                            DieselValue.Text(
                                requireNotNull(
                                    context.name,
                                ),
                            ),
                    ),
            )
        }

        var captured: DieselResponse? =
            null

        val engine =
            DieselProtocolEngine(
                commands = registry,
                responses =
                    DieselResponseTransport {
                            response,
                        ->
                        captured = response
                        true
                    },
            )

        val dispatch =
            engine.handle(
                DieselRequest(
                    requestId = "req-42",
                    command = "sensor",
                    name = "accelerometer",
                ),
            )

        assertTrue(
            dispatch.sent,
        )

        assertNull(
            dispatch.handlerError,
        )

        assertNull(
            dispatch.transportError,
        )

        assertEquals(
            "req-42",
            captured?.requestId,
        )

        assertEquals(
            "sensor",
            captured?.command,
        )

        assertEquals(
            "accelerometer",
            captured?.name,
        )

        assertEquals(
            DieselResponseStatus.OK,
            captured?.status,
        )
    }

    @Test
    fun newCommandNeedsOnlyRegistration() {
        val registry =
            DieselCommandRegistry()

        registry.register(
            "display.write",
        ) {
            DieselCommandResult.ok(
                data =
                    mapOf(
                        "provider" to
                            DieselValue.Text(
                                "plugin.secondary",
                            ),
                    ),
            )
        }

        var response: DieselResponse? =
            null

        val engine =
            DieselProtocolEngine(
                commands = registry,
                responses =
                    DieselResponseTransport {
                            value,
                        ->
                        response = value
                        true
                    },
            )

        engine.handle(
            DieselRequest(
                requestId = "lcd-1",
                command = "display.write",
                name = "secondary",
            ),
        )

        assertEquals(
            DieselValue.Text(
                "plugin.secondary",
            ),
            response
                ?.data
                ?.get("provider"),
        )
    }

    @Test
    fun unknownCommandProducesGenericResponse() {
        var response: DieselResponse? =
            null

        val engine =
            DieselProtocolEngine(
                commands =
                    DieselCommandRegistry(),
                responses =
                    DieselResponseTransport {
                            value,
                        ->
                        response = value
                        true
                    },
            )

        engine.handle(
            DieselRequest(
                requestId = "unknown-1",
                command = "future.command",
            ),
        )

        assertEquals(
            DieselResponseStatus.UNKNOWN_COMMAND,
            response?.status,
        )
    }

    @Test
    fun handlerFailureDoesNotCrashProtocolEngine() {
        val registry =
            DieselCommandRegistry()

        registry.register(
            "broken",
        ) {
            error("boom")
        }

        var response: DieselResponse? =
            null

        val engine =
            DieselProtocolEngine(
                commands = registry,
                responses =
                    DieselResponseTransport {
                            value,
                        ->
                        response = value
                        true
                    },
            )

        val dispatch =
            engine.handle(
                DieselRequest(
                    requestId = "broken-1",
                    command = "broken",
                ),
            )

        assertNotNull(
            dispatch.handlerError,
        )

        assertEquals(
            DieselResponseStatus.FAILED,
            response?.status,
        )
    }

    @Test
    fun transportFailureIsSeparateFromCommandStatus() {
        val registry =
            DieselCommandRegistry()

        registry.register(
            "commands",
        ) {
            DieselCommandResult.ok()
        }

        val engine =
            DieselProtocolEngine(
                commands = registry,
                responses =
                    DieselResponseTransport {
                        throw IllegalStateException(
                            "transport offline",
                        )
                    },
            )

        val dispatch =
            engine.handle(
                DieselRequest(
                    requestId = "transport-1",
                    command = "commands",
                ),
            )

        assertFalse(
            dispatch.sent,
        )

        assertNotNull(
            dispatch.transportError,
        )

        assertEquals(
            DieselResponseStatus.OK,
            dispatch.response.status,
        )
    }
}
