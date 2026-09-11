// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ui.debug

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.aaustralian.dieselbridge.system.BatteryUsageMonitor
import org.aaustralian.dieselbridge.system.BatteryUsageSnapshot
import org.aaustralian.dieselbridge.system.PowerHelper
import org.aaustralian.dieselbridge.system.PowerMode
import org.aaustralian.dieselbridge.system.PowerPolicy
import org.aaustralian.dieselbridge.sensor.SensorMatrixExperiment
import org.aaustralian.dieselbridge.sensor.SensorMatrixExperimentResult
import org.aaustralian.dieselbridge.sensor.SensorReadCoordinator
import org.aaustralian.dieselbridge.platform.sensor.SensorReadOptions
import org.aaustralian.dieselbridge.platform.sensor.SensorReadResult
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
import org.aaustralian.dieselbridge.debug.DeveloperBuildInfoSource
import org.aaustralian.dieselbridge.debug.DeveloperRemoteAccessPolicy
import org.aaustralian.dieselbridge.debug.DeveloperRuntimeAccess
import org.aaustralian.dieselbridge.debug.SafePlatformTestResult
import org.aaustralian.dieselbridge.debug.SafePlatformTestRunner
import org.aaustralian.dieselbridge.debug.SafePlatformTestSpec
import org.aaustralian.dieselbridge.debug.SensorProbeRecord
import org.aaustralian.dieselbridge.debug.SensorProbeStoreSnapshot
import org.aaustralian.dieselbridge.debug.SensorScanTerminalReason
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.capability.BatteryCapability
import org.aaustralian.dieselbridge.platform.provider.ProviderBindingInfo
import org.aaustralian.dieselbridge.platform.provider.ProviderStatus
import org.aaustralian.dieselbridge.platform.sensor.SensorInventory
import org.aaustralian.dieselbridge.platform.sensor.SensorInventoryEntry
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue

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
    SENSORS,
    BLUETOOTH,
    COMMANDS,
    COMMAND_DETAIL,
    TOOLS,
    TEST_RESULT,
    MATRIX_RESULTS,
    MATRIX_ENTRY_DETAIL,
    SCAN_RESULTS,
    SCAN_RECORD_DETAIL,
    LOGS,
    BUILD,
    POWER,
}

private enum class LogSource {
    PLATFORM,
    BLE,
}

