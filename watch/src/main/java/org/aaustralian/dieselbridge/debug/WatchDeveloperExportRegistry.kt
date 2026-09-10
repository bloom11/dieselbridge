// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale
import org.aaustralian.dieselbridge.BuildConfig
import org.aaustralian.dieselbridge.ble.ProbeStateHolder
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.provider.ProviderBindingInfo
import org.aaustralian.dieselbridge.platform.provider.ProviderStatus
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRoute
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRouteCatalog
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselValue

/**
 * Watch-specific developer export providers.
 *
 * The registry reads the same live sources used by Diesel Developer UI. No
 * provider activates sensors or maintains a second copy of runtime state.
 */
object WatchDeveloperExportRegistry {

    fun create(
        context: Context,
        platform: DieselPlatform,
        sensorRouteCatalog: AndroidSensorRouteCatalog,
        safeTestRunner: SafePlatformTestRunner,
        sensorProbeStore: SensorProbeStore? = null,
    ): DeveloperExportRegistry =
        DeveloperExportRegistry()
            .apply {
                register(
                    SECTION_BUILD,
                ) {
                    buildSnapshot(
                        context,
                    )
                }

                register(
                    SECTION_PLATFORM,
                ) {
                    platformSnapshot(
                        platform,
                    )
                }

                register(
                    SECTION_BLE,
                ) {
                    bleSnapshot()
                }

                register(
                    SECTION_COMMANDS,
                ) {
                    commandsSnapshot(
                        DeveloperRuntimeAccess
                            .commandCatalog
                            .value,
                    )
                }

                register(
                    SECTION_SENSORS,
                ) {
                    sensorsSnapshot(
                        sensorRouteCatalog,
                    )
                }

                register("sensor_probes") {
                    DeveloperExportSnapshot(
                        source = "android.sensor_probe_scan",
                        items = sensorProbeStore?.snapshot().orEmpty().mapIndexed { index, record ->
                            objectValue(
                                "index" to integer(index),
                                "runId" to text(record.runId),
                                "routeId" to nullableText(record.route?.descriptor?.routeId?.value),
                                "logicalId" to nullableText(record.route?.descriptor?.logicalId?.value),
                                "providerId" to nullableText(record.route?.descriptor?.providerId),
                                "androidType" to nullableInteger(record.route?.inventory?.androidType?.toLong()),
                                "androidId" to nullableInteger(record.route?.inventory?.androidId?.toLong()),
                                "outcome" to text(record.outcome),
                                "elapsedMs" to integer(record.elapsedMs),
                                "reason" to nullableText(record.reason),
                            )
                        },
                        metadata = mapOf("recordCount" to integer(sensorProbeStore?.snapshot()?.size ?: 0)),
                    )
                }

                register(
                    SECTION_SAFE_TESTS,
                ) {
                    safeTestsSnapshot(
                        safeTestRunner,
                    )
                }

                register(
                    SECTION_PERMISSIONS,
                ) {
                    permissionsSnapshot(
                        context,
                    )
                }

                register(
                    SECTION_PLATFORM_LOGS,
                ) {
                    platformLogsSnapshot(
                        platform,
                    )
                }

                register(
                    SECTION_BLE_LOGS,
                ) {
                    bleLogsSnapshot()
                }
            }

    private fun buildSnapshot(
        context: Context,
    ): DeveloperExportSnapshot =
        DeveloperExportSnapshot(
            source =
                "android.build",
            items =
                listOf(
                    objectValue(
                        "packageName" to
                            text(
                                context.packageName,
                            ),
                        "versionName" to
                            text(
                                BuildConfig.VERSION_NAME,
                            ),
                        "versionCode" to
                            integer(
                                BuildConfig.VERSION_CODE,
                            ),
                        "debug" to
                            flag(
                                BuildConfig.DEBUG,
                            ),
                        "sdkInt" to
                            integer(
                                Build.VERSION.SDK_INT,
                            ),
                        "manufacturer" to
                            text(
                                Build.MANUFACTURER,
                            ),
                        "model" to
                            text(
                                Build.MODEL,
                            ),
                        "product" to
                            text(
                                Build.PRODUCT,
                            ),
                        "device" to
                            text(
                                Build.DEVICE,
                            ),
                        "hardware" to
                            text(
                                Build.HARDWARE,
                            ),
                        "fingerprint" to
                            text(
                                Build.FINGERPRINT,
                            ),
                    ),
                ),
        )

