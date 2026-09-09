// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlinx.coroutines.runBlocking

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

        var buildInfoCalls = 0

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

        override fun buildInfo():
            DieselCommandResult {
            buildInfoCalls += 1

            return DieselCommandResult.ok(
                data =
                    mapOf(
                        "versionName" to
                            DieselValue.Text(
                                "test-version",
                            ),
                        "gitSha" to
                            DieselValue.Text(
                                "0123456789abcdef",
                            ),
                    ),
            )
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
    fun moduleRegistersExistingDeveloperCommands(): Unit = runBlocking {
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
                "debug.build.info",
                "test",
            ),
            registry.commands(),
        )

        assertEquals(
            4,
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
    fun diagnosticsUsesGenericResultPath(): Unit = runBlocking {
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
    fun buildInfoUsesGenericResultPath(): Unit = runBlocking {
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
                    requestId = "build-info-1",
                    command = "debug.build.info",
                ),
            )

        assertEquals(
            1,
            runtime.buildInfoCalls,
        )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        assertEquals(
            DieselValue.Text(
                "test-version",
            ),
            result.data["versionName"],
        )

        assertEquals(
            DieselValue.Text(
                "0123456789abcdef",
            ),
            result.data["gitSha"],
        )
    }

    @Test
    fun buildInfoRejectsNameAndArgsBeforeRuntime(): Unit = runBlocking {
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
                    requestId = "build-info-invalid",
                    command = "debug.build.info",
                    name = "unexpected",
                ),
            )

        assertEquals(
            0,
            runtime.buildInfoCalls,
        )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            result.status,
        )

        assertEquals(
            DieselValue.Text(
                "invalid_args",
            ),
            result.data["reason"],
        )

        val argsResult =
            registry.dispatch(
                DieselRequest(
                    requestId = "build-info-invalid-args",
                    command = "debug.build.info",
                    args =
                        mapOf(
                            "unexpected" to
                                DieselValue.Flag(true),
                        ),
                ),
            )

        assertEquals(
            0,
            runtime.buildInfoCalls,
        )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            argsResult.status,
        )

        assertEquals(
            DieselValue.Text(
                "invalid_args",
            ),
            argsResult.data["reason"],
        )
    }

    @Test
    fun testTargetIsPassedWithoutTransportKnowledge(): Unit = runBlocking {
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
    fun commandsDiscoveryIncludesLaterModulesAutomatically(): Unit = runBlocking {
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
                "debug.build.info",
                "test",
                "sensor",
            ),
            names,
        )

        assertEquals(
            listOf(
                "diagnostics",
                "commands",
                "debug.build.info",
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
