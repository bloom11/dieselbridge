// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.protocol.DieselCommandModule
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselValue

interface DeveloperCommandRuntime {

    fun showDiagnostics(): DieselCommandResult

    fun showCommands(
        commands: List<DieselCommandSpec>,
    )

    fun runSafeTest(
        target: String?,
    ): DieselCommandResult
}

/**
 * Developer functionality is a normal Diesel command module.
 *
 * DieselCommandSpec is the single source of truth for both execution and
 * discovery. No developer-specific command registry exists anymore.
 */
class DeveloperCommandModule(
    private val runtime: DeveloperCommandRuntime,
) : DieselCommandModule {

    private val commandSpecs =
        listOf(
            developerSpec(
                name = COMMAND_DIAGNOSTICS,
                summary =
                    "Show runtime diagnostics notification",
                effect =
                    DeveloperCommandEffect.READ_ONLY,
            ),
            developerSpec(
                name = COMMAND_COMMANDS,
                summary =
                    "List supported Diesel commands",
                effect =
                    DeveloperCommandEffect.READ_ONLY,
            ),
            developerSpec(
                name = COMMAND_TEST,
                summary =
                    "Run a bounded safe platform test",
                effect =
                    DeveloperCommandEffect.SAFE_ACTION,
            ),
        )

    override fun install(
        registry: DieselCommandRegistry,
    ) {
        val diagnostics =
            commandSpecs.first {
                it.name == COMMAND_DIAGNOSTICS
            }

        registry.register(
            diagnostics,
        ) {
            runtime.showDiagnostics()
        }

        val commands =
            commandSpecs.first {
                it.name == COMMAND_COMMANDS
            }

        registry.register(
            commands,
        ) {
            val liveCatalog =
                registry.specs()

            runtime.showCommands(
                liveCatalog,
            )

            commandCatalogResult(
                liveCatalog,
            )
        }

        val test =
            commandSpecs.first {
                it.name == COMMAND_TEST
            }

        registry.register(
            test,
        ) { context ->
            runtime.runSafeTest(
                context.name,
            )
        }
    }

    private fun commandCatalogResult(
        specs: List<DieselCommandSpec>,
    ): DieselCommandResult {
        val values =
            specs.map { spec ->
                val fields =
                    linkedMapOf<String, DieselValue>(
                        "name" to
                            DieselValue.Text(
                                spec.name,
                            ),
                        "summary" to
                            DieselValue.Text(
                                spec.summary,
                            ),
                    )

                if (spec.metadata.isNotEmpty()) {
                    fields["metadata"] =
                        DieselValue.ObjectValue(
                            spec.metadata,
                        )
                }

                DieselValue.ObjectValue(
                    fields,
                )
            }

        return DieselCommandResult.ok(
            data =
                mapOf(
                    "commands" to
                        DieselValue.ListValue(
                            values,
                        ),
                ),
        )
    }

    private fun developerSpec(
        name: String,
        summary: String,
        effect: DeveloperCommandEffect,
    ): DieselCommandSpec =
        DieselCommandSpec(
            name = name,
            summary = summary,
            metadata =
                mapOf(
                    "effect" to
                        DieselValue.Text(
                            effect.wireName,
                        ),
                ),
        )

    private companion object {
        const val COMMAND_DIAGNOSTICS =
            "diagnostics"

        const val COMMAND_COMMANDS =
            "commands"

        const val COMMAND_TEST =
            "test"
    }
}
