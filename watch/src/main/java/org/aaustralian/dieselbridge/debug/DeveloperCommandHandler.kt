// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import android.content.Context
import org.aaustralian.dieselbridge.ble.ProbeStateHolder
import org.aaustralian.dieselbridge.platform.capability.BatteryCapability
import org.aaustralian.dieselbridge.platform.provider.ProviderStatus
import org.aaustralian.dieselbridge.protocol.GbMessage

/**
 * Handles the Diesel-specific development command namespace.
 *
 * Commands are registered explicitly. This is not a general remote shell,
 * arbitrary Android-intent bridge, reflection dispatcher or method bridge.
 */
class DeveloperCommandHandler(
    context: Context,
) {

    private val notifier =
        DiagnosticsNotifier(context)

    private val registry =
        DeveloperCommandRegistry(
            onCatalogChanged =
                DeveloperRuntimeAccess::publishCommandCatalog,
        )

    init {
        registry.register(
            DeveloperCommandSpec(
                name = COMMAND_DIAGNOSTICS,
                summary = "Show runtime diagnostics notification",
                effect = DeveloperCommandEffect.READ_ONLY,
            ),
        ) { _ ->
            showDiagnostics()
        }

        registry.register(
            DeveloperCommandSpec(
                name = COMMAND_COMMANDS,
                summary = "List supported Diesel developer commands",
                effect = DeveloperCommandEffect.READ_ONLY,
            ),
        ) { _ ->
            showCommands()
        }
    }

    fun handle(
        message: GbMessage.DieselCommand,
    ) {
        if (!registry.dispatch(message)) {
            recordUnknownCommand(
                message.command,
            )
        }
    }

    private fun showDiagnostics() {
        val platform =
            DeveloperRuntimeAccess.platform.value

        val probe =
            ProbeStateHolder.state.value

        val battery =
            platform
                ?.battery
                ?.current()

        val batteryProviderId =
            platform
                ?.diagnostics
                ?.state
                ?.value
                ?.providers
                ?.firstOrNull {
                    it.capabilityId ==
                        BatteryCapability.ID &&
                        it.status ==
                        ProviderStatus.ACTIVE
                }
                ?.providerId

        platform
            ?.diagnostics
            ?.record(
                type = "developer-command",
                message =
                    "diagnostics requested over Gadgetbridge",
            )

        ProbeStateHolder.log(
            "diesel command diagnostics",
        )

        notifier.show(
            probe = probe,
            batteryPercent = battery?.percent,
            charging = battery?.charging,
            batteryProviderId = batteryProviderId,
        )
    }

    /**
     * Discovery is generated directly from the same registrations used for
     * dispatch, so it cannot silently drift away from supported commands.
     *
     * D4.2 will expose the same metadata in a dedicated watch UI/notification.
     */
    private fun showCommands() {
        val commandNames =
            registry
                .specs()
                .joinToString(", ") {
                    it.name
                }

        DeveloperRuntimeAccess
            .platform
            .value
            ?.diagnostics
            ?.record(
                type = "developer-command",
                message =
                    "supported commands: $commandNames",
            )

        ProbeStateHolder.log(
            "diesel commands: $commandNames",
        )

        notifier.showCommands(
            commands = registry.specs(),
        )
    }

    private fun recordUnknownCommand(
        command: String,
    ) {
        DeveloperRuntimeAccess
            .platform
            .value
            ?.diagnostics
            ?.record(
                type = "developer-command",
                message =
                    "ignored unknown command: $command",
            )

        ProbeStateHolder.log(
            "diesel command ignored: $command",
        )
    }

    private companion object {
        const val COMMAND_DIAGNOSTICS =
            "diagnostics"

        const val COMMAND_COMMANDS =
            "commands"
    }
}
