// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

/**
 * Generic discoverable metadata for one Diesel command.
 *
 * [metadata] deliberately remains structured and extensible rather than
 * hard-coding developer-, sensor-, alarm- or plugin-specific properties into
 * the registry.
 */
data class DieselCommandSpec(
    val name: String,
    val summary: String,
    val metadata: Map<String, DieselValue> = emptyMap(),
) {
    init {
        require(
            DieselProtocolRules
                .isValidIdentifier(name),
        ) {
            "Invalid Diesel command name '$name'"
        }

        require(
            summary.isNotBlank() &&
                summary.length <=
                    DieselProtocolRules
                        .MAX_COMMAND_SUMMARY_LENGTH,
        ) {
            "Diesel command summary must be 1.." +
                "${DieselProtocolRules.MAX_COMMAND_SUMMARY_LENGTH} characters"
        }

        require(
            metadata.size <=
                DieselProtocolRules
                    .MAX_TOP_LEVEL_FIELDS,
        ) {
            "Diesel command metadata contains too many fields"
        }
    }
}

/**
 * Context visible to a command handler.
 *
 * Correlation and transport mechanics remain in the protocol engine.
 */
data class DieselCommandContext(
    val request: DieselRequest,
) {
    val command: String
        get() = request.command

    val name: String?
        get() = request.name

    val args: Map<String, DieselValue>
        get() = request.args
}

/**
 * Generic result returned by every Diesel command implementation.
 */
data class DieselCommandResult(
    val status: DieselResponseStatus,
    val data: Map<String, DieselValue> = emptyMap(),
) {
    init {
        require(
            data.size <=
                DieselProtocolRules
                    .MAX_TOP_LEVEL_FIELDS,
        ) {
            "Diesel command result contains too many fields"
        }
    }

    companion object {
        fun ok(
            data: Map<String, DieselValue> =
                emptyMap(),
        ): DieselCommandResult =
            DieselCommandResult(
                status = DieselResponseStatus.OK,
                data = data,
            )
    }
}

fun interface DieselCommandHandler {

    fun execute(
        context: DieselCommandContext,
    ): DieselCommandResult
}

/**
 * Extension point for built-in modules and future plugin bridges.
 */
fun interface DieselCommandModule {

    fun install(
        registry: DieselCommandRegistry,
    )
}

/**
 * Generic command registry.
 *
 * Registration remains the single source of truth for execution and remote
 * discovery. The registry has no knowledge of BLE, Gadgetbridge or individual
 * command families.
 */
class DieselCommandRegistry(
    private val onCatalogChanged:
        (List<DieselCommandSpec>) -> Unit = {},
) {

    private data class Entry(
        val spec: DieselCommandSpec,
        val handler: DieselCommandHandler,
    )

    private val entries =
        linkedMapOf<String, Entry>()

    fun register(
        spec: DieselCommandSpec,
        handler: DieselCommandHandler,
    ) {
        require(
            spec.name !in entries,
        ) {
            "Diesel command '${spec.name}' is already registered"
        }

        val stableSpec =
            spec.copy(
                metadata =
                    spec.metadata.toMap(),
            )

        entries[stableSpec.name] =
            Entry(
                spec = stableSpec,
                handler = handler,
            )

        onCatalogChanged(
            specs(),
        )
    }

    fun install(
        module: DieselCommandModule,
    ) {
        module.install(this)
    }

    fun dispatch(
        request: DieselRequest,
    ): DieselCommandResult {
        val entry =
            entries[request.command]
                ?: return DieselCommandResult(
                    status =
                        DieselResponseStatus
                            .UNKNOWN_COMMAND,
                )

        return entry.handler.execute(
            DieselCommandContext(
                request = request,
            ),
        )
    }

    fun specs(): List<DieselCommandSpec> =
        entries.values.map {
            it.spec
        }

    fun commands(): List<String> =
        entries.keys.toList()
}
