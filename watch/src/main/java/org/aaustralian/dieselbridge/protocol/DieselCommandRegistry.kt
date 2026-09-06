// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

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
 *
 * No command handler writes to BLE, Gadgetbridge, Binder or JSON directly.
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

/**
 * Command execution contract.
 *
 * Handlers should perform bounded/non-blocking control-plane work. Long-lived
 * data flows will use the later Diesel event/subscription layer rather than
 * blocking this request path.
 */
fun interface DieselCommandHandler {

    fun execute(
        context: DieselCommandContext,
    ): DieselCommandResult
}

/**
 * Extension point for built-in modules and, later, plugin bridges.
 *
 * A module installs commands into the generic registry without modifying the
 * protocol engine or any transport.
 */
fun interface DieselCommandModule {

    fun install(
        registry: DieselCommandRegistry,
    )
}

/**
 * Generic command dispatcher.
 *
 * This registry deliberately knows nothing about Gadgetbridge, Bluetooth,
 * developer tooling, sensors, alarms or any other command implementation.
 */
class DieselCommandRegistry {

    private val handlers =
        linkedMapOf<String, DieselCommandHandler>()

    fun register(
        command: String,
        handler: DieselCommandHandler,
    ) {
        require(
            DieselProtocolRules
                .isValidIdentifier(command),
        ) {
            "Invalid Diesel command name '$command'"
        }

        require(
            command !in handlers,
        ) {
            "Diesel command '$command' is already registered"
        }

        handlers[command] = handler
    }

    fun install(
        module: DieselCommandModule,
    ) {
        module.install(this)
    }

    fun dispatch(
        request: DieselRequest,
    ): DieselCommandResult {
        val handler =
            handlers[request.command]
                ?: return DieselCommandResult(
                    status =
                        DieselResponseStatus
                            .UNKNOWN_COMMAND,
                )

        return handler.execute(
            DieselCommandContext(
                request = request,
            ),
        )
    }

    fun commands(): List<String> =
        handlers.keys.toList()
}
