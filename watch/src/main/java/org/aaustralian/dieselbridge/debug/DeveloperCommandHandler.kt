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

        registry.register(
            DeveloperCommandSpec(
                name = COMMAND_TEST,
                summary = "Run a bounded safe platform test",
                effect = DeveloperCommandEffect.SAFE_ACTION,
            ),
        ) { message ->
            runSafeTest(
                target = message.name,
            )
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

    private fun runSafeTest(
        target: String?,
    ) {
        val runner =
            DeveloperRuntimeAccess
                .safeTestRunner
                .value

        if (runner == null) {
            DeveloperRuntimeAccess
                .platform
                .value
                ?.diagnostics
                ?.record(
                    type = "developer-test",
                    message =
                        "safe-test runner unavailable for " +
                            (target ?: "<missing>"),
                )

            ProbeStateHolder.log(
                "diesel test " +
                    (target ?: "<missing>") +
                    " unavailable: runner",
            )

            return
        }

        val result =
            runner.run(
                target = target,
            )

        ProbeStateHolder.log(
            when (result) {
                is SafePlatformTestResult.Success ->
                    "diesel test ${result.target} " +
                        "success provider=${result.providerId}"

                is SafePlatformTestResult.Unavailable ->
                    "diesel test ${result.target} unavailable"

                is SafePlatformTestResult.RateLimited ->
                    "diesel test ${result.target} " +
                        "rate-limited retryAfterMs=" +
                        result.retryAfterMs

                is SafePlatformTestResult.Failed ->
                    "diesel test ${result.target} " +
                        "failed: ${result.message}"

                is SafePlatformTestResult.UnknownTarget ->
                    "diesel test ignored target=" +
                        (result.target ?: "<missing>")
            },
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

        const val COMMAND_TEST =
            "test"
    }
}
