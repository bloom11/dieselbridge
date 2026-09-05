// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ui.debug

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
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
import org.aaustralian.dieselbridge.BuildConfig
import org.aaustralian.dieselbridge.ble.ProbeReport
import org.aaustralian.dieselbridge.ble.ProbeStateHolder
import org.aaustralian.dieselbridge.debug.DeveloperRuntimeAccess
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.capability.BatteryCapability
import org.aaustralian.dieselbridge.platform.provider.ProviderStatus

private val CardBackground = Color(0xFF202124)
private val PrimaryText = Color(0xFFF1F3F4)
private val SecondaryText = Color(0xFF9AA0A6)
private val ActiveText = Color(0xFF81C995)
private val WarningText = Color(0xFFFDD663)
private val AccentText = Color(0xFF8AB4F8)

@Composable
fun DiagnosticsScreen() {
    val platform by
        DeveloperRuntimeAccess.platform.collectAsStateWithLifecycle()

    val probe by
        ProbeStateHolder.state.collectAsStateWithLifecycle()

    MaterialTheme {
        val currentPlatform = platform

        if (currentPlatform == null) {
            OfflineDiagnosticsScreen()
        } else {
            OnlineDiagnosticsScreen(
                platform = currentPlatform,
                probe = probe,
            )
        }
    }
}

@Composable
private fun OnlineDiagnosticsScreen(
    platform: DieselPlatform,
    probe: ProbeReport,
) {
    val diagnostics by
        platform.diagnostics.state.collectAsStateWithLifecycle()

    val battery by
        platform.battery.state.collectAsStateWithLifecycle()

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
        Text(
            text = "Diesel Developer",
            style = MaterialTheme.typography.titleMedium,
            color = PrimaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "● Runtime active · ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = ActiveText,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
        )

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
                    "${provider.providerId} · priority ${provider.priority}"
                } ?: "No active provider",
            healthy =
                battery != null &&
                    batteryProvider != null,
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
        )

        DiagnosticCard(
            title = "DIAGNOSTICS",
            primary =
                "${diagnostics.recentRecords.size} recent records",
            secondary =
                diagnostics.recentRecords
                    .lastOrNull()
                    ?.let { "${it.type}: ${it.message}" }
                    ?: "No diagnostic records yet",
            healthy = true,
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
            text = "Open DieselBridge and wait for the service to start.",
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun DiagnosticCard(
    title: String,
    primary: String,
    secondary: String?,
    healthy: Boolean,
) {
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
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = AccentText,
            maxLines = 1,
        )

        Text(
            text = primary,
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
