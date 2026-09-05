// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.protocol.GbMessage

/**
 * Safety classification for remotely reachable Diesel developer commands.
 *
 * SAFE_ACTION still means explicitly implemented and bounded. It never means
 * arbitrary method, Intent, shell or reflection access.
 */
enum class DeveloperCommandEffect {
    READ_ONLY,
    SAFE_ACTION,
}

/**
 * Public metadata for one supported Diesel developer command.
 *
 * The registry is also the source for command discovery, so adding a command
 * automatically makes it visible through the discovery catalog.
 */
data class DeveloperCommandSpec(
    val name: String,
    val summary: String,
    val effect: DeveloperCommandEffect,
)

/**
 * Explicit allow-list and dispatcher for the Diesel developer command namespace.
 *
 * Registration is the single source of truth for both dispatch and discovery.
 */
class DeveloperCommandRegistry(
    private val onCatalogChanged:
        (List<DeveloperCommandSpec>) -> Unit = {},
) {

    private data class Entry(
        val spec: DeveloperCommandSpec,
        val handler: (GbMessage.DieselCommand) -> Unit,
    )

    private val entries =
        linkedMapOf<String, Entry>()

    fun register(
        spec: DeveloperCommandSpec,
        handler: (GbMessage.DieselCommand) -> Unit,
    ) {
        require(COMMAND_NAME.matches(spec.name)) {
            "Invalid developer command name '${spec.name}'"
        }

        require(spec.name !in entries) {
            "Developer command '${spec.name}' is already registered"
        }

        entries[spec.name] =
            Entry(
                spec = spec,
                handler = handler,
            )

        onCatalogChanged(specs())
    }

    /**
     * Dispatches only an explicitly registered command.
     *
     * false means the command is unknown and nothing was executed.
     */
    fun dispatch(
        message: GbMessage.DieselCommand,
    ): Boolean {
        val entry =
            entries[message.command]
                ?: return false

        entry.handler(message)
        return true
    }

    /**
     * Registration-order catalog used by diagnostics/discovery surfaces.
     */
    fun specs(): List<DeveloperCommandSpec> =
        entries.values.map { it.spec }

    private companion object {
        val COMMAND_NAME =
            Regex("[a-z][a-z0-9_-]*")
    }
}
