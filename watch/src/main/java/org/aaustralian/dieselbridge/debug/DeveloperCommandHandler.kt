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
 * Keep commands narrow and explicit. This is not a general remote shell or
 * arbitrary Android-intent bridge.
 */
class DeveloperCommandHandler(
    context: Context,
) {

    private val notifier =
        DiagnosticsNotifier(context)

    fun handle(
        message: GbMessage.DieselCommand,
    ) {
        when (message.command) {
            COMMAND_DIAGNOSTICS ->
                showDiagnostics()

            else ->
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
    }
}
