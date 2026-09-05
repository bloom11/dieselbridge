// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ui.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.text.DateFormat
import java.util.Date
import org.aaustralian.dieselbridge.BuildConfig
import org.aaustralian.dieselbridge.ble.ProbeReport
import org.aaustralian.dieselbridge.ble.ProbeStateHolder
import org.aaustralian.dieselbridge.debug.DeveloperRuntimeAccess
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.capability.BatteryCapability
import org.aaustralian.dieselbridge.platform.provider.ProviderBindingInfo
import org.aaustralian.dieselbridge.platform.provider.ProviderStatus

private val CardBackground = Color(0xFF202124)
private val PrimaryText = Color(0xFFF1F3F4)
private val SecondaryText = Color(0xFF9AA0A6)
private val ActiveText = Color(0xFF81C995)
private val WarningText = Color(0xFFFDD663)
private val ErrorText = Color(0xFFF28B82)
private val AccentText = Color(0xFF8AB4F8)
private val ChipBackground = Color(0xFF3C4043)

private enum class DiagnosticsPage {
    OVERVIEW,
    PLATFORM,
    BLUETOOTH,
    LOGS,
}

@Composable
fun DiagnosticsScreen() {
    val platform by
        DeveloperRuntimeAccess.platform.collectAsStateWithLifecycle()

    val probe by
        ProbeStateHolder.state.collectAsStateWithLifecycle()

    var page by remember {
        mutableStateOf(DiagnosticsPage.OVERVIEW)
    }

    BackHandler(
        enabled = page != DiagnosticsPage.OVERVIEW,
    ) {
        page = DiagnosticsPage.OVERVIEW
    }

    MaterialTheme {
        val currentPlatform = platform

        if (currentPlatform == null) {
            OfflineDiagnosticsScreen()
        } else {
            when (page) {
                DiagnosticsPage.OVERVIEW ->
                    OverviewScreen(
                        platform = currentPlatform,
                        probe = probe,
                        onPlatform = {
                            page = DiagnosticsPage.PLATFORM
                        },
                        onBluetooth = {
                            page = DiagnosticsPage.BLUETOOTH
                        },
                        onLogs = {
                            page = DiagnosticsPage.LOGS
                        },
                    )

                DiagnosticsPage.PLATFORM ->
                    PlatformScreen(
                        platform = currentPlatform,
                        onBack = {
                            page = DiagnosticsPage.OVERVIEW
                        },
                    )

                DiagnosticsPage.BLUETOOTH ->
                    BluetoothScreen(
                        probe = probe,
                        onBack = {
                            page = DiagnosticsPage.OVERVIEW
                        },
                    )

                DiagnosticsPage.LOGS ->
                    LogsScreen(
                        platform = currentPlatform,
                        probe = probe,
                        onBack = {
                            page = DiagnosticsPage.OVERVIEW
                        },
                    )
            }
        }
    }
}

@Composable
private fun OverviewScreen(
    platform: DieselPlatform,
    probe: ProbeReport,
    onPlatform: () -> Unit,
    onBluetooth: () -> Unit,
    onLogs: () -> Unit,
) {
    val diagnostics by
        platform.diagnostics.state.collectAsStateWithLifecycle()

    val battery by
        platform.battery.state.collectAsStateWithLifecycle()

    val providers = diagnostics.providers

    val activeProviders =
        providers.count {
            it.status == ProviderStatus.ACTIVE
        }

    val providerErrors =
        providers.count {
            it.status == ProviderStatus.ERROR ||
                it.status == ProviderStatus.UNAVAILABLE
        }

    val batteryProvider =
        providers.firstOrNull {
            it.capabilityId == BatteryCapability.ID &&
                it.status == ProviderStatus.ACTIVE
        }

    DeveloperPage(
        title = "Diesel Developer",
        subtitle = "● Runtime active · ${BuildConfig.VERSION_NAME}",
    ) {
        DiagnosticCard(
            title = "BLUETOOTH",
            primary =
                when {
                    !probe.bluetoothOn ->
                        "Bluetooth off"

                    probe.centralConnected ->
                        "Connected"

                    probe.advertising ->
                        "Advertising"

                    else ->
                        "Starting"
                },
            secondary =
                when {
                    probe.centralConnected ->
                        buildString {
                            append(
                                probe.connectedDeviceName
                                    ?: "central",
                            )
                            append(" · ")
                            append(
                                if (probe.notifySubscribed) {
                                    "subscribed"
                                } else {
                                    "not subscribed"
                                },
                            )
                        }

                    probe.advertising ->
                        "Waiting for Gadgetbridge"

                    else ->
                        null
                },
            healthy =
                probe.bluetoothOn &&
                    probe.centralConnected &&
                    probe.notifySubscribed,
            onClick = onBluetooth,
        )

        DiagnosticCard(
            title = "BATTERY",
            primary =
                battery?.let { state ->
                    buildString {
                        append(state.percent)
                        append("%")

                        if (state.charging) {
                            append(" · charging")
                        }
                    }
                } ?: "No state",
            secondary =
                batteryProvider?.let { provider ->
                    buildString {
                        append(provider.providerId)
                        append(" · priority ")
                        append(provider.priority)

                        probe.lastBatteryTxReason?.let {
                            append(" · TX ")
                            append(it)
                        }
                    }
                } ?: "No active provider",
            healthy =
                battery != null &&
                    batteryProvider != null,
            onClick = onPlatform,
        )

        DiagnosticCard(
            title = "PLATFORM",
            primary =
                "$activeProviders active · ${providers.size} registered",
            secondary =
                if (providerErrors == 0) {
                    "No provider errors"
                } else {
                    "$providerErrors unavailable/error"
                },
            healthy = providerErrors == 0,
            onClick = onPlatform,
        )

        DiagnosticCard(
            title = "DIAGNOSTICS",
            primary =
                "${diagnostics.recentRecords.size} platform records",
            secondary =
                "${probe.log.size} BLE records",
            healthy = true,
            onClick = onLogs,
        )

        DiagnosticCard(
            title = "BUILD",
            primary = BuildConfig.VERSION_NAME,
            secondary =
                if (BuildConfig.DEBUG) {
                    "debug build"
                } else {
                    "release build"
                },
            healthy = true,
        )
    }
}