    private fun platformSnapshot(
        platform: DieselPlatform,
    ): DeveloperExportSnapshot {
        val diagnostics =
            platform
                .diagnostics
                .state
                .value

        val battery =
            platform
                .battery
                .current()

        val providers =
            diagnostics
                .providers
                .sortedWith(
                    compareBy<ProviderBindingInfo>(
                        { it.capabilityId },
                        { -it.priority },
                        { it.registrationOrder },
                    ),
                )

        val activeCount =
            providers.count {
                it.status ==
                    ProviderStatus.ACTIVE
            }

        val metadata =
            linkedMapOf<String, DieselValue>(
                "providerCount" to
                    integer(
                        providers.size,
                    ),
                "activeProviderCount" to
                    integer(
                        activeCount,
                    ),
                "diagnosticRecordCount" to
                    integer(
                        diagnostics
                            .recentRecords
                            .size,
                    ),
            )

        battery?.let { state ->
            metadata["batteryPercent"] =
                integer(
                    state.percent,
                )

            metadata["batteryCharging"] =
                flag(
                    state.charging,
                )

            metadata["batteryVoltageVolts"] =
                decimal(
                    state.voltageVolts,
                )
        }

        return DeveloperExportSnapshot(
            source =
                "diesel.platform",
            metadata =
                metadata,
            items =
                providers.map { provider ->
                    objectValue(
                        "capabilityId" to
                            text(
                                provider.capabilityId,
                            ),
                        "providerId" to
                            text(
                                provider.providerId,
                            ),
                        "priority" to
                            integer(
                                provider.priority,
                            ),
                        "status" to
                            text(
                                provider
                                    .status
                                    .name
                                    .lowercase(
                                        Locale.ROOT,
                                    ),
                            ),
                        "providerClass" to
                            text(
                                provider.providerClass,
                            ),
                        "registrationOrder" to
                            integer(
                                provider.registrationOrder,
                            ),
                        "registeredAtMs" to
                            integer(
                                provider.registeredAtMs,
                            ),
                        "reason" to
                            nullableText(
                                provider.reason,
                            ),
                    )
                },
        )
    }

    private fun bleSnapshot():
        DeveloperExportSnapshot {
        val probe =
            ProbeStateHolder
                .state
                .value

        return DeveloperExportSnapshot(
            source =
                "diesel.ble_probe",
            metadata =
                mapOf(
                    "logCount" to
                        integer(
                            probe.log.size,
                        ),
                ),
            items =
                listOf(
                    objectValue(
                        "bluetoothOn" to
                            flag(
                                probe.bluetoothOn,
                            ),
                        "advertiserAvailable" to
                            flag(
                                probe.advertiserAvailable,
                            ),
                        "multipleAdvSupported" to
                            flag(
                                probe.multipleAdvSupported,
                            ),
                        "advertising" to
                            flag(
                                probe.advertising,
                            ),
                        "advertiseError" to
                            nullableText(
                                probe.advertiseError,
                            ),
                        "gattServerOpen" to
                            flag(
                                probe.gattServerOpen,
                            ),
                        "centralConnected" to
                            flag(
                                probe.centralConnected,
                            ),
                        "connectedDeviceName" to
                            nullableText(
                                probe.connectedDeviceName,
                            ),
                        "notifySubscribed" to
                            flag(
                                probe.notifySubscribed,
                            ),
                        "peripheralReady" to
                            flag(
                                probe.peripheralReady,
                            ),
                        "bytesReceived" to
                            integer(
                                probe.bytesReceived,
                            ),
                        "lastLine" to
                            nullableText(
                                probe.lastLine,
                            ),
                        "ignoringBatteryOptimizations" to
                            flag(
                                probe
                                    .ignoringBatteryOptimizations,
                            ),
                        "batteryPct" to
                            nullableInteger(
                                probe.batteryPct,
                            ),
                        "charging" to
                            flag(
                                probe.charging,
                            ),
                        "batteryTxAttempts" to
                            integer(
                                probe.batteryTxAttempts,
                            ),
                        "lastBatteryTxPercent" to
                            nullableInteger(
                                probe.lastBatteryTxPercent,
                            ),
                        "lastBatteryTxAtMs" to
                            nullableInteger(
                                probe.lastBatteryTxAtMs,
                            ),
                        "lastBatteryTxReason" to
                            nullableText(
                                probe.lastBatteryTxReason,
                            ),
                        "lastBatteryTxSucceeded" to
                            nullableFlag(
                                probe.lastBatteryTxSucceeded,
                            ),
                    ),
                ),
        )
    }

