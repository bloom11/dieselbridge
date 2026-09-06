// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.platform.sensor.SensorInventory
import org.aaustralian.dieselbridge.platform.sensor.SensorInventoryEntry
import org.aaustralian.dieselbridge.protocol.DieselCommandContext
import org.aaustralian.dieselbridge.protocol.DieselCommandModule
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue

/**
 * Authorized remote access to developer-visible runtime information.
 *
 * The authorization gate can only be changed through the local developer UI.
 * This module deliberately contains no command that mutates authorization.
 *
 * M4.0c1 exposes the first section: the complete SensorManager census.
 * Additional developer sections can be added here without changing BLE,
 * Gadgetbridge, the protocol engine, or the watch UI data sources.
 */
class DeveloperExportCommandModule(
    private val authorization:
        DeveloperExportAuthorization,
    private val sensorInventory:
        SensorInventory,
) : DieselCommandModule {

    override fun install(
        registry: DieselCommandRegistry,
    ) {
        registry.register(
            DieselCommandSpec(
                name =
                    COMMAND_STATUS,
                summary =
                    "Show remote developer export status",
                metadata =
                    metadata(
                        pagination = false,
                    ),
            ),
        ) { context ->
            status(
                context,
            )
        }

        registry.register(
            DieselCommandSpec(
                name =
                    COMMAND_EXPORT,
                summary =
                    "Export authorized developer data",
                metadata =
                    metadata(
                        pagination = true,
                    ),
            ),
        ) { context ->
            export(
                context,
            )
        }
    }

    private fun status(
        context: DieselCommandContext,
    ): DieselCommandResult {
        if (
            context.name != null ||
            context.args.isNotEmpty()
        ) {
            return invalidArguments()
        }

        return DieselCommandResult.ok(
            data =
                linkedMapOf(
                    "enabled" to
                        DieselValue.Flag(
                            authorization
                                .isEnabled(),
                        ),
                    "authorization" to
                        DieselValue.Text(
                            "local_developer_ui",
                        ),
                    "sections" to
                        DieselValue.ListValue(
                            SUPPORTED_SECTIONS
                                .map {
                                    DieselValue.Text(
                                        it,
                                    )
                                },
                        ),
                    "maxPageSize" to
                        DieselValue.Integer(
                            MAX_PAGE_SIZE.toLong(),
                        ),
                ),
        )
    }

    private fun export(
        context: DieselCommandContext,
    ): DieselCommandResult {
        if (context.name != null) {
            return invalidArguments()
        }

        if (
            context.args.keys.any {
                it !in EXPORT_ARGUMENTS
            }
        ) {
            return invalidArguments()
        }

        val section =
            (
                context.args[ARG_SECTION]
                    as? DieselValue.Text
            )
                ?.value
                ?: return invalidArguments()

        val offset =
            integerArgument(
                context = context,
                key = ARG_OFFSET,
                defaultValue = 0,
                minimum = 0,
                maximum = Int.MAX_VALUE,
            )
                ?: return invalidArguments()

        val limit =
            integerArgument(
                context = context,
                key = ARG_LIMIT,
                defaultValue =
                    DEFAULT_PAGE_SIZE,
                minimum = 1,
                maximum =
                    MAX_PAGE_SIZE,
            )
                ?: return invalidArguments()

        if (!authorization.isEnabled()) {
            return DieselCommandResult(
                status =
                    DieselResponseStatus
                        .UNAVAILABLE,
                data =
                    mapOf(
                        "reason" to
                            DieselValue.Text(
                                REASON_DISABLED,
                            ),
                    ),
            )
        }

        return when (section) {
            SECTION_SENSORS ->
                exportSensors(
                    offset = offset,
                    limit = limit,
                )

            else ->
                invalidArguments()
        }
    }

    private fun exportSensors(
        offset: Int,
        limit: Int,
    ): DieselCommandResult {
        val snapshot =
            runCatching {
                sensorInventory
                    .snapshot()
            }
                .getOrElse {
                    return DieselCommandResult(
                        status =
                            DieselResponseStatus
                                .FAILED,
                        data =
                            mapOf(
                                "reason" to
                                    DieselValue.Text(
                                        REASON_SNAPSHOT_FAILED,
                                    ),
                            ),
                    )
                }

        val page =
            snapshot
                .drop(
                    offset,
                )
                .take(
                    limit,
                )

        val values =
            page.mapIndexed {
                    pageIndex,
                    sensor,
                ->
                encodeSensor(
                    index =
                        offset +
                            pageIndex,
                    sensor =
                        sensor,
                )
            }

        val hasMore =
            offset < snapshot.size &&
                offset.toLong() +
                    page.size.toLong() <
                snapshot.size.toLong()

        return DieselCommandResult.ok(
            data =
                linkedMapOf(
                    "section" to
                        DieselValue.Text(
                            SECTION_SENSORS,
                        ),
                    "source" to
                        DieselValue.Text(
                            SOURCE_SENSOR_MANAGER,
                        ),
                    "total" to
                        DieselValue.Integer(
                            snapshot.size.toLong(),
                        ),
                    "offset" to
                        DieselValue.Integer(
                            offset.toLong(),
                        ),
                    "returned" to
                        DieselValue.Integer(
                            page.size.toLong(),
                        ),
                    "hasMore" to
                        DieselValue.Flag(
                            hasMore,
                        ),
                    "items" to
                        DieselValue.ListValue(
                            values,
                        ),
                ),
        )
    }

    private fun encodeSensor(
        index: Int,
        sensor: SensorInventoryEntry,
    ): DieselValue.ObjectValue =
        DieselValue.ObjectValue(
            linkedMapOf(
                "index" to
                    DieselValue.Integer(
                        index.toLong(),
                    ),
                "logicalId" to
                    DieselValue.Text(
                        compactText(
                            sensor.logicalId,
                        ),
                    ),
                "androidId" to
                    DieselValue.Integer(
                        sensor.androidId.toLong(),
                    ),
                "androidType" to
                    DieselValue.Integer(
                        sensor.androidType.toLong(),
                    ),
                "stringType" to
                    DieselValue.Text(
                        compactText(
                            sensor.stringType,
                        ),
                    ),
                "name" to
                    DieselValue.Text(
                        compactText(
                            sensor.name,
                        ),
                    ),
                "vendor" to
                    DieselValue.Text(
                        compactText(
                            sensor.vendor,
                        ),
                    ),
                "version" to
                    DieselValue.Integer(
                        sensor.version.toLong(),
                    ),
                "maxRange" to
                    decimal(
                        sensor.maxRange,
                    ),
                "resolution" to
                    decimal(
                        sensor.resolution,
                    ),
                "powerMilliAmps" to
                    decimal(
                        sensor.powerMilliAmps,
                    ),
                "minDelayUs" to
                    DieselValue.Integer(
                        sensor.minDelayUs.toLong(),
                    ),
                "maxDelayUs" to
                    DieselValue.Integer(
                        sensor.maxDelayUs.toLong(),
                    ),
                "fifoReservedEventCount" to
                    DieselValue.Integer(
                        sensor
                            .fifoReservedEventCount
                            .toLong(),
                    ),
                "fifoMaxEventCount" to
                    DieselValue.Integer(
                        sensor
                            .fifoMaxEventCount
                            .toLong(),
                    ),
                "reportingMode" to
                    DieselValue.Integer(
                        sensor.reportingMode
                            .toLong(),
                    ),
                "wakeUp" to
                    DieselValue.Flag(
                        sensor.wakeUp,
                    ),
            ),
        )

    private fun decimal(
        value: Float,
    ): DieselValue =
        if (value.isFinite()) {
            DieselValue.Decimal(
                value.toDouble(),
            )
        } else {
            DieselValue.Null
        }

    /**
     * Bound hardware/vendor metadata before it reaches the fixed-size response
     * envelope. Controls are normalized and surrogate pairs are never split.
     */
    private fun compactText(
        value: String,
    ): String {
        val output =
            StringBuilder()

        var offset = 0
        var codePoints = 0

        while (
            offset < value.length &&
            codePoints <
            MAX_REMOTE_TEXT_CODE_POINTS
        ) {
            val codePoint =
                value.codePointAt(
                    offset,
                )

            if (
                Character.isISOControl(
                    codePoint,
                )
            ) {
                output.append(
                    ' ',
                )
            } else {
                output.appendCodePoint(
                    codePoint,
                )
            }

            offset +=
                Character.charCount(
                    codePoint,
                )

            codePoints++
        }

        return output.toString()
    }

    private fun integerArgument(
        context: DieselCommandContext,
        key: String,
        defaultValue: Int,
        minimum: Int,
        maximum: Int,
    ): Int? {
        val value =
            context.args[key]
                ?: return defaultValue

        val integer =
            (
                value as?
                    DieselValue.Integer
            )
                ?.value
                ?: return null

        if (
            integer <
            minimum.toLong() ||
            integer >
            maximum.toLong()
        ) {
            return null
        }

        return integer.toInt()
    }

    private fun invalidArguments():
        DieselCommandResult =
        DieselCommandResult(
            status =
                DieselResponseStatus
                    .INVALID_REQUEST,
            data =
                mapOf(
                    "reason" to
                        DieselValue.Text(
                            REASON_INVALID_ARGUMENTS,
                        ),
                ),
        )

    private fun metadata(
        pagination: Boolean,
    ): Map<String, DieselValue> =
        linkedMapOf<String, DieselValue>(
            "domain" to
                DieselValue.Text(
                    "debug",
                ),
            "effect" to
                DieselValue.Text(
                    "read_only",
                ),
            "authorization" to
                DieselValue.Text(
                    "local_developer_ui",
                ),
        )
            .apply {
                if (pagination) {
                    put(
                        "pagination",
                        DieselValue.Text(
                            "offset_limit",
                        ),
                    )
                }
            }

    companion object {
        const val COMMAND_STATUS =
            "debug.status"

        const val COMMAND_EXPORT =
            "debug.export"

        const val SECTION_SENSORS =
            "sensors"

        const val DEFAULT_PAGE_SIZE =
            2

        const val MAX_PAGE_SIZE =
            2

        private const val ARG_SECTION =
            "section"

        private const val ARG_OFFSET =
            "offset"

        private const val ARG_LIMIT =
            "limit"

        private const val SOURCE_SENSOR_MANAGER =
            "android.sensor_manager"

        private const val REASON_DISABLED =
            "remote_export_disabled"

        private const val REASON_INVALID_ARGUMENTS =
            "invalid_args"

        private const val REASON_SNAPSHOT_FAILED =
            "snapshot_failed"

        private const val MAX_REMOTE_TEXT_CODE_POINTS =
            64

        private val SUPPORTED_SECTIONS =
            listOf(
                SECTION_SENSORS,
            )

        private val EXPORT_ARGUMENTS =
            setOf(
                ARG_SECTION,
                ARG_OFFSET,
                ARG_LIMIT,
            )
    }
}