@Composable
private fun PlatformScreen(
    platform: DieselPlatform,
    onBack: () -> Unit,
) {
    val diagnostics by
        platform.diagnostics.state.collectAsStateWithLifecycle()

    val battery by
        platform.battery.state.collectAsStateWithLifecycle()

    val providers =
        diagnostics.providers.sortedWith(
            compareBy<ProviderBindingInfo>(
                { it.capabilityId },
                { -it.priority },
                { it.registrationOrder },
            ),
        )

    DeveloperPage(
        title = "Platform",
        subtitle = "${providers.size} provider bindings",
        onBack = onBack,
    ) {
        battery?.let { state ->
            DiagnosticCard(
                title = "BATTERY STATE",
                primary =
                    "${state.percent}%${
                        if (state.charging) {
                            " · charging"
                        } else {
                            ""
                        }
                    }",
                secondary =
                    "${state.voltageVolts} V",
                healthy = true,
            )
        }

        if (providers.isEmpty()) {
            DiagnosticCard(
                title = "PROVIDERS",
                primary = "None registered",
                secondary = null,
                healthy = false,
            )
        } else {
            providers.forEach { provider ->
                ProviderCard(provider)
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderBindingInfo,
) {
    val healthy =
        provider.status == ProviderStatus.ACTIVE ||
            provider.status == ProviderStatus.STANDBY

    val statusColor =
        when (provider.status) {
            ProviderStatus.ACTIVE ->
                ActiveText

            ProviderStatus.STANDBY ->
                AccentText

            ProviderStatus.UNAVAILABLE ->
                WarningText

            ProviderStatus.ERROR ->
                ErrorText
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(CardBackground)
                .padding(
                    horizontal = 14.dp,
                    vertical = 11.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = provider.capabilityId.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AccentText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = provider.providerId,
            style = MaterialTheme.typography.titleSmall,
            color =
                if (healthy) {
                    PrimaryText
                } else {
                    WarningText
                },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text =
                "${provider.status} · priority ${provider.priority}",
            style = MaterialTheme.typography.bodySmall,
            color = statusColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text =
                provider.providerClass
                    .substringAfterLast('.'),
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = "order ${provider.registrationOrder}",
            style = MaterialTheme.typography.labelSmall,
            color = SecondaryText,
            maxLines = 1,
        )

        provider.reason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = WarningText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BluetoothScreen(
    probe: ProbeReport,
    onBack: () -> Unit,
) {
    DeveloperPage(
        title = "Bluetooth",
        subtitle =
            if (probe.centralConnected) {
                "● Central connected"
            } else {
                "○ No central"
            },
        onBack = onBack,
    ) {
        DiagnosticCard(
            title = "LINK",
            primary =
                if (probe.centralConnected) {
                    probe.connectedDeviceName
                        ?: "Connected"
                } else {
                    "Disconnected"
                },
            secondary =
                if (probe.notifySubscribed) {
                    "NUS notifications subscribed"
                } else {
                    "NUS not subscribed"
                },
            healthy =
                probe.centralConnected &&
                    probe.notifySubscribed,
        )

        DiagnosticCard(
            title = "GATT SERVER",
            primary =
                if (probe.gattServerOpen) {
                    "Open"
                } else {
                    "Closed"
                },
            secondary =
                buildString {
                    append(
                        if (probe.advertising) {
                            "advertising"
                        } else {
                            "not advertising"
                        },
                    )

                    append(" · ")

                    append(
                        if (probe.multipleAdvSupported) {
                            "multi-adv"
                        } else {
                            "single-adv"
                        },
                    )
                },
            healthy =
                probe.gattServerOpen &&
                    probe.advertising,
        )

        DiagnosticCard(
            title = "BATTERY TX",
            primary =
                probe.lastBatteryTxPercent
                    ?.let { "$it%" }
                    ?: "No transmission yet",
            secondary =
                if (probe.lastBatteryTxPercent == null) {
                    null
                } else {
                    buildString {
                        append(
                            probe.lastBatteryTxReason
                                ?: "unknown",
                        )

                        append(" · ")

                        append(
                            when (probe.lastBatteryTxSucceeded) {
                                true -> "sent"
                                false -> "failed"
                                null -> "unknown"
                            },
                        )

                        probe.lastBatteryTxAtMs?.let {
                            append(" · ")
                            append(formatTimestamp(it))
                        }

                        append(" · #")
                        append(probe.batteryTxAttempts)
                    }
                },
            healthy =
                probe.lastBatteryTxSucceeded == true,
        )

        DiagnosticCard(
            title = "CURRENT BATTERY",
            primary =
                probe.batteryPct
                    ?.let { "$it%" }
                    ?: "Unknown",
            secondary =
                if (probe.charging) {
                    "charging"
                } else {
                    "not charging"
                },
            healthy = probe.batteryPct != null,
        )

        DiagnosticCard(
            title = "RX TRAFFIC",
            primary =
                "${probe.bytesReceived} bytes",
            secondary =
                probe.lastLine
                    ?.take(100)
                    ?: "No received line yet",
            healthy = probe.centralConnected,
        )

        probe.advertiseError?.let { error ->
            DiagnosticCard(
                title = "ADVERTISE ERROR",
                primary = error,
                secondary = null,
                healthy = false,
            )
        }
    }
}

@Composable
private fun LogsScreen(
    platform: DieselPlatform,
    probe: ProbeReport,
    onBack: () -> Unit,
) {
    val diagnostics by
        platform.diagnostics.state.collectAsStateWithLifecycle()

    DeveloperPage(
        title = "Logs",
        subtitle = "Process-local · bounded",
        onBack = onBack,
    ) {
        SectionLabel("PLATFORM")

        if (diagnostics.recentRecords.isEmpty()) {
            LogCard(
                title = "No platform records",
                body = null,
            )
        } else {
            diagnostics.recentRecords
                .takeLast(20)
                .asReversed()
                .forEach { record ->
                    LogCard(
                        title =
                            "${formatTimestamp(record.timestampMs)} · ${record.type}",
                        body = record.message,
                    )
                }
        }

        SectionLabel("BLE")

        if (probe.log.isEmpty()) {
            LogCard(
                title = "No BLE records",
                body = null,
            )
        } else {
            probe.log
                .takeLast(20)
                .asReversed()
                .forEach { line ->
                    LogCard(
                        title = line,
                        body = null,
                    )
                }
        }
    }
}

@Composable
private fun DeveloperPage(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val configuration = LocalConfiguration.current

    val horizontalPadding =
        if (configuration.isScreenRound) {
            14.dp
        } else {
            10.dp
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .rotaryScrollable(
                    RotaryScrollableDefaults.behavior(
                        scrollableState = scrollState,
                    ),
                    focusRequester,
                )
                .verticalScroll(scrollState)
                .padding(
                    horizontal = horizontalPadding,
                    vertical = 28.dp,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (onBack != null) {
            NavigationChip(
                label = "‹ Overview",
                onClick = onBack,
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = PrimaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = SecondaryText,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
        )

        content()
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    primary: String,
    secondary: String?,
    healthy: Boolean,
    onClick: (() -> Unit)? = null,
) {
    val clickModifier =
        if (onClick == null) {
            Modifier
        } else {
            Modifier.clickable(onClick = onClick)
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(CardBackground)
                .then(clickModifier)
                .padding(
                    horizontal = 14.dp,
                    vertical = 11.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = AccentText,
            maxLines = 1,
        )

        Text(
            text =
                if (onClick == null) {
                    primary
                } else {
                    "$primary  ›"
                },
            style = MaterialTheme.typography.titleSmall,
            color =
                if (healthy) {
                    PrimaryText
                } else {
                    WarningText
                },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        secondary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = SecondaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NavigationChip(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = PrimaryText,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ChipBackground)
                .clickable(onClick = onClick)
                .padding(
                    horizontal = 12.dp,
                    vertical = 9.dp,
                ),
    )
}

@Composable
private fun SectionLabel(
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = AccentText,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp),
    )
}

@Composable
private fun LogCard(
    title: String,
    body: String?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBackground)
                .padding(
                    horizontal = 12.dp,
                    vertical = 9.dp,
                ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = PrimaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        body?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = SecondaryText,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OfflineDiagnosticsScreen() {
    val configuration = LocalConfiguration.current

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(
                    horizontal =
                        if (configuration.isScreenRound) {
                            18.dp
                        } else {
                            12.dp
                        },
                    vertical = 36.dp,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Diesel Developer",
            style = MaterialTheme.typography.titleMedium,
            color = PrimaryText,
            textAlign = TextAlign.Center,
        )

        Text(
            text = "Runtime unavailable",
            style = MaterialTheme.typography.bodyMedium,
            color = WarningText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        Text(
            text =
                "Open DieselBridge and wait for the service to start.",
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

private fun formatTimestamp(
    timestampMs: Long,
): String =
    DateFormat
        .getTimeInstance(DateFormat.MEDIUM)
        .format(Date(timestampMs))