    private fun commandsSnapshot(
        commands: List<DieselCommandSpec>,
    ): DeveloperExportSnapshot =
        DeveloperExportSnapshot(
            source =
                "diesel.command_registry",
            metadata =
                mapOf(
                    "commandCount" to
                        integer(
                            commands.size,
                        ),
                ),
            items =
                commands.map { command ->
                    objectValue(
                        "name" to
                            text(
                                command.name,
                            ),
                        "summary" to
                            text(
                                command.summary,
                            ),
                        "metadata" to
                            DieselValue.ObjectValue(
                                command.metadata,
                            ),
                    )
                },
        )

    private fun sensorsSnapshot(
        routes: AndroidSensorRouteCatalog,
    ): DeveloperExportSnapshot {
        val sensorRoutes =
            routes.snapshot()

        val androidStringTypes =
            sensorRoutes.count {
                it.inventory
                    .stringType
                    .startsWith(
                        "android.sensor.",
                    )
            }

        return DeveloperExportSnapshot(
            source =
                "android.sensor_manager",
            metadata =
                mapOf(
                    "sensorCount" to
                        integer(
                            sensorRoutes.size,
                        ),
                    "routeCount" to
                        integer(
                            sensorRoutes.size,
                        ),
                    "androidStringTypeCount" to
                        integer(
                            androidStringTypes,
                        ),
                    "otherStringTypeCount" to
                        integer(
                            sensorRoutes.size -
                                androidStringTypes,
                        ),
                ),
            items =
                sensorRoutes.mapIndexed {
                        index,
                        route,
                    ->
                    sensorValue(
                        index,
                        route,
                    )
                },
        )
    }

    private fun sensorValue(
        index: Int,
        route: AndroidSensorRoute,
    ): DieselValue.ObjectValue {
        val sensor =
            route.inventory

        val descriptor =
            route.descriptor

        return objectValue(
            "index" to
                integer(
                    index,
                ),
            "routeId" to
                text(
                    descriptor.routeId.value,
                ),
            "providerId" to
                text(
                    descriptor.providerId,
                ),
            "logicalId" to
                text(
                    descriptor.logicalId.value,
                ),
            "androidId" to
                integer(
                    sensor.androidId,
                ),
            "androidType" to
                integer(
                    sensor.androidType,
                ),
            "stringType" to
                text(
                    sensor.stringType,
                ),
            "name" to
                text(
                    sensor.name,
                ),
            "vendor" to
                text(
                    sensor.vendor,
                ),
            "version" to
                integer(
                    sensor.version,
                ),
            "maxRange" to
                decimal(
                    sensor.maxRange.toDouble(),
                ),
            "resolution" to
                decimal(
                    sensor.resolution.toDouble(),
                ),
            "powerMilliAmps" to
                decimal(
                    sensor.powerMilliAmps.toDouble(),
                ),
            "minDelayUs" to
                integer(
                    sensor.minDelayUs,
                ),
            "maxDelayUs" to
                integer(
                    sensor.maxDelayUs,
                ),
            "fifoReservedEventCount" to
                integer(
                    sensor.fifoReservedEventCount,
                ),
            "fifoMaxEventCount" to
                integer(
                    sensor.fifoMaxEventCount,
                ),
            "reportingMode" to
                integer(
                    sensor.reportingMode,
                ),
            "wakeUp" to
                flag(
                    sensor.wakeUp,
                ),
        )
    }

    private fun safeTestsSnapshot(
        runner: SafePlatformTestRunner,
    ): DeveloperExportSnapshot {
        val tests =
            runner.specs()

        return DeveloperExportSnapshot(
            source =
                "diesel.safe_test_runner",
            metadata =
                mapOf(
                    "testCount" to
                        integer(
                            tests.size,
                        ),
                ),
            items =
                tests.map { test ->
                    objectValue(
                        "name" to
                            text(
                                test.name,
                            ),
                        "summary" to
                            text(
                                test.summary,
                            ),
                    )
                },
        )
    }

    @Suppress("DEPRECATION")
    private fun permissionsSnapshot(
        context: Context,
    ): DeveloperExportSnapshot {
        val info =
            context
                .packageManager
                .getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                )

        val requested =
            info.requestedPermissions
                ?: emptyArray()

        val flags =
            info.requestedPermissionsFlags

