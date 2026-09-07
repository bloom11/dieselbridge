// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselProtocolEngineTest {

    @Test
    fun engineAddsCorrelationWithoutCommandSpecificTransportCode(): Unit = runBlocking {
        val registry =
            DieselCommandRegistry()

        registry.register(
            DieselCommandSpec(
                name = "sensor",
                summary = "Read sensor data",
            ),
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
    fun newCommandNeedsOnlyRegistration(): Unit = runBlocking {
        val registry =
            DieselCommandRegistry()

        registry.register(
            DieselCommandSpec(
                name = "display.write",
                summary = "Write display state",
            ),
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
    fun unknownCommandProducesGenericResponse(): Unit = runBlocking {
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
    fun handlerFailureDoesNotCrashProtocolEngine(): Unit = runBlocking {
        val registry =
            DieselCommandRegistry()

        registry.register(
            DieselCommandSpec(
                name = "broken",
                summary = "Synthetic failing command",
            ),
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
    fun transportFailureIsSeparateFromCommandStatus(): Unit = runBlocking {
        val registry =
            DieselCommandRegistry()

        registry.register(
            DieselCommandSpec(
                name = "commands",
                summary = "List registered commands",
            ),
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

    @Test
    fun invalidRequestUsesGenericResponseTransport(): Unit = runBlocking {
        var response:
            DieselResponse? =
            null

        val engine =
            DieselProtocolEngine(
                commands =
                    DieselCommandRegistry(),
                responses =
                    DieselResponseTransport {
                            value,
                        ->
                        response =
                            value

                        true
                    },
            )

        val dispatch =
            engine.handleInvalid(
                DieselInvalidRequest(
                    requestId = "bad-1",
                    command = "sensor.read",
                    name = "accelerometer",
                    reason =
                        DieselRequestFailureReason.INVALID_ARGS,
                    detail =
                        "local parser detail",
                ),
            )

        assertTrue(
            dispatch.sent,
        )

        assertNull(
            dispatch.transportError,
        )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            response?.status,
        )

        assertEquals(
            "bad-1",
            response?.requestId,
        )

        assertEquals(
            "sensor.read",
            response?.command,
        )

        assertEquals(
            "accelerometer",
            response?.name,
        )

        assertEquals(
            DieselValue.Text(
                "invalid_args",
            ),
            response
                ?.data
                ?.get("reason"),
        )

        assertFalse(
            response
                ?.data
                ?.containsKey("detail")
                ?: true,
        )
    }

    @Test
    fun invalidCommandUsesReservedProtocolCommand(): Unit = runBlocking {
        var response:
            DieselResponse? =
            null

        val engine =
            DieselProtocolEngine(
                commands =
                    DieselCommandRegistry(),
                responses =
                    DieselResponseTransport {
                            value,
                        ->
                        response =
                            value

                        true
                    },
            )

        engine.handleInvalid(
            DieselInvalidRequest(
                requestId = "bad-2",
                command = null,
                name = null,
                reason =
                    DieselRequestFailureReason.INVALID_COMMAND,
                detail =
                    "must remain local",
            ),
        )

        assertEquals(
            DieselProtocolEngine.PROTOCOL_ERROR_COMMAND,
            response?.command,
        )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            response?.status,
        )
    }

    @Test
    fun invalidRequestTransportFailureIsReported(): Unit = runBlocking {
        val engine =
            DieselProtocolEngine(
                commands =
                    DieselCommandRegistry(),
                responses =
                    DieselResponseTransport {
                        throw IllegalStateException(
                            "transport offline",
                        )
                    },
            )

        val dispatch =
            engine.handleInvalid(
                DieselInvalidRequest(
                    requestId = "bad-3",
                    command = "commands",
                    name = null,
                    reason =
                        DieselRequestFailureReason.INVALID_KIND,
                    detail =
                        "local only",
                ),
            )

        assertFalse(
            dispatch.sent,
        )

        assertNotNull(
            dispatch.transportError,
        )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            dispatch.response.status,
        )
    }


    @Test
    fun suspendHandlerDoesNotSendBeforeCompletion(): Unit =
        runBlocking {
            val entered =
                CompletableDeferred<Unit>()

            val release =
                CompletableDeferred<Unit>()

            val registry =
                DieselCommandRegistry()

            registry.register(
                DieselCommandSpec(
                    name = "slow",
                    summary = "Synthetic suspending command",
                ),
            ) {
                entered.complete(
                    Unit,
                )

                release.await()

                DieselCommandResult.ok(
                    data =
                        mapOf(
                            "finished" to
                                DieselValue.Flag(
                                    true,
                                ),
                        ),
                )
            }

            var response:
                DieselResponse? =
                null

            val engine =
                DieselProtocolEngine(
                    commands = registry,
                    responses =
                        DieselResponseTransport {
                                value,
                            ->
                            response =
                                value

                            true
                        },
                )

            val pending =
                async {
                    engine.handle(
                        DieselRequest(
                            requestId =
                                "slow-1",
                            command =
                                "slow",
                        ),
                    )
                }

            entered.await()
            yield()

            assertNull(
                response,
            )

            release.complete(
                Unit,
            )

            val dispatch =
                pending.await()

            assertTrue(
                dispatch.sent,
            )

            assertEquals(
                DieselResponseStatus.OK,
                response?.status,
            )

            assertEquals(
                DieselValue.Flag(
                    true,
                ),
                response
                    ?.data
                    ?.get(
                        "finished",
                    ),
            )
        }

    @Test
    fun handlerCancellationDoesNotBecomeFailedResponse(): Unit =
        runBlocking {
            val entered =
                CompletableDeferred<Unit>()

            val registry =
                DieselCommandRegistry()

            registry.register(
                DieselCommandSpec(
                    name = "cancel",
                    summary = "Synthetic cancellable command",
                ),
            ) {
                entered.complete(
                    Unit,
                )

                awaitCancellation()
            }

            var responseCount =
                0

            val engine =
                DieselProtocolEngine(
                    commands = registry,
                    responses =
                        DieselResponseTransport {
                            responseCount += 1
                            true
                        },
                )

            val pending =
                launch {
                    engine.handle(
                        DieselRequest(
                            requestId =
                                "cancel-1",
                            command =
                                "cancel",
                        ),
                    )
                }

            entered.await()

            pending.cancelAndJoin()

            assertEquals(
                0,
                responseCount,
            )
        }


}
