// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperCommandModuleTest {

    private class FakeRuntime :
        DeveloperCommandRuntime {

        var diagnosticsCalls = 0

        var shownCommands:
            List<DieselCommandSpec> =
            emptyList()

        var lastTestTarget: String? =
            null

        override fun showDiagnostics():
            DieselCommandResult {
            diagnosticsCalls += 1

            return DieselCommandResult.ok(
                data =
                    mapOf(
                        "source" to
                            DieselValue.Text(
                                "fake",
                            ),
                    ),
            )
        }

        override fun showCommands(
            commands: List<DieselCommandSpec>,
        ) {
            shownCommands =
                commands.toList()
        }

        override fun runSafeTest(
            target: String?,
        ): DieselCommandResult {
            lastTestTarget =
                target

            return DieselCommandResult.ok(
                data =
                    mapOf(
                        "target" to
                            DieselValue.Text(
                                target ?: "missing",
                            ),
                    ),
            )
        }
    }

    @Test
    fun moduleRegistersExistingDeveloperCommands() {
        val runtime =
            FakeRuntime()

        val published =
            mutableListOf<
                List<DieselCommandSpec>
            >()

        val registry =
            DieselCommandRegistry(
                onCatalogChanged =
                    published::add,
            )

        registry.install(
            DeveloperCommandModule(
                runtime = runtime,
            ),
        )

        assertEquals(
            listOf(
                "diagnostics",
                "commands",
                "test",
            ),
            registry.commands(),
        )

        assertEquals(
            3,
            published.size,
        )

        assertEquals(
            registry.commands(),
            published.last().map {
                it.name
            },
        )
    }

    @Test
    fun diagnosticsUsesGenericResultPath() {
        val runtime =
            FakeRuntime()

        val registry =
            DieselCommandRegistry()

        registry.install(
            DeveloperCommandModule(
                runtime,
            ),
        )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "diag-1",
                    command = "diagnostics",
                ),
            )

        assertEquals(
            1,
            runtime.diagnosticsCalls,
        )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        assertEquals(
            DieselValue.Text("fake"),
            result.data["source"],
        )
    }

    @Test
    fun testTargetIsPassedWithoutTransportKnowledge() {
        val runtime =
            FakeRuntime()

        val registry =
            DieselCommandRegistry()

        registry.install(
            DeveloperCommandModule(
                runtime,
            ),
        )

        registry.dispatch(
            DieselRequest(
                requestId = "test-1",
                command = "test",
                name = "vibration",
            ),
        )

        assertEquals(
            "vibration",
            runtime.lastTestTarget,
        )
    }

    @Test
    fun commandsDiscoveryIncludesLaterModulesAutomatically() {
        val runtime =
            FakeRuntime()

        val registry =
            DieselCommandRegistry()

        registry.install(
            DeveloperCommandModule(
                runtime,
            ),
        )

        registry.register(
            DieselCommandSpec(
                name = "sensor",
                summary =
                    "Read a platform sensor",
                metadata =
                    mapOf(
                        "family" to
                            DieselValue.Text(
                                "sensor",
                            ),
                    ),
            ),
        ) {
            DieselCommandResult.ok()
        }

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "commands-1",
                    command = "commands",
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        val commands =
            result.data["commands"]
                as DieselValue.ListValue

        val names =
            commands.value
                .map {
                    val objectValue =
                        it as
                            DieselValue.ObjectValue

                    (
                        objectValue.value["name"]
                            as DieselValue.Text
                    ).value
                }

        assertEquals(
            listOf(
                "diagnostics",
                "commands",
                "test",
                "sensor",
            ),
            names,
        )

        assertEquals(
            listOf(
                "diagnostics",
                "commands",
                "test",
                "sensor",
            ),
            runtime.shownCommands.map {
                it.name
            },
        )

        assertTrue(
            "sensor" in names,
        )
    }
}
