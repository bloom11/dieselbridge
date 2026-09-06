// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import android.content.Context
import org.aaustralian.dieselbridge.BuildConfig
import org.aaustralian.dieselbridge.ble.ProbeStateHolder
import org.aaustralian.dieselbridge.platform.capability.BatteryCapability
import org.aaustralian.dieselbridge.platform.provider.ProviderStatus
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue

/**
 * Watch-process implementation of developer command effects.
 *
 * This class may touch Android/platform runtime state. The generic command
 * module and protocol engine do not.
 */
class WatchDeveloperCommandRuntime(
    context: Context,
) : DeveloperCommandRuntime {

    private val notifier =
        DiagnosticsNotifier(context)

    override fun showDiagnostics():
        DieselCommandResult {
        val platform =
            DeveloperRuntimeAccess
                .platform
                .value

        val probe =
            ProbeStateHolder
                .state
                .value

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
                    "diagnostics requested over Diesel protocol",
            )

        ProbeStateHolder.log(
            "diesel command diagnostics",
        )

        notifier.show(
            probe = probe,
            batteryPercent =
                battery?.percent,
            charging =
                battery?.charging,
            batteryProviderId =
                batteryProviderId,
        )

        val data =
            linkedMapOf<String, DieselValue>(
                "build" to
                    DieselValue.Text(
                        BuildConfig.VERSION_NAME,
                    ),
                "bleConnected" to
                    DieselValue.Flag(
                        probe.centralConnected,
                    ),
                "notifySubscribed" to
                    DieselValue.Flag(
                        probe.notifySubscribed,
                    ),
                "advertising" to
                    DieselValue.Flag(
                        probe.advertising,
                    ),
            )

        battery?.let {
            data["batteryPercent"] =
                DieselValue.Integer(
                    it.percent.toLong(),
                )

            data["charging"] =
                DieselValue.Flag(
                    it.charging,
                )
        }

        batteryProviderId?.let {
            data["batteryProviderId"] =
                DieselValue.Text(it)
        }

        return DieselCommandResult.ok(
            data = data,
        )
    }

    override fun showCommands(
        commands: List<DieselCommandSpec>,
    ) {
        val commandNames =
            commands.joinToString(", ") {
                it.name
            }

        DeveloperRuntimeAccess
            .platform
            .value
            ?.diagnostics
            ?.record(
                type = "developer-command",
                message =
                    "supported developer commands: $commandNames",
            )

        ProbeStateHolder.log(
            "diesel commands: $commandNames",
        )

        notifier.showCommands(
            commands = commands,
        )
    }

    override fun runSafeTest(
        target: String?,
    ): DieselCommandResult {
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

            return DieselCommandResult(
                status =
                    DieselResponseStatus.UNAVAILABLE,
                data =
                    mapOf(
                        "reason" to
                            DieselValue.Text(
                                "runner",
                            ),
                    ),
            )
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

        return result.toDieselResult()
    }

    private fun SafePlatformTestResult.toDieselResult():
        DieselCommandResult =
        when (this) {
            is SafePlatformTestResult.Success ->
                DieselCommandResult.ok(
                    data =
                        mapOf<String, DieselValue>(
                            "target" to
                                DieselValue.Text(
                                    target,
                                ),
                            "providerId" to
                                DieselValue.Text(
                                    providerId,
                                ),
                            "durationMs" to
                                DieselValue.Integer(
                                    SafePlatformTestRunner
                                        .VIBRATION_TEST_DURATION_MS,
                                ),
                        ),
                )

            is SafePlatformTestResult.Unavailable ->
                DieselCommandResult(
                    status =
                        DieselResponseStatus.UNAVAILABLE,
                    data =
                        mapOf(
                            "target" to
                                DieselValue.Text(
                                    target,
                                ),
                        ),
                )

            is SafePlatformTestResult.RateLimited ->
                DieselCommandResult(
                    status =
                        DieselResponseStatus.RATE_LIMITED,
                    data =
                        mapOf<String, DieselValue>(
                            "target" to
                                DieselValue.Text(
                                    target,
                                ),
                            "retryAfterMs" to
                                DieselValue.Integer(
                                    retryAfterMs,
                                ),
                        ),
                )

            is SafePlatformTestResult.Failed ->
                DieselCommandResult(
                    status =
                        DieselResponseStatus.FAILED,
                    data =
                        mapOf<String, DieselValue>(
                            "target" to
                                DieselValue.Text(
                                    target,
                                ),
                            "reason" to
                                DieselValue.Text(
                                    "execution",
                                ),
                        ),
                )

            is SafePlatformTestResult.UnknownTarget -> {
                val data =
                    target
                        ?.let {
                            mapOf(
                                "target" to
                                    DieselValue.Text(
                                        it,
                                    ),
                            )
                        }
                        ?: emptyMap()

                DieselCommandResult(
                    status =
                        DieselResponseStatus.UNKNOWN_TARGET,
                    data = data,
                )
            }
        }
}