@Composable
fun DiagnosticsScreen(
    openCommandsInitially: Boolean = false,
) {
    val platform by
        DeveloperRuntimeAccess.platform.collectAsStateWithLifecycle()

    val commandCatalog by
        DeveloperRuntimeAccess.commandCatalog.collectAsStateWithLifecycle()

    val commandDispatcher by
        DeveloperRuntimeAccess.commandDispatcher.collectAsStateWithLifecycle()

    val healthServicesRefresh by
        DeveloperRuntimeAccess.healthServicesRefresh.collectAsStateWithLifecycle()

    val safeTestRunner by
        DeveloperRuntimeAccess.safeTestRunner.collectAsStateWithLifecycle()

    val sensorMatrixExperiment by
        DeveloperRuntimeAccess.sensorMatrixExperiment.collectAsStateWithLifecycle()

    val developerRemoteAccessPolicy by
        DeveloperRuntimeAccess
            .developerRemoteAccessPolicy
            .collectAsStateWithLifecycle()

    val sensorInventory by
        DeveloperRuntimeAccess.sensorInventory.collectAsStateWithLifecycle()

    val sensorProbeStoreSnapshot by
        DeveloperRuntimeAccess.sensorProbeStoreSnapshot.collectAsStateWithLifecycle()

    val probe by
        ProbeStateHolder.state.collectAsStateWithLifecycle()

    var selectedCommand by remember { mutableStateOf<DieselCommandSpec?>(null) }
    var lastSafeResult by remember(safeTestRunner) {
        mutableStateOf<SafePlatformTestResult?>(null)
    }
    var matrixResult by remember(sensorMatrixExperiment) {
        mutableStateOf<SensorMatrixExperimentResult?>(null)
    }
    var matrixRunning by remember { mutableStateOf(false) }
    var selectedMatrixEntry by remember {
        mutableStateOf<Pair<String, SensorReadResult>?>(null)
    }
    var scanRunId by remember { mutableStateOf<String?>(null) }
    var scanResult by remember { mutableStateOf<DieselCommandResult?>(null) }
    var selectedScanRecord by remember {
        mutableStateOf<SensorProbeRecord?>(null)
    }

    val scanState =
        (scanResult?.data?.get("state") as? DieselValue.Text)?.value

    LaunchedEffect(
        scanRunId,
        scanState,
        commandDispatcher,
    ) {
        val activeRunId = scanRunId
        val dispatch = commandDispatcher
        if (
            activeRunId != null &&
                scanState in setOf("running", "cancelling") &&
                dispatch != null
        ) {
            while (true) {
                delay(1_000)
                val result = dispatch(
                    DieselRequest(
                        requestId = "local-scan-status",
                        command = "debug.sensor.scan.status",
                        args = mapOf("runId" to DieselValue.Text(activeRunId)),
                    ),
                )
                scanResult = result
                val nextState =
                    (result.data["state"] as? DieselValue.Text)?.value
                if (nextState !in setOf("running", "cancelling")) break
            }
        }
    }

    var page by remember {
        mutableStateOf(
            if (openCommandsInitially) {
                DiagnosticsPage.COMMANDS
            } else {
                DiagnosticsPage.OVERVIEW
            },
        )
    }

    BackHandler(
        enabled = page != DiagnosticsPage.OVERVIEW,
    ) {
        when (page) {
            DiagnosticsPage.COMMAND_DETAIL -> {
                selectedCommand = null
                page = DiagnosticsPage.COMMANDS
            }

            DiagnosticsPage.MATRIX_ENTRY_DETAIL -> {
                selectedMatrixEntry = null
                page = DiagnosticsPage.MATRIX_RESULTS
            }

            DiagnosticsPage.SCAN_RECORD_DETAIL -> {
                selectedScanRecord = null
                page = DiagnosticsPage.SCAN_RESULTS
            }

            DiagnosticsPage.TEST_RESULT,
            DiagnosticsPage.MATRIX_RESULTS,
            DiagnosticsPage.SCAN_RESULTS ->
                page = DiagnosticsPage.TOOLS

            else ->
                page = DiagnosticsPage.OVERVIEW
        }
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
                        sensorInventory = sensorInventory,
                        commandCatalog = commandCatalog,
                        safeTests =
                            safeTestRunner
                                ?.specs()
                                .orEmpty(),
                        onPlatform = {
                            page = DiagnosticsPage.PLATFORM
                        },
                        onSensors = {
                            page = DiagnosticsPage.SENSORS
                        },
                        onBluetooth = {
                            page = DiagnosticsPage.BLUETOOTH
                        },
                        onCommands = {
                            page = DiagnosticsPage.COMMANDS
                        },
                        onTools = {
                            page = DiagnosticsPage.TOOLS
                        },
                        onLogs = {
                            page = DiagnosticsPage.LOGS
                        },
                        onBuild = {
                            page = DiagnosticsPage.BUILD
                        },
                        onPower = {
                            page = DiagnosticsPage.POWER
                        },
                    )

                DiagnosticsPage.PLATFORM ->
                    PlatformScreen(
                        platform = currentPlatform,
                        onBack = {
                            page = DiagnosticsPage.OVERVIEW
                        },
                    )

                DiagnosticsPage.SENSORS ->
                    SensorsScreen(
                        capabilities = currentPlatform.capabilities,
                        inventory = sensorInventory,
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

                DiagnosticsPage.COMMANDS ->
                    CommandsScreen(
                        commands = commandCatalog,
                        dispatcher = commandDispatcher,
                        onCommand = { command ->
                            selectedCommand = command
                            page = DiagnosticsPage.COMMAND_DETAIL
                        },
                        onBack = {
                            page = DiagnosticsPage.OVERVIEW
                        },
                    )

                DiagnosticsPage.COMMAND_DETAIL ->
                    selectedCommand?.let { command ->
                        CommandDetailScreen(
                            command = command,
                            onBack = {
                                selectedCommand = null
                                page = DiagnosticsPage.COMMANDS
                            },
                        )
                    } ?: run { page = DiagnosticsPage.COMMANDS }

                DiagnosticsPage.TOOLS ->
                    ToolsScreen(
                        runner = safeTestRunner,
                        sensorMatrixExperiment = sensorMatrixExperiment,
                        developerRemoteAccessPolicy =
                            developerRemoteAccessPolicy,
                        commandDispatcher = commandDispatcher,
                        healthServicesRefresh = healthServicesRefresh,
                        lastSafeResult = lastSafeResult,
                        onSafeResult = {
                            lastSafeResult = it
                            page = DiagnosticsPage.TEST_RESULT
                        },
                        onSafeResultDetails = {
                            page = DiagnosticsPage.TEST_RESULT
                        },
                        matrixResult = matrixResult,
                        matrixRunning = matrixRunning,
                        onMatrixResult = {
                            matrixResult = it
                        },
                        onMatrixRunningChange = {
                            matrixRunning = it
                        },
                        onMatrixDetails = {
                            page = DiagnosticsPage.MATRIX_RESULTS
                        },
                        scanRunId = scanRunId,
                        scanResult = scanResult,
                        scanSnapshot = sensorProbeStoreSnapshot,
                        onScanRunId = {
                            scanRunId = it
                        },
                        onScanResult = {
                            scanResult = it
                        },
                        onScanDetails = {
                            page = DiagnosticsPage.SCAN_RESULTS
                        },
                        onBack = {
                            page = DiagnosticsPage.OVERVIEW
                        },
                    )

                DiagnosticsPage.TEST_RESULT ->
                    lastSafeResult?.let { result ->
                        SafeTestResultScreen(
                            result = result,
                            onBack = {
                                page = DiagnosticsPage.TOOLS
                            },
                        )
                    } ?: run { page = DiagnosticsPage.TOOLS }

                DiagnosticsPage.MATRIX_RESULTS ->
                    MatrixResultsScreen(
                        result = matrixResult,
                        onEntry = {
                            selectedMatrixEntry = it
                            page = DiagnosticsPage.MATRIX_ENTRY_DETAIL
                        },
                        onBack = {
                            page = DiagnosticsPage.TOOLS
                        },
                    )

                DiagnosticsPage.MATRIX_ENTRY_DETAIL ->
                    selectedMatrixEntry?.let { entry ->
                        MatrixEntryDetailScreen(
                            entry = entry,
                            onBack = {
                                selectedMatrixEntry = null
                                page = DiagnosticsPage.MATRIX_RESULTS
                            },
                        )
                    } ?: run { page = DiagnosticsPage.MATRIX_RESULTS }

                DiagnosticsPage.SCAN_RESULTS ->
                    ScanResultsScreen(
                        runId = scanRunId,
                        commandResult = scanResult,
                        snapshot = sensorProbeStoreSnapshot,
                        onRecord = {
                            selectedScanRecord = it
                            page = DiagnosticsPage.SCAN_RECORD_DETAIL
                        },
                        onBack = {
                            page = DiagnosticsPage.TOOLS
                        },
                    )

                DiagnosticsPage.SCAN_RECORD_DETAIL ->
                    selectedScanRecord?.let { record ->
                        ScanRecordDetailScreen(
                            record = record,
                            onBack = {
                                selectedScanRecord = null
                                page = DiagnosticsPage.SCAN_RESULTS
                            },
                        )
                    } ?: run { page = DiagnosticsPage.SCAN_RESULTS }

                DiagnosticsPage.LOGS ->
                    LogsScreen(
                        platform = currentPlatform,
                        probe = probe,
                        onBack = {
                            page = DiagnosticsPage.OVERVIEW
                        },
                    )

                DiagnosticsPage.BUILD ->
                    BuildScreen(
                        onBack = {
                            page = DiagnosticsPage.OVERVIEW
                        },
                    )

                DiagnosticsPage.POWER ->
                    PowerScreen(
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
    sensorInventory: SensorInventory?,
    commandCatalog: List<DieselCommandSpec>,
    safeTests: List<SafePlatformTestSpec>,
    onPlatform: () -> Unit,
    onSensors: () -> Unit,
    onBluetooth: () -> Unit,
    onCommands: () -> Unit,
    onTools: () -> Unit,
    onLogs: () -> Unit,
    onBuild: () -> Unit,
    onPower: () -> Unit,
) {
    val diagnostics by
        platform.diagnostics.state.collectAsStateWithLifecycle()

    val battery by
        platform.battery.state.collectAsStateWithLifecycle()

    val providers = diagnostics.providers

    val sensorSnapshot =
        remember(sensorInventory) {
            runCatching {
                sensorInventory
                    ?.snapshot()
                    .orEmpty()
            }
                .getOrDefault(
                    emptyList(),
                )
        }

    val androidStringTypeSensors =
        sensorSnapshot.count {
            it.stringType.startsWith(
                "android.sensor.",
            )
        }

    val otherStringTypeSensors =
        sensorSnapshot.size -
            androidStringTypeSensors

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
            title = "POWER",
            primary = "Usage & sleep controls",
            secondary = "Battery readings · app policy",
            healthy = true,
            onClick = onPower,
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
            title = "SENSORS",
            primary =
                if (sensorInventory == null) {
                    "Inventory unavailable"
                } else {
                    "${sensorSnapshot.size} process-visible"
                },
            secondary =
                if (sensorInventory == null) {
                    "Service sensor census not attached"
                } else {
                    "$androidStringTypeSensors android.sensor.* · " +
                        "$otherStringTypeSensors other"
                },
            healthy =
                sensorInventory != null &&
                    sensorSnapshot.isNotEmpty(),
            onClick = onSensors,
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
            title = "COMMANDS",
            primary =
                "${commandCatalog.size} supported",
            secondary =
                commandCatalog
                    .joinToString(", ") {
                        it.name
                    }
                    .ifBlank {
                        "No commands registered"
                    },
            healthy = commandCatalog.isNotEmpty(),
            onClick = onCommands,
        )

        DiagnosticCard(
            title = "TOOLS",
            primary =
                "${safeTests.size} bounded tests",
            secondary =
                safeTests
                    .joinToString(", ") {
                        it.name
                    }
                    .ifBlank {
                        "No safe tests registered"
                    },
            healthy = safeTests.isNotEmpty(),
            onClick = onTools,
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
            onClick = onBuild,
        )
    }
}

