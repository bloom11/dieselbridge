// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ui.debug

import androidx.activity.compose.BackHandler
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
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.capability.BatteryCapability
import org.aaustralian.dieselbridge.platform.provider.ProviderBindingInfo
import org.aaustralian.dieselbridge.platform.provider.ProviderStatus
import org.aaustralian.dieselbridge.platform.sensor.SensorInventory
import org.aaustralian.dieselbridge.platform.sensor.SensorInventoryEntry
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselRequest
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

    val probe by
        ProbeStateHolder.state.collectAsStateWithLifecycle()

    var selectedCommand by remember { mutableStateOf<DieselCommandSpec?>(null) }

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
        if (page == DiagnosticsPage.COMMAND_DETAIL) {
            selectedCommand = null
            page = DiagnosticsPage.COMMANDS
        } else {
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
    onBack: () -> Unit,
) {
    val tests =
        runner
            ?.specs()
            .orEmpty()

    var lastResult by remember(runner) {
        mutableStateOf<SafePlatformTestResult?>(null)
    }
    var matrixResult by remember(sensorMatrixExperiment) {
        mutableStateOf<SensorMatrixExperimentResult?>(null)
    }
    var matrixRunning by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    DeveloperPage(
        title = "Tools",
        subtitle = "${tests.size} bounded safe tests",
        onBack = onBack,
    ) {
        RemoteDeveloperAccessControl(
            policy =
                developerRemoteAccessPolicy,
        )

        sensorMatrixExperiment?.let { experiment ->
            DiagnosticCard(
                title = "SENSOR MATRIX",
                primary = if (matrixRunning) "Running…" else "8 logical targets",
                secondary = matrixResult?.let { "${it.eventCount} events · ${it.unavailableCount} unavailable · ${it.nonEventCount} other" }
                    ?: "One bounded sample per target",
                healthy = !matrixRunning,
            )
            NavigationChip(
                label = if (matrixRunning) "RUNNING SENSOR MATRIX…" else "RUN SENSOR MATRIX",
                onClick = {
                    if (!matrixRunning) {
                        matrixRunning = true
                        scope.launch {
                            matrixResult = experiment.run()
                            matrixRunning = false
                        }
                    }
                },
            )
        }

        lastResult?.let { result ->
            DiagnosticCard(
                title = "LAST RESULT",
                primary =
                    when (result) {
                        is SafePlatformTestResult.Success ->
                            "SUCCESS"

                        is SafePlatformTestResult.Unavailable ->
                            "UNAVAILABLE"

                        is SafePlatformTestResult.RateLimited ->
                            "RATE LIMITED"

                        is SafePlatformTestResult.Failed ->
                            "FAILED"

                        is SafePlatformTestResult.UnknownTarget ->
                            "UNKNOWN TARGET"
                    },
                secondary =
                    when (result) {
                        is SafePlatformTestResult.Success ->
                            "${result.target} · ${result.providerId}"

                        is SafePlatformTestResult.Unavailable ->
                            "${result.target} · no active provider"

                        is SafePlatformTestResult.RateLimited ->
                            "${result.target} · retry in ${result.retryAfterMs} ms"

                        is SafePlatformTestResult.Failed ->
                            "${result.target} · ${result.message}"

                        is SafePlatformTestResult.UnknownTarget ->
                            result.target
                                ?: "<missing>"
                    },
                healthy =
                    result is SafePlatformTestResult.Success,
            )
        }

        when {
            runner == null ->
                DiagnosticCard(
                    title = "SAFE TESTS",
                    primary = "Runner unavailable",
                    secondary =
                        "Diesel service runtime is not attached",
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
                        secondary =
                            "Platform routed · fixed safety bounds",
                        healthy = true,
                        onClick = {
                            lastResult =
                                runner.run(
                                    test.name,
                                )
                        },
                    )
                }
        }
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