        val permissions =
            requested
                .mapIndexed {
                        index,
                        permission,
                    ->
                    val permissionFlags =
                        flags
                            ?.getOrNull(
                                index,
                            )
                            ?: 0

                    permission to
                        (
                            (
                                permissionFlags and
                                    PackageInfo
                                        .REQUESTED_PERMISSION_GRANTED
                            ) !=
                                0
                        )
                }
                .sortedBy {
                    it.first
                }

        return DeveloperExportSnapshot(
            source =
                "android.package_manager",
            metadata =
                mapOf(
                    "requestedPermissionCount" to
                        integer(
                            permissions.size,
                        ),
                ),
            items =
                permissions.map {
                        (
                            permission,
                            granted,
                        ),
                    ->
                    objectValue(
                        "name" to
                            text(
                                permission,
                            ),
                        "granted" to
                            flag(
                                granted,
                            ),
                    )
                },
        )
    }

    private fun platformLogsSnapshot(
        platform: DieselPlatform,
    ): DeveloperExportSnapshot {
        val records =
            platform
                .diagnostics
                .state
                .value
                .recentRecords

        return DeveloperExportSnapshot(
            source =
                "diesel.platform_diagnostics",
            metadata =
                mapOf(
                    "recordCount" to
                        integer(
                            records.size,
                        ),
                ),
            items =
                records.mapIndexed {
                        index,
                        record,
                    ->
                    objectValue(
                        "index" to
                            integer(
                                index,
                            ),
                        "timestampMs" to
                            integer(
                                record.timestampMs,
                            ),
                        "type" to
                            text(
                                record.type,
                            ),
                        "message" to
                            text(
                                record.message,
                            ),
                    )
                },
        )
    }

    private fun bleLogsSnapshot():
        DeveloperExportSnapshot {
        val logs =
            ProbeStateHolder
                .state
                .value
                .log

        return DeveloperExportSnapshot(
            source =
                "diesel.ble_probe_log",
            metadata =
                mapOf(
                    "recordCount" to
                        integer(
                            logs.size,
                        ),
                ),
            items =
                logs.mapIndexed {
                        index,
                        line,
                    ->
                    objectValue(
                        "index" to
                            integer(
                                index,
                            ),
                        "line" to
                            text(
                                line,
                            ),
                    )
                },
        )
    }

    private fun objectValue(
        vararg fields:
            Pair<String, DieselValue>,
    ): DieselValue.ObjectValue =
        DieselValue.ObjectValue(
            linkedMapOf(
                *fields,
            ),
        )

    private fun text(
        value: String,
    ): DieselValue.Text =
        DieselValue.Text(
            value,
        )

    private fun nullableText(
        value: String?,
    ): DieselValue =
        value
            ?.let {
                text(
                    it,
                )
            }
            ?: DieselValue.Null

    private fun integer(
        value: Int,
    ): DieselValue.Integer =
        integer(
            value.toLong(),
        )

    private fun integer(
        value: Long,
    ): DieselValue.Integer =
        DieselValue.Integer(
            value,
        )

    private fun nullableInteger(
        value: Int?,
    ): DieselValue =
        value
            ?.let {
                integer(
                    it,
                )
            }
            ?: DieselValue.Null

    private fun nullableInteger(
        value: Long?,
    ): DieselValue =
        value
            ?.let {
                integer(
                    it,
                )
            }
            ?: DieselValue.Null

    private fun flag(
        value: Boolean,
    ): DieselValue.Flag =
        DieselValue.Flag(
            value,
        )

    private fun nullableFlag(
        value: Boolean?,
    ): DieselValue =
        value
            ?.let {
                flag(
                    it,
                )
            }
            ?: DieselValue.Null

    private fun decimal(
        value: Double,
    ): DieselValue =
        if (value.isFinite()) {
            DieselValue.Decimal(
                value,
            )
        } else {
            DieselValue.Null
        }

    private const val SECTION_BUILD =
        "build"

    private const val SECTION_PLATFORM =
        "platform"

    private const val SECTION_BLE =
        "ble"

    private const val SECTION_COMMANDS =
        "commands"

    private const val SECTION_SENSORS =
        "sensors"

    private const val SECTION_SAFE_TESTS =
        "safe_tests"

    private const val SECTION_PERMISSIONS =
        "permissions"

    private const val SECTION_PLATFORM_LOGS =
        "platform_logs"

    private const val SECTION_BLE_LOGS =
        "ble_logs"






}