@Composable
private fun BuildScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    val buildInfo =
        remember(context) {
            DeveloperBuildInfoSource.snapshot(
                context,
            )
        }

    DeveloperPage(
        title = "Build details",
        subtitle =
            "${buildInfo.versionName} · code ${buildInfo.versionCode}",
        onBack = onBack,
    ) {
        DiagnosticCard(
            title = "VERSION",
            primary = buildInfo.versionName,
            secondary =
                "versionCode ${buildInfo.versionCode} · " +
                    buildInfo.buildType,
            healthy = true,
        )

        DiagnosticCard(
            title = "COMMIT",
            primary =
                buildInfo.gitShaShort
                    ?: "Unknown",
            secondary =
                buildInfo.gitSha
                    ?: "No source revision embedded",
            healthy =
                buildInfo.gitSha != null,
        )

        DiagnosticCard(
            title = "BUILT",
            primary =
                buildInfo.buildTimestampUtc,
            secondary =
                "Embedded at build time",
            healthy = true,
        )

        DiagnosticCard(
            title = "CI RUN",
            primary =
                buildInfo.ciRunId
                    ?: "Not a CI build",
            secondary =
                if (buildInfo.ciRunId != null) {
                    "GitHub Actions run"
                } else {
                    "No CI run embedded"
                },
            healthy =
                buildInfo.ciRunId != null,
        )

        DiagnosticCard(
            title = "INSTALLATION",
            primary =
                buildInfo.lastUpdateTimeMs
                    ?.let {
                        "Updated ${formatDateTime(it)}"
                    }
                    ?: "Unavailable",
            secondary =
                buildInfo.firstInstallTimeMs
                    ?.let {
                        "First installed ${formatDateTime(it)}"
                    },
            healthy =
                buildInfo.lastUpdateTimeMs != null,
        )
    }
}

@Composable
private fun SensorsScreen(
    capabilities: org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry,
    inventory: SensorInventory?,
    onBack: () -> Unit,
) {
    val snapshotResult =
        remember(inventory) {
            runCatching {
                inventory
                    ?.snapshot()
                    .orEmpty()
            }
        }

    val sensors =
        snapshotResult
            .getOrDefault(
                emptyList(),
            )

    DeveloperPage(
        title = "Sensors",
        subtitle =
            if (inventory == null) {
                "Sensor inventory unavailable"
            } else {
                "${sensors.size} process-visible sensors"
            },
        onBack = onBack,
    ) {
        SensorReadPanel(capabilities)

        when {
            inventory == null ->
                DiagnosticCard(
                    title = "SENSOR INVENTORY",
                    primary = "Unavailable",
                    secondary =
                        "Diesel service runtime did not attach an inventory",
                    healthy = false,
                )

            snapshotResult.isFailure ->
                DiagnosticCard(
                    title = "SENSOR INVENTORY",
                    primary = "Snapshot failed",
                    secondary =
                        snapshotResult
                            .exceptionOrNull()
                            ?.javaClass
                            ?.simpleName,
                    healthy = false,
                )

            sensors.isEmpty() ->
                DiagnosticCard(
                    title = "SENSOR INVENTORY",
                    primary = "No sensors visible",
                    secondary =
                        "SensorManager returned an empty TYPE_ALL list",
                    healthy = false,
                )

            else ->
                sensors.forEachIndexed {
                        index,
                        sensor,
                    ->
                    SensorInventoryCard(
                        index = index,
                        sensor = sensor,
                    )
                }
        }
    }
}

@Composable
private fun SensorReadPanel(
    capabilities: org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var running by remember { mutableStateOf<String?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }
    val targets = SensorReadCoordinator.standardTargets

    SectionLabel("PUBLIC SENSOR READ")
    Text(
        text = resultText ?: "Select a logical target; reads use the local platform registry.",
        style = MaterialTheme.typography.bodySmall,
        color = SecondaryText,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
    targets.forEach { target ->
        NavigationChip(
            label = if (running == target) "READING ${target.uppercase()}…" else "READ ${target.uppercase()}",
            onClick = {
                if (running != null) return@NavigationChip
                running = target
                resultText = null
                scope.launch {
                    val result = SensorReadCoordinator.read(
                        registry = capabilities,
                        target = target,
                        options = SensorReadOptions(),
                    )
                    resultText = sensorReadResultSummary(target, result)
                    running = null
                }
            },
        )
    }
}

private fun sensorReadResultSummary(target: String, result: SensorReadResult): String =
    when (result) {
        is SensorReadResult.Event ->
            "$target: event ${result.reading.values.take(4).joinToString()}"
        SensorReadResult.Unavailable -> "$target: unavailable"
        is SensorReadResult.PermissionDenied ->
            "$target: permission denied ${result.requiredPermission ?: "unknown"}"
        SensorReadResult.Timeout -> "$target: timeout"
        is SensorReadResult.RegistrationRejected ->
            "$target: registration rejected ${result.reason ?: "unknown"}"
    }

private fun sensorReadSummary(target: String, result: DieselCommandResult): String {
    val data = commandMetadataValue(DieselValue.ObjectValue(result.data))
    return "$target: ${result.status.wireName} $data"
}


@Composable
private fun SensorInventoryCard(
    index: Int,
    sensor: SensorInventoryEntry,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        18.dp,
                    ),
                )
                .background(
                    CardBackground,
                )
                .padding(
                    horizontal = 14.dp,
                    vertical = 11.dp,
                ),
        verticalArrangement =
            Arrangement.spacedBy(
                3.dp,
            ),
    ) {
        Text(
            text =
                "#$index · " +
                    sensor.logicalId.uppercase(),
            style =
                MaterialTheme.typography
                    .labelSmall,
            color = AccentText,
            maxLines = 2,
            overflow =
                TextOverflow.Ellipsis,
        )

        Text(
            text =
                sensor.name
                    .ifBlank {
                        "<unnamed sensor>"
                    },
            style =
                MaterialTheme.typography
                    .titleSmall,
            color = PrimaryText,
            maxLines = 3,
            overflow =
                TextOverflow.Ellipsis,
        )

        Text(
            text =
                sensor.stringType
                    .ifBlank {
                        "<no string type>"
                    },
            style =
                MaterialTheme.typography
                    .bodySmall,
            color = SecondaryText,
            maxLines = 3,
            overflow =
                TextOverflow.Ellipsis,
        )

        SensorDetailLine(
            "Android id ${sensor.androidId} · " +
                "type ${sensor.androidType} · " +
                "version ${sensor.version}",
        )

        SensorDetailLine(
            "vendor " +
                sensor.vendor
                    .ifBlank {
                        "<unknown>"
                    },
        )

        SensorDetailLine(
            "range ${sensor.maxRange} · " +
                "resolution ${sensor.resolution}",
        )

        SensorDetailLine(
            "power ${sensor.powerMilliAmps} mA",
        )

        SensorDetailLine(
            "delay ${sensor.minDelayUs}.." +
                "${sensor.maxDelayUs} µs",
        )

        SensorDetailLine(
            "FIFO ${sensor.fifoReservedEventCount}/" +
                "${sensor.fifoMaxEventCount}",
        )

        SensorDetailLine(
            "reporting mode ${sensor.reportingMode} · " +
                if (sensor.wakeUp) {
                    "wake-up"
                } else {
                    "non-wake-up"
                },
        )
    }
}

