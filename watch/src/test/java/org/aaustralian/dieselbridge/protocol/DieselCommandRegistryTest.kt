// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselCommandRegistryTest {

    @Test
    fun arbitraryRegisteredModuleUsesGenericArguments() {
        val registry =
            DieselCommandRegistry()

        registry.register(
            "sensor",
        ) { context ->
            DieselCommandResult.ok(
                data =
                    mapOf(
                        "requestedRateHz" to
                            requireNotNull(
                                context.args[
                                    "rateHz"
                                ],
                            ),
                    ),
            )
        }

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "sensor-1",
                    command = "sensor",
                    name = "accelerometer",
                    args =
                        mapOf(
                            "rateHz" to
                                DieselValue.Integer(
                                    25L,
                                ),
                        ),
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        assertEquals(
            DieselValue.Integer(25L),
            result.data[
                "requestedRateHz"
            ],
        )
    }

    @Test
    fun unknownCommandIsHandledCentrally() {
        val registry =
            DieselCommandRegistry()

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "unknown-1",
                    command = "future.module",
                ),
            )

        assertEquals(
            DieselResponseStatus.UNKNOWN_COMMAND,
            result.status,
        )
    }

    @Test
    fun moduleCanInstallCommandsWithoutEngineChanges() {
        val registry =
            DieselCommandRegistry()

        val module =
            DieselCommandModule {
                    targetRegistry,
                ->
                targetRegistry.register(
                    "alarm",
                ) {
                    DieselCommandResult.ok(
                        data =
                            mapOf(
                                "count" to
                                    DieselValue.Integer(
                                        3L,
                                    ),
                            ),
                    )
                }
            }

        registry.install(
            module,
        )

        assertEquals(
            listOf("alarm"),
            registry.commands(),
        )
    }

    @Test
    fun duplicateRegistrationIsRejected() {
        val registry =
            DieselCommandRegistry()

        registry.register(
            "sensor",
        ) {
            DieselCommandResult.ok()
        }

        var rejected = false

        try {
            registry.register(
                "sensor",
            ) {
                DieselCommandResult.ok()
            }
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }
}
