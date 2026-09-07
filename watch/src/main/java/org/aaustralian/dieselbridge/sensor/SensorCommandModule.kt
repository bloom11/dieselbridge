// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRoute
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRouteCatalog
import org.aaustralian.dieselbridge.protocol.DieselCommandContext
import org.aaustralian.dieselbridge.protocol.DieselCommandModule
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue

/**
 * Read-only sensor discovery commands.
 *
 * M4.0 intentionally performs inventory only. No listener is registered and
 * no sensor is sampled by this module.
 */
class SensorCommandModule(
    private val routes: AndroidSensorRouteCatalog,
) : DieselCommandModule {

    override fun install(
        registry: DieselCommandRegistry,
    ) {
        registry.register(
            DieselCommandSpec(
                name =
                    COMMAND_SENSOR_LIST,
                summary =
                    "List process-visible Android sensors",
                metadata =
                    mapOf(
                        "domain" to
                            DieselValue.Text(
                                "sensor",
                            ),
                        "effect" to
                            DieselValue.Text(
                                "read_only",
                            ),
                        "pagination" to
                            DieselValue.Text(
                                "offset_limit",
                            ),
                    ),
            ),
        ) { context ->
            listSensors(
                context,
            )
        }
    }

    private fun listSensors(
        context: DieselCommandContext,
    ): DieselCommandResult {
        if (context.name != null) {
            return invalidArguments()
        }

        if (
            context.args.keys.any {
                it !in ALLOWED_ARGUMENTS
            }
        ) {
            return invalidArguments()
        }

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

        val sensorRoutes =
            routes.snapshot()

        val page =
            sensorRoutes
                .drop(
                    offset,
                )
                .take(
                    limit,
                )

        val encoded =
            page.mapIndexed {
                    pageIndex,
                    route,
                ->
                encodeCompactSensor(
                    index =
                        offset +
                            pageIndex,
                    route =
                        route,
                )
            }

        val hasMore =
            offset < sensorRoutes.size &&
                offset + page.size <
                sensorRoutes.size

        return DieselCommandResult.ok(
            data =
                linkedMapOf(
                    "source" to
                        DieselValue.Text(
                            SOURCE_ANDROID_SENSOR_MANAGER,
                        ),
                    "total" to
                        DieselValue.Integer(
                            sensorRoutes.size.toLong(),
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
                    "sensors" to
                        DieselValue.ListValue(
                            encoded,
                        ),
                ),
        )
    }

    private fun encodeCompactSensor(
        index: Int,
        route: AndroidSensorRoute,
    ): DieselValue.ObjectValue {
        val sensor =
            route.inventory

        val descriptor =
            route.descriptor

        return DieselValue.ObjectValue(
            linkedMapOf(
                "index" to
                    DieselValue.Integer(
                        index.toLong(),
                    ),
                "routeId" to
                    DieselValue.Text(
                        descriptor.routeId.value,
                    ),
                "providerId" to
                    DieselValue.Text(
                        descriptor.providerId,
                    ),
                "id" to
                    DieselValue.Text(
                        descriptor.logicalId.value,
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
                "wakeUp" to
                    DieselValue.Flag(
                        sensor.wakeUp,
                    ),
                "reportingMode" to
                    DieselValue.Integer(
                        sensor.reportingMode.toLong(),
                    ),
            ),
        )
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

    /**
     * Bound vendor-controlled Android metadata before it enters the protocol.
     *
     * ISO control characters are normalized so JSON escaping cannot amplify a
     * short-looking metadata string into an unexpectedly large response.
     * Iterating by Unicode code point also avoids cutting a surrogate pair.
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
                            "invalid_args",
                        ),
                ),
        )

    companion object {
        const val COMMAND_SENSOR_LIST =
            "sensor.list"

        const val ARG_OFFSET =
            "offset"

        const val ARG_LIMIT =
            "limit"

        const val DEFAULT_PAGE_SIZE =
            4

        const val MAX_PAGE_SIZE =
            4

        private const val MAX_REMOTE_TEXT_CODE_POINTS =
            48

        private const val SOURCE_ANDROID_SENSOR_MANAGER =
            "android.sensor_manager"

        private val ALLOWED_ARGUMENTS =
            setOf(
                ARG_OFFSET,
                ARG_LIMIT,
            )
    }
}