@Composable
private fun SensorDetailLine(
    text: String,
) {
    Text(
        text = text,
        style =
            MaterialTheme.typography
                .labelSmall,
        color = SecondaryText,
        maxLines = 3,
        overflow =
            TextOverflow.Ellipsis,
    )
}

@Composable
private fun CommandsScreen(
    commands: List<DieselCommandSpec>,
    dispatcher: (suspend (DieselRequest) -> DieselCommandResult)?,
    onCommand: (DieselCommandSpec) -> Unit,
    onBack: () -> Unit,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var lastResult by remember { mutableStateOf<String?>(null) }

    DeveloperPage(
        title = "Commands",
        subtitle = "${commands.size} registered",
        onBack = onBack,
    ) {
        if (commands.isEmpty()) {
            DiagnosticCard(
                title = "COMMANDS",
                primary = "None registered",
                secondary = null,
                healthy = false,
            )
        } else {
            commands.forEach { command ->
                DiagnosticCard(
                    title = command.name.uppercase(),
                    primary = command.summary,
                    secondary = commandMetadataSummary(command.metadata),
                    healthy = true,
                    onClick = { onCommand(command) },
                )
                if (command.name in setOf("diagnostics", "commands", "debug.build.info", "sensor.matrix")) {
                    dispatcher?.let { activeDispatcher ->
                        NavigationChip(
                            label = "RUN ${command.name.uppercase()}",
                            onClick = {
                                scope.launch {
                                    val result = activeDispatcher(
                                        DieselRequest(
                                            requestId = "local-ui",
                                            command = command.name,
                                            args = if (command.name == "sensor.matrix") {
                                                mapOf("timeoutMs" to DieselValue.Integer(5000))
                                            } else {
                                                emptyMap()
                                            },
                                        ),
                                    )
                                    lastResult = "${command.name}: ${result.status.wireName} " +
                                        commandMetadataValue(DieselValue.ObjectValue(result.data))
                                }
                            },
                        )
                    }
                }
            }
        }
        lastResult?.let {
            DiagnosticCard("LAST COMMAND RESULT", it, null, healthy = true)
        }
    }
}

@Composable
private fun CommandDetailScreen(
    command: DieselCommandSpec,
    onBack: () -> Unit,
) {
    DeveloperPage(
        title = command.name,
        subtitle = "Command API details",
        onBack = onBack,
    ) {
        DiagnosticCard("SUMMARY", command.summary, null, healthy = true)
        if (command.metadata.isEmpty()) {
            DiagnosticCard("METADATA", "None declared", null, healthy = false)
        } else {
            command.metadata.entries.forEach { (key, value) ->
                DiagnosticCard(
                    title = key.uppercase(),
                    primary = commandMetadataValue(value),
                    secondary = "Structured command metadata",
                    healthy = true,
                )
            }
        }
        DiagnosticCard(
            title = "REQUEST SHAPE",
            primary = "cmd=\"${command.name}\"",
            secondary = "Use the target name and arguments documented above",
            healthy = true,
        )
    }
}

private fun commandMetadataSummary(metadata: Map<String, DieselValue>): String {
    if (metadata.isEmpty()) return "No metadata declared"
    val preferred = listOf("domain", "effect", "target", "routing", "arguments")
    val ordered = preferred.mapNotNull { key -> metadata[key]?.let { key to it } } +
        metadata.filterKeys { it !in preferred }.toList()
    return ordered.joinToString(" · ") { (key, value) ->
        "$key=${commandMetadataValue(value)}"
    }.take(180)
}

private fun commandMetadataValue(value: DieselValue): String =
    when (value) {
        DieselValue.Null -> "null"
        is DieselValue.Text -> value.value.replace('_', ' ')
        is DieselValue.Integer -> value.value.toString()
        is DieselValue.Decimal -> value.value.toString()
        is DieselValue.Flag -> value.value.toString()
        is DieselValue.ObjectValue -> value.value.entries.joinToString(",", "{", "}") { (key, child) ->
            "$key:${commandMetadataValue(child)}"
        }
        is DieselValue.ListValue -> value.value.joinToString(",", "[", "]") { commandMetadataValue(it) }
    }

@Composable
private fun RemoteDeveloperAccessControl(
    policy: DeveloperRemoteAccessPolicy?,
) {
    if (policy == null) {
        DiagnosticCard(
            title = "REMOTE DEVELOPER ACCESS",
            primary = "Unavailable",
            secondary =
                "Developer remote access policy is not attached",
            healthy = false,
        )

        return
    }

    val enabled by
        policy.enabled
            .collectAsStateWithLifecycle()

    DiagnosticCard(
        title = "REMOTE DEVELOPER ACCESS",
        primary =
            if (enabled) {
                "Enabled"
            } else {
                "Disabled"
            },
        secondary =
            if (enabled) {
                "Remote debug commands authorized · tap to disable"
            } else {
                "Local authorization required · tap to enable"
            },
        healthy = !enabled,
        onClick = {
            policy.setEnabled(
                !enabled,
            )
        },
    )
}

@Composable
private fun ToolsScreen(
    runner: SafePlatformTestRunner?,
    sensorMatrixExperiment: SensorMatrixExperiment?,
    developerRemoteAccessPolicy: DeveloperRemoteAccessPolicy?,
    commandDispatcher: (suspend (DieselRequest) -> DieselCommandResult)?,
    healthServicesRefresh: (suspend () -> Unit)?,
    lastSafeResult: SafePlatformTestResult?,
    onSafeResult: (SafePlatformTestResult) -> Unit,
    onSafeResultDetails: () -> Unit,
    matrixResult: SensorMatrixExperimentResult?,
    matrixRunning: Boolean,
    onMatrixResult: (SensorMatrixExperimentResult) -> Unit,
    onMatrixRunningChange: (Boolean) -> Unit,
    onMatrixDetails: () -> Unit,
    scanRunId: String?,
    scanResult: DieselCommandResult?,
    scanSnapshot: SensorProbeStoreSnapshot?,
    onScanRunId: (String?) -> Unit,
    onScanResult: (DieselCommandResult) -> Unit,
    onScanDetails: () -> Unit,
    onBack: () -> Unit,
) {
    val tests = runner?.specs().orEmpty()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scanState =
        (scanResult?.data?.get("state") as? DieselValue.Text)?.value
    val scanCompleted =
        (scanResult?.data?.get("completedRoutes") as? DieselValue.Integer)?.value
    val scanTotal =
        (scanResult?.data?.get("totalRoutes") as? DieselValue.Integer)?.value
    val scanRecordCount =
        scanSnapshot
            ?.records
            ?.count { it.runId == scanRunId }
            ?: 0

    DeveloperPage(
        title = "Tools",
        subtitle = "${tests.size} bounded safe tests",
        onBack = onBack,
    ) {
        RemoteDeveloperAccessControl(
            policy = developerRemoteAccessPolicy,
        )

        val context = LocalContext.current
        val healthPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            scope.launch { healthServicesRefresh?.invoke() }
        }
        healthServicesRefresh?.let { refresh ->
            val granted =
                context.checkSelfPermission(Manifest.permission.BODY_SENSORS) ==
                    PackageManager.PERMISSION_GRANTED
            DiagnosticCard(
                title = "HEALTH SERVICES",
                primary =
                    if (granted) {
                        "Heart-rate permission granted"
                    } else {
                        "Heart-rate permission required"
                    },
                secondary = "Enables the optional Health Services provider",
                healthy = granted,
            )
            NavigationChip(
                label =
                    if (granted) {
                        "REFRESH HEALTH PROVIDER"
                    } else {
                        "GRANT HEART-RATE PERMISSION"
                    },
                onClick = {
                    if (granted) {
                        scope.launch { refresh() }
                    } else {
                        healthPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
                    }
                },
            )
        }

        commandDispatcher?.let { dispatch ->
            DiagnosticCard(
                title = "WHOLE-WATCH SENSOR SCAN",
                primary = scanState ?: "Ready",
                secondary =
                    when {
                        scanCompleted != null && scanTotal != null ->
                            "${scanCompleted} / ${scanTotal} routes · ${scanRecordCount} records"

                        else ->
                            "2 s per route · 60 s maximum"
                    },
                healthy = scanState != "time_budget_exhausted",
                onClick =
                    if (scanRunId == null) {
                        null
                    } else {
                        onScanDetails
                    },
            )
            NavigationChip(
                label =
                    if (scanState in setOf("running", "cancelling")) {
                        "VIEW LIVE SENSOR SCAN"
                    } else {
                        "START SENSOR SCAN"
                    },
                onClick = {
                    if (
                        scanState in setOf("running", "cancelling") &&
                            scanRunId != null
                    ) {
                        onScanDetails()
                    } else {
                        scope.launch {
                            val result = dispatch(
                                DieselRequest(
                                    requestId = "local-scan-start",
                                    command = "debug.sensor.scan.start",
                                ),
                            )
                            onScanResult(result)
                            val runId =
                                (result.data["runId"] as? DieselValue.Text)?.value
                            onScanRunId(runId)
                            onScanDetails()
                        }
                    }
                },
            )
            val activeScanRunId = scanRunId
            if (scanState == "running" && activeScanRunId != null) {
                NavigationChip(
                    label = "CANCEL SENSOR SCAN",
                    onClick = {
                        scope.launch {
                            onScanResult(
                                dispatch(
                                    DieselRequest(
                                        requestId = "local-scan-cancel",
                                        command = "debug.sensor.scan.cancel",
                                        args = mapOf(
                                            "runId" to DieselValue.Text(activeScanRunId),
                                        ),
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        sensorMatrixExperiment?.let { experiment ->
            DiagnosticCard(
                title = "SENSOR MATRIX",
                primary =
                    if (matrixRunning) {
                        "Running..."
                    } else {
                        "8 logical targets"
                    },
                secondary =
                    matrixResult?.let {
                        "${it.eventCount} events · ${it.unavailableCount} unavailable · ${it.nonEventCount} other"
                    } ?: "One bounded sample per target",
                healthy = !matrixRunning,
                onClick =
                    if (matrixResult == null) {
                        null
                    } else {
                        onMatrixDetails
                    },
            )
            NavigationChip(
                label =
                    if (matrixRunning) {
                        "RUNNING SENSOR MATRIX..."
                    } else {
                        "RUN SENSOR MATRIX"
                    },
                onClick = {
                    if (!matrixRunning) {
                        onMatrixRunningChange(true)
                        scope.launch {
                            try {
                                onMatrixResult(experiment.run())
                                onMatrixDetails()
                            } finally {
                                onMatrixRunningChange(false)
                            }
                        }
                    }
                },
            )
        }

        lastSafeResult?.let { result ->
            DiagnosticCard(
                title = "LAST TEST RESULT",
                primary = safeTestResultTitle(result),
                secondary = safeTestResultSummary(result),
                healthy = result is SafePlatformTestResult.Success,
                onClick = onSafeResultDetails,
            )
        }

        when {
            runner == null ->
                DiagnosticCard(
                    title = "SAFE TESTS",
                    primary = "Runner unavailable",
                    secondary = "Diesel service runtime is not attached",
                    healthy = false,
                )

            tests.isEmpty() ->
                DiagnosticCard(
                    title = "SAFE TESTS",
                    primary = "None registered",
                    secondary = null,
                    healthy = false,
                )

            else ->
                tests.forEach { test ->
                    DiagnosticCard(
                        title = test.name.uppercase(),
                        primary = test.summary,
                        secondary = "Tap to run and inspect the complete result",
                        healthy = true,
                        onClick = {
                            onSafeResult(runner.run(test.name))
                        },
                    )
                }
        }
    }
}

private enum class ResultFilter {
    ALL,
    ISSUES,
    EVENTS,
}

private fun safeTestResultTitle(
    result: SafePlatformTestResult,
): String =
    when (result) {
        is SafePlatformTestResult.Success -> "SUCCESS"
        is SafePlatformTestResult.Unavailable -> "UNAVAILABLE"
        is SafePlatformTestResult.RateLimited -> "RATE LIMITED"
        is SafePlatformTestResult.Failed -> "FAILED"
        is SafePlatformTestResult.UnknownTarget -> "UNKNOWN TARGET"
    }

private fun safeTestResultSummary(
    result: SafePlatformTestResult,
): String =
    when (result) {
        is SafePlatformTestResult.Success ->
            result.target + " · " + result.providerId

        is SafePlatformTestResult.Unavailable ->
            result.target + " · no active provider"

        is SafePlatformTestResult.RateLimited ->
            result.target + " · retry in " + result.retryAfterMs + " ms"

        is SafePlatformTestResult.Failed ->
            result.target + " · " + result.message

        is SafePlatformTestResult.UnknownTarget ->
            result.target ?: "<missing>"
    }

@Composable
private fun SafeTestResultScreen(
    result: SafePlatformTestResult,
    onBack: () -> Unit,
) {
    DeveloperPage(
        title = "Test result",
        subtitle = safeTestResultTitle(result),
        onBack = onBack,
    ) {
        DiagnosticCard(
            title = "OUTCOME",
            primary = safeTestResultTitle(result),
            secondary = safeTestResultSummary(result),
            healthy = result is SafePlatformTestResult.Success,
        )
        when (result) {
            is SafePlatformTestResult.Success ->
                DetailText("Provider: " + result.providerId)

            is SafePlatformTestResult.Unavailable ->
                DetailText("No active provider was available for this test.")

            is SafePlatformTestResult.RateLimited ->
                DetailText("Retry after " + result.retryAfterMs + " ms.")

            is SafePlatformTestResult.Failed ->
                DetailText("Failure detail: " + result.message)

            is SafePlatformTestResult.UnknownTarget ->
                DetailText("The selected test target is not registered.")
        }
    }
}

@Composable
private fun MatrixResultsScreen(
    result: SensorMatrixExperimentResult?,
    onEntry: (Pair<String, SensorReadResult>) -> Unit,
    onBack: () -> Unit,
) {
    var filter by remember { mutableStateOf(ResultFilter.ALL) }
    val entries =
        result
            ?.entries
            .orEmpty()
            .filter { entry ->
                when (filter) {
                    ResultFilter.ALL -> true
                    ResultFilter.ISSUES -> entry.second !is SensorReadResult.Event
                    ResultFilter.EVENTS -> entry.second is SensorReadResult.Event
                }
            }

    DeveloperPage(
        title = "Sensor matrix",
        subtitle =
            result?.let {
                it.eventCount.toString() + " events · " +
                    it.unavailableCount + " unavailable · " +
                    it.nonEventCount + " other"
            } ?: "No matrix result yet",
        onBack = onBack,
    ) {
        ResultFilterRow(
            filter = filter,
            onFilter = { filter = it },
        )
        if (result == null) {
            DiagnosticCard(
                title = "MATRIX",
                primary = "No completed matrix",
                secondary = "Run the matrix from Tools to inspect its eight logical targets.",
                healthy = false,
            )
        } else if (entries.isEmpty()) {
            DiagnosticCard(
                title = "MATRIX",
                primary = "No " + filter.name.lowercase() + " results",
                secondary = "Choose another result filter.",
                healthy = true,
            )
        } else {
            entries.forEach { entry ->
                val target = entry.first
                val sensorResult = entry.second
                DiagnosticCard(
                    title = target.uppercase(),
                    primary = matrixResultTitle(sensorResult),
                    secondary = matrixResultSummary(sensorResult),
                    healthy = sensorResult is SensorReadResult.Event,
                    onClick = { onEntry(entry) },
                )
            }
        }
    }
}

@Composable
private fun MatrixEntryDetailScreen(
    entry: Pair<String, SensorReadResult>,
    onBack: () -> Unit,
) {
    val target = entry.first
    val result = entry.second
    DeveloperPage(
        title = target,
        subtitle = matrixResultTitle(result),
        onBack = onBack,
    ) {
        DiagnosticCard(
            title = "OUTCOME",
            primary = matrixResultTitle(result),
            secondary = matrixResultSummary(result),
            healthy = result is SensorReadResult.Event,
        )
        when (result) {
            is SensorReadResult.Event -> {
                DetailText("Capability: " + result.reading.capabilityId.value)
                DetailText("Provider: " + result.reading.providerId)
                DetailText("Elapsed: " + result.reading.elapsedMs + " ms")
                DetailText("Sensor timestamp: " + result.reading.timestampNanos + " ns")
                DetailText(
                    "Accuracy: " +
                        (result.reading.accuracy?.toString() ?: "unknown"),
                )
                DetailText(
                    "Raw values: " +
                        result.reading.values.joinToString(),
                )
            }

            SensorReadResult.Unavailable ->
                DetailText("No active provider exposed this logical capability.")

            is SensorReadResult.PermissionDenied ->
                DetailText(
                    "Required permission: " +
                        (result.requiredPermission ?: "not reported by Android"),
                )

            SensorReadResult.Timeout ->
                DetailText("No sample arrived before the configured timeout.")

            is SensorReadResult.RegistrationRejected ->
                DetailText(
                    "Registration reason: " +
                        (result.reason ?: "not reported by Android"),
                )
        }
    }
}

@Composable
private fun ScanResultsScreen(
    runId: String?,
    commandResult: DieselCommandResult?,
    snapshot: SensorProbeStoreSnapshot?,
    onRecord: (SensorProbeRecord) -> Unit,
    onBack: () -> Unit,
) {
    var filter by remember { mutableStateOf(ResultFilter.ALL) }
    val activeRunId = runId ?: snapshot?.latestSummary?.runId
    val summary =
        snapshot
            ?.latestSummary
            ?.takeIf { it.runId == activeRunId }
    val records =
        snapshot
            ?.records
            .orEmpty()
            .asReversed()
            .filter { it.runId == activeRunId }
            .filter { record ->
                when (filter) {
                    ResultFilter.ALL -> true
                    ResultFilter.ISSUES -> record.outcome != "event"
                    ResultFilter.EVENTS -> record.outcome == "event"
                }
            }

    DeveloperPage(
        title = "Sensor scan",
        subtitle = scanResultSubtitle(summary, commandResult),
        onBack = onBack,
    ) {
        commandResult
            ?.takeIf { it.status != DieselResponseStatus.OK }
            ?.let { result ->
                DiagnosticCard(
                    title = "SCAN COMMAND",
                    primary = result.status.wireName.uppercase(),
                    secondary = scanCommandReason(result),
                    healthy = false,
                )
            }
        summary?.let {
            DiagnosticCard(
                title = "SCAN SUMMARY",
                primary = scanTerminalTitle(it.terminalReason),
                secondary =
                    it.completedRoutes.toString() + " / " +
                        it.totalRoutes + " routes · " +
                        scanElapsedMs(it).toString() + " ms",
                healthy = it.terminalReason == SensorScanTerminalReason.FINISHED,
            )
        }
        ResultFilterRow(
            filter = filter,
            onFilter = { filter = it },
        )
        if (activeRunId == null) {
            DiagnosticCard(
                title = "SCAN RECORDS",
                primary = "No scan selected",
                secondary = "Start a whole-watch sensor scan from Tools.",
                healthy = false,
            )
        } else if (records.isEmpty()) {
            DiagnosticCard(
                title = "SCAN RECORDS",
                primary = "No " + filter.name.lowercase() + " records",
                secondary = "Records appear here while the scan runs.",
                healthy = filter != ResultFilter.ALL,
            )
        } else {
            DetailText("Newest attempted route first.")
            records.forEach { record ->
                DiagnosticCard(
                    title =
                        "#" + (record.index + 1) + " · " +
                            record.route.descriptor.logicalId.value.uppercase(),
                    primary = record.outcome.replace('_', ' ').uppercase(),
                    secondary = scanRecordSummary(record),
                    healthy = record.outcome == "event",
                    onClick = { onRecord(record) },
                )
            }
        }
    }
}

@Composable
private fun ScanRecordDetailScreen(
    record: SensorProbeRecord,
    onBack: () -> Unit,
) {
    val route = record.route
    val sensor = route.inventory
    DeveloperPage(
        title = "Route #" + (record.index + 1),
        subtitle = record.outcome.replace('_', ' ').uppercase(),
        onBack = onBack,
    ) {
        DiagnosticCard(
            title = sensor.logicalId.uppercase(),
            primary = record.outcome.replace('_', ' ').uppercase(),
            secondary = scanRecordSummary(record),
            healthy = record.outcome == "event",
        )
        DetailText("Route ID: " + route.descriptor.routeId.value)
        DetailText("Provider: " + route.descriptor.providerId)
        DetailText("Android type/id: " + sensor.androidType + " / " + sensor.androidId)
        DetailText("String type: " + sensor.stringType)
        DetailText("Name: " + sensor.name)
        DetailText("Vendor: " + sensor.vendor)
        DetailText(
            "Reporting mode: " + sensor.reportingMode +
                if (sensor.wakeUp) " · wake-up" else " · non-wake-up",
        )
        DetailText(
            "Registration: " +
                (record.registrationKind?.name?.lowercase() ?: "not registered"),
        )
        DetailText("Elapsed: " + record.elapsedMs + " ms")
        record.requiredPermission?.let {
            DetailText("Required permission: " + it)
        }
        record.reason?.let {
            DetailText("Reason: " + it)
        }
        record.event?.let { event ->
            DetailText("Sensor timestamp: " + event.timestampNanos + " ns")
            DetailText(
                "Accuracy: " +
                    (event.accuracy?.toString() ?: "unknown"),
            )
            DetailText("Raw values: " + event.values.joinToString())
        }
    }
}

@Composable
private fun ResultFilterRow(
    filter: ResultFilter,
    onFilter: (ResultFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResultFilterChip(
            label = "ALL",
            selected = filter == ResultFilter.ALL,
            onClick = { onFilter(ResultFilter.ALL) },
            modifier = Modifier.weight(1f),
        )
        ResultFilterChip(
            label = "ISSUES",
            selected = filter == ResultFilter.ISSUES,
            onClick = { onFilter(ResultFilter.ISSUES) },
            modifier = Modifier.weight(1f),
        )
        ResultFilterChip(
            label = "EVENTS",
            selected = filter == ResultFilter.EVENTS,
            onClick = { onFilter(ResultFilter.EVENTS) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ResultFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) AccentText else SecondaryText,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier =
            modifier
                .clip(RoundedCornerShape(15.dp))
                .background(
                    if (selected) {
                        AccentText.copy(alpha = 0.14f)
                    } else {
                        ChipBackground
                    },
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

@Composable
private fun DetailText(
    text: String,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = SecondaryText,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun matrixResultTitle(
    result: SensorReadResult,
): String =
    when (result) {
        is SensorReadResult.Event -> "EVENT"
        SensorReadResult.Unavailable -> "UNAVAILABLE"
        is SensorReadResult.PermissionDenied -> "PERMISSION DENIED"
        SensorReadResult.Timeout -> "TIMEOUT"
        is SensorReadResult.RegistrationRejected -> "REGISTRATION REJECTED"
    }

private fun matrixResultSummary(
    result: SensorReadResult,
): String =
    when (result) {
        is SensorReadResult.Event ->
            result.reading.providerId + " · " +
                result.reading.elapsedMs + " ms · " +
                result.reading.values.take(4).joinToString()

        SensorReadResult.Unavailable ->
            "No active provider"

        is SensorReadResult.PermissionDenied ->
            result.requiredPermission ?: "Permission not reported by Android"

        SensorReadResult.Timeout ->
            "No sample before timeout"

        is SensorReadResult.RegistrationRejected ->
            result.reason ?: "Android did not report a reason"
    }

private fun scanCommandReason(
    result: DieselCommandResult,
): String =
    (result.data["reason"] as? DieselValue.Text)?.value
        ?: "The developer command did not return a reason."

private fun scanRecordSummary(
    record: SensorProbeRecord,
): String {
    val detail =
        record.requiredPermission
            ?: record.reason
            ?: record.event?.values?.take(4)?.joinToString()
            ?: record.route.inventory.name
    return record.elapsedMs.toString() + " ms · " + detail
}

private fun scanTerminalTitle(
    reason: SensorScanTerminalReason?,
): String =
    when (reason) {
        null -> "RUNNING"
        SensorScanTerminalReason.FINISHED -> "FINISHED"
        SensorScanTerminalReason.CANCELLED -> "CANCELLED"
        SensorScanTerminalReason.TIME_BUDGET_EXHAUSTED -> "TIME BUDGET EXHAUSTED"
    }

private fun scanElapsedMs(
    summary: org.aaustralian.dieselbridge.debug.SensorScanSummary,
): Long =
    (summary.finishedAtMs ?: System.currentTimeMillis()) - summary.startedAtMs

private fun scanResultSubtitle(
    summary: org.aaustralian.dieselbridge.debug.SensorScanSummary?,
    commandResult: DieselCommandResult?,
): String =
    if (summary != null) {
        summary.completedRoutes.toString() + " / " + summary.totalRoutes + " routes"
    } else {
        (commandResult?.data?.get("state") as? DieselValue.Text)?.value
            ?: "No scan result yet"
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
private fun PowerScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf<BatteryUsageSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    val mode = remember { mutableStateOf(PowerPolicy.mode(context)) }

    LaunchedEffect(Unit) {
        snapshot = withContext(Dispatchers.Default) { BatteryUsageMonitor.snapshot(context) }
        loading = false
    }

    DeveloperPage(
        title = "Power",
        subtitle = "Battery usage · app sleep policy",
        onBack = onBack,
    ) {
        val battery = snapshot
        DiagnosticCard(
            title = "BATTERY",
            primary = battery?.levelPercent?.let { "$it%" } ?: if (loading) "Reading…" else "Unavailable",
            secondary = buildString {
                battery?.voltageVolts?.let { append("${"%.2f".format(it)} V") }
                battery?.currentMilliAmps?.let { if (isNotEmpty()) append(" · "); append("${"%.0f".format(it)} mA") }
                battery?.temperatureCelsius?.let { if (isNotEmpty()) append(" · "); append("${"%.1f".format(it)} °C") }
            }.ifBlank { "No battery telemetry" },
            healthy = battery?.levelPercent != null,
        )
        DiagnosticCard(
            title = "ENERGY COUNTERS",
            primary = battery?.chargeCounterMah?.let { "${"%.0f".format(it)} mAh" } ?: "Unavailable",
            secondary = battery?.energyCounterMwh?.let { "${"%.0f".format(it)} mWh reported by BatteryManager" }
                ?: "Hardware does not expose a readable counter",
            healthy = battery?.chargeCounterMah != null,
        )
        DiagnosticCard(
            title = "APP BATTERY ATTRIBUTION",
            primary = "Unavailable",
            secondary = battery?.batteryAttributionReason ?: "BatteryStats is a privileged API",
            healthy = false,
        )
        DiagnosticCard(
            title = "APP USAGE",
            primary = if (battery?.usageAccessGranted == true) "Foreground time · last 24 h" else "Usage access unavailable",
            secondary = if (battery?.usageAccessGranted == true) {
                "${battery.totalForegroundMs / 60_000L} min observed"
            } else {
                "Grant Usage Access in system settings to show app shares"
            },
            healthy = battery?.usageAccessGranted == true,
        )
        battery?.appUsage?.forEach { entry ->
            DiagnosticCard(
                title = entry.label,
                primary = "${"%.1f".format(entry.sharePercent)}% of foreground time",
                secondary = "${entry.foregroundMs / 60_000L} min · ${entry.packageName}",
                healthy = true,
            )
        }
        SectionLabel("SLEEP POLICY")
        DiagnosticCard(
            title = "CURRENT MODE",
            primary = mode.value.label,
            secondary = when (mode.value) {
                PowerMode.ACTIVE -> "Bridge starts normally and remains available"
                PowerMode.OPTIMIZED -> "Bridge runs; Doze exemption is not changed"
                PowerMode.SLEEPING -> "Bridge stopped; boot auto-start is suppressed"
            },
            healthy = mode.value != PowerMode.SLEEPING,
        )
        PowerMode.values().forEach { option ->
            NavigationChip(
                label = if (mode.value == option) "✓ ${option.label.uppercase()}" else option.label.uppercase(),
                onClick = {
                    PowerPolicy.setMode(context, option)
                    mode.value = option
                },
            )
        }
        DiagnosticCard(
            title = "DOZE EXEMPTION",
            primary = if (PowerHelper.isIgnoringBatteryOptimizations(context)) "Granted" else "Not granted",
            secondary = "This controls system idle policy, not per-app battery attribution",
            healthy = PowerHelper.isIgnoringBatteryOptimizations(context),
            onClick = {
                PowerPolicy.openOptimizationSettings(context)
            },
        )
        NavigationChip(
            label = "OPEN USAGE ACCESS SETTINGS",
            onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
        NavigationChip(
            label = "REFRESH BATTERY DATA",
            onClick = {
                snapshot = BatteryUsageMonitor.snapshot(context)
            },
        )
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

    var source by remember {
        mutableStateOf(
            LogSource.PLATFORM,
        )
    }

    var horizontalDragPx by remember {
        mutableStateOf(0f)
    }

    val swipeThresholdPx =
        with(LocalDensity.current) {
            48.dp.toPx()
        }

    val swipeModifier =
        Modifier.pointerInput(
            source,
            swipeThresholdPx,
        ) {
            detectHorizontalDragGestures(
                onDragStart = {
                    horizontalDragPx = 0f
                },
                onHorizontalDrag = {
                        _,
                        dragAmount,
                    ->
                    horizontalDragPx += dragAmount
                },
                onDragEnd = {
                    source =
                        when {
                            horizontalDragPx <=
                                -swipeThresholdPx &&
                                source ==
                                LogSource.PLATFORM ->
                                LogSource.BLE

                            horizontalDragPx >=
                                swipeThresholdPx &&
                                source ==
                                LogSource.BLE ->
                                LogSource.PLATFORM

                            else ->
                                source
                        }

                    horizontalDragPx = 0f
                },
                onDragCancel = {
                    horizontalDragPx = 0f
                },
            )
        }

    val recordCount =
        when (source) {
            LogSource.PLATFORM ->
                diagnostics.recentRecords.size

            LogSource.BLE ->
                probe.log.size
        }

    DeveloperPage(
        title = "Logs",
        subtitle =
            when (source) {
                LogSource.PLATFORM ->
                    "Platform · $recordCount records"

                LogSource.BLE ->
                    "BLE · $recordCount records"
            },
        onBack = onBack,
        contentModifier = swipeModifier,
    ) {
        LogSourceSwitcher(
            source = source,
            onSourceChanged = {
                source = it
            },
        )

        when (source) {
            LogSource.PLATFORM -> {
                if (
                    diagnostics
                        .recentRecords
                        .isEmpty()
                ) {
                    LogCard(
                        title =
                            "No platform records",
                        body = null,
                    )
                } else {
                    diagnostics
                        .recentRecords
                        .takeLast(20)
                        .asReversed()
                        .forEach { record ->
                            LogCard(
                                title =
                                    "${formatTimestamp(record.timestampMs)} · ${record.type}",
                                body =
                                    record.message,
                            )
                        }
                }
            }

            LogSource.BLE -> {
                if (probe.log.isEmpty()) {
                    LogCard(
                        title =
                            "No BLE records",
                        body = null,
                    )
                } else {
                    probe
                        .log
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
    }
}

@Composable
private fun LogSourceSwitcher(
    source: LogSource,
    onSourceChanged: (LogSource) -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(7.dp),
        verticalAlignment =
            Alignment.CenterVertically,
    ) {
        LogSourceChip(
            label =
                if (source == LogSource.BLE) {
                    "‹ PLATFORM"
                } else {
                    "PLATFORM"
                },
            selected =
                source == LogSource.PLATFORM,
            onClick = {
                onSourceChanged(
                    LogSource.PLATFORM,
                )
            },
            modifier =
                Modifier.weight(1f),
        )

        LogSourceChip(
            label =
                if (source == LogSource.PLATFORM) {
                    "BLE ›"
                } else {
                    "BLE"
                },
            selected =
                source == LogSource.BLE,
            onClick = {
                onSourceChanged(
                    LogSource.BLE,
                )
            },
            modifier =
                Modifier.weight(1f),
        )
    }

    Text(
        text = "Swipe sideways or tap a source",
        style = MaterialTheme.typography.labelSmall,
        color = SecondaryText,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    bottom = 3.dp,
                ),
    )
}

@Composable
private fun LogSourceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color =
            if (selected) {
                AccentText
            } else {
                SecondaryText
            },
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier =
            modifier
                .clip(
                    RoundedCornerShape(
                        15.dp,
                    ),
                )
                .background(
                    if (selected) {
                        AccentText.copy(
                            alpha = 0.14f,
                        )
                    } else {
                        ChipBackground
                    },
                )
                .clickable(
                    onClick = onClick,
                )
                .padding(
                    horizontal = 8.dp,
                    vertical = 8.dp,
                ),
    )
}

@Composable
private fun DeveloperPage(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    contentModifier: Modifier = Modifier,
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
                .then(contentModifier)
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

private fun formatDateTime(
    timestampMs: Long,
): String =
    DateFormat
        .getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.MEDIUM,
        )
        .format(Date(timestampMs))

private fun formatTimestamp(
    timestampMs: Long,
): String =
    DateFormat
        .getTimeInstance(DateFormat.MEDIUM)
        .format(Date(timestampMs))
