// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRoute
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRouteCatalog
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.sensor.SensorCapability
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityCatalog
import org.aaustralian.dieselbridge.platform.sensor.SensorReadOptions
import org.aaustralian.dieselbridge.platform.sensor.SensorReadResult
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorSampler
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
    private val capabilities: CapabilityRegistry? = null,
) : DieselCommandModule {
    private val experimentRunner = capabilities?.let(::SensorExperimentRunner)

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
                        "arguments" to DieselValue.ObjectValue(
                            mapOf(
                                ARG_OFFSET to DieselValue.Text("integer >= 0, default 0"),
                                ARG_LIMIT to DieselValue.Text("integer 1..$MAX_PAGE_SIZE, default $DEFAULT_PAGE_SIZE"),
                            ),
                        ),
                    ),
            ),
        ) { context ->
            listSensors(
                context,
            )
        }

        if (capabilities != null) {
            registry.register(
                DieselCommandSpec(
                    name = COMMAND_SENSOR_READ,
                    summary = "Read one logical sensor capability",
                    metadata = mapOf(
                        "domain" to DieselValue.Text("sensor"),
                        "effect" to DieselValue.Text("read_only"),
                        "routing" to DieselValue.Text("automatic_provider_selection"),
                        "target" to DieselValue.Text("logical sensor name"),
                        "arguments" to DieselValue.ObjectValue(
                            mapOf(
                                ARG_TIMEOUT_MS to DieselValue.Text("integer ${AndroidSensorSampler.MIN_TIMEOUT_MS}..${AndroidSensorSampler.MAX_TIMEOUT_MS}, default ${AndroidSensorSampler.DEFAULT_TIMEOUT_MS}"),
                            ),
                        ),
                    ),
                ),
            ) { context -> readSensor(context, capabilities) }
            registry.register(
                DieselCommandSpec(
                    name = COMMAND_SENSOR_MATRIX,
                    summary = "Read every standard logical sensor target",
                    metadata = mapOf(
                        "domain" to DieselValue.Text("sensor"),
                        "effect" to DieselValue.Text("read_only"),
                        "bounded" to DieselValue.Text("one sample per target"),
                        "arguments" to DieselValue.ObjectValue(
                            mapOf(
                                ARG_TIMEOUT_MS to DieselValue.Text("integer ${AndroidSensorSampler.MIN_TIMEOUT_MS}..${AndroidSensorSampler.MAX_TIMEOUT_MS}, default ${AndroidSensorSampler.DEFAULT_TIMEOUT_MS}"),
                            ),
                        ),
                    ),
                ),
            ) { context -> readMatrix(context, capabilities) }
            registry.register(
                DieselCommandSpec(
                    name = COMMAND_SENSOR_EXPERIMENT,
                    summary = "Run a bounded repeated logical sensor experiment",
                    metadata = mapOf(
                        "domain" to DieselValue.Text("sensor"),
                        "effect" to DieselValue.Text("read_only"),
                        "bounded" to DieselValue.Text("maximum 8 rounds and 30 seconds"),
                        "arguments" to DieselValue.ObjectValue(
                            mapOf(
                                ARG_ROUNDS to DieselValue.Text("integer 1..${SensorExperimentRunner.MAX_ROUNDS}, default 1"),
                                ARG_TIMEOUT_MS to DieselValue.Text("integer ${SensorExperimentRunner.MIN_TIMEOUT_MS}..${SensorExperimentRunner.MAX_TIMEOUT_MS}, default ${AndroidSensorSampler.DEFAULT_TIMEOUT_MS}"),
                                ARG_TOTAL_TIMEOUT_MS to DieselValue.Text("integer ${SensorExperimentRunner.MIN_TOTAL_TIMEOUT_MS}..${SensorExperimentRunner.MAX_TOTAL_TIMEOUT_MS}, default ${SensorExperimentRunner.DEFAULT_TOTAL_TIMEOUT_MS}"),
                            ),
                        ),
                    ),
                ),
            ) { context -> runExperiment(context, experimentRunner) }
        }
    }

    private suspend fun readSensor(
        context: DieselCommandContext,
        registry: CapabilityRegistry,
    ): DieselCommandResult {
        val target = context.name ?: return invalidReadArguments()
        if (context.args.keys.any { it != ARG_TIMEOUT_MS }) {
            return invalidReadArguments()
        }
        val timeout = when (val value = context.args[ARG_TIMEOUT_MS]) {
            null -> AndroidSensorSampler.DEFAULT_TIMEOUT_MS
            is DieselValue.Integer -> value.value
            else -> return invalidReadArguments()
        }
        if (timeout !in AndroidSensorSampler.MIN_TIMEOUT_MS..AndroidSensorSampler.MAX_TIMEOUT_MS) {
            return invalidReadArguments()
        }
        val capability = registry.resolveAs<SensorCapability>("sensor.${context.name}")
        if (capability == null) {
            val known = target in CANONICAL_LOGICAL_IDS
            return DieselCommandResult(
                if (known) DieselResponseStatus.UNAVAILABLE else DieselResponseStatus.UNKNOWN_TARGET,
                mapOf(
                    "reason" to DieselValue.Text(
                        if (known) "sensor_provider_unavailable" else "unknown_sensor_capability",
                    ),
                ),
            )
        }
        return when (val result = SensorReadCoordinator.read(registry, target, SensorReadOptions(timeout))) {
            is SensorReadResult.Event -> encodeReading(result.reading)
            SensorReadResult.Unavailable -> DieselCommandResult(
                DieselResponseStatus.UNAVAILABLE,
                mapOf("reason" to DieselValue.Text("sensor_unavailable")),
            )
            is SensorReadResult.PermissionDenied -> DieselCommandResult.ok(
                mapOf(
                    "outcome" to DieselValue.Text("permission_denied"),
                    "requiredPermission" to (result.requiredPermission?.let(DieselValue::Text) ?: DieselValue.Null),
                ),
            )
            SensorReadResult.Timeout -> DieselCommandResult.ok(mapOf("outcome" to DieselValue.Text("timeout")))
            is SensorReadResult.RegistrationRejected -> DieselCommandResult.ok(
                mapOf(
                    "outcome" to DieselValue.Text("registration_rejected"),
                    "reason" to (result.reason?.let(DieselValue::Text) ?: DieselValue.Null),
                ),
            )
        }
    }

    private suspend fun runExperiment(
        context: DieselCommandContext,
        runner: SensorExperimentRunner?,
    ): DieselCommandResult {
        if (context.name != null || context.args.keys.any { it !in EXPERIMENT_ARGUMENTS }) return invalidReadArguments()
        val rounds = integerArgument(context, ARG_ROUNDS, 1, 1, SensorExperimentRunner.MAX_ROUNDS) ?: return invalidReadArguments()
        val timeout = longArgument(context, ARG_TIMEOUT_MS, AndroidSensorSampler.DEFAULT_TIMEOUT_MS, SensorExperimentRunner.MIN_TIMEOUT_MS, SensorExperimentRunner.MAX_TIMEOUT_MS) ?: return invalidReadArguments()
        val totalTimeout = longArgument(context, ARG_TOTAL_TIMEOUT_MS, SensorExperimentRunner.DEFAULT_TOTAL_TIMEOUT_MS, SensorExperimentRunner.MIN_TOTAL_TIMEOUT_MS, SensorExperimentRunner.MAX_TOTAL_TIMEOUT_MS) ?: return invalidReadArguments()
        val activeRunner = runner ?: return DieselCommandResult(DieselResponseStatus.UNAVAILABLE, mapOf("reason" to DieselValue.Text("experiment_runner_unavailable")))
        val result = activeRunner.run(SensorExperimentSpec(rounds = rounds, timeoutMs = timeout, totalTimeoutMs = totalTimeout))
        val samples = result.samples.take(MAX_EXPERIMENT_SAMPLES).map { sample ->
            val fields = linkedMapOf<String, DieselValue>(
                "round" to DieselValue.Integer(sample.round.toLong()),
                "target" to DieselValue.Text(sample.target),
                "elapsedMs" to DieselValue.Integer(sample.elapsedMs),
            )
            when (val read = sample.result) {
                is SensorReadResult.Event -> {
                    fields["outcome"] = DieselValue.Text("event")
                    fields["providerId"] = DieselValue.Text(read.reading.providerId)
                    fields["values"] = DieselValue.ListValue(read.reading.values.take(MAX_EXPERIMENT_VALUES).map { if (it.isFinite()) DieselValue.Decimal(it.toDouble()) else DieselValue.Null })
                }
                SensorReadResult.Unavailable -> fields["outcome"] = DieselValue.Text("unavailable")
                SensorReadResult.Timeout -> fields["outcome"] = DieselValue.Text("timeout")
                is SensorReadResult.PermissionDenied -> {
                    fields["outcome"] = DieselValue.Text("permission_denied")
                    fields["requiredPermission"] = read.requiredPermission?.let(DieselValue::Text) ?: DieselValue.Null
                }
                is SensorReadResult.RegistrationRejected -> {
                    fields["outcome"] = DieselValue.Text("registration_rejected")
                    fields["reason"] = DieselValue.Text(read.reason ?: "unknown")
                }
            }
            DieselValue.ObjectValue(fields)
        }
        return DieselCommandResult.ok(
            mapOf(
                "experimentId" to DieselValue.Text(result.spec.id),
                "startedAtMs" to DieselValue.Integer(result.startedAtMs),
                "finishedAtMs" to DieselValue.Integer(result.finishedAtMs),
                "roundsCompleted" to DieselValue.Integer(result.roundsCompleted.toLong()),
                "eventCount" to DieselValue.Integer(result.eventCount.toLong()),
                "unavailableCount" to DieselValue.Integer(result.unavailableCount.toLong()),
                "timeoutCount" to DieselValue.Integer(result.timeoutCount.toLong()),
                "sampleCount" to DieselValue.Integer(result.samples.size.toLong()),
                "samplesReturned" to DieselValue.Integer(samples.size.toLong()),
                "samplesTruncated" to DieselValue.Flag(result.samples.size > samples.size),
                "samples" to DieselValue.ListValue(samples),
            ),
        )
    }

    private suspend fun readMatrix(
        context: DieselCommandContext,
        registry: CapabilityRegistry,
    ): DieselCommandResult {
        if (context.name != null || context.args.keys.any { it != ARG_TIMEOUT_MS }) return invalidReadArguments()
        val timeout = when (val value = context.args[ARG_TIMEOUT_MS]) {
            null -> AndroidSensorSampler.DEFAULT_TIMEOUT_MS
            is DieselValue.Integer -> value.value
            else -> return invalidReadArguments()
        }
        if (timeout !in AndroidSensorSampler.MIN_TIMEOUT_MS..AndroidSensorSampler.MAX_TIMEOUT_MS) return invalidReadArguments()
        val results = SensorReadCoordinator.matrix(registry, SensorReadOptions(timeout)).map { (target, result) ->
            val fields = linkedMapOf<String, DieselValue>("target" to DieselValue.Text(target))
            when (result) {
                is SensorReadResult.Event -> {
                    fields["status"] = DieselValue.Text("ok")
                    fields["outcome"] = DieselValue.Text("event")
                    fields["providerId"] = DieselValue.Text(result.reading.providerId)
                    fields["elapsedMs"] = DieselValue.Integer(result.reading.elapsedMs)
                    val values = result.reading.values.take(MAX_MATRIX_VALUES)
                    fields["capability"] = DieselValue.Text(result.reading.capabilityId.value)
                    fields["accuracy"] = result.reading.accuracy?.let { DieselValue.Integer(it.toLong()) } ?: DieselValue.Null
                    fields["sensorTimestampNs"] = DieselValue.Integer(result.reading.timestampNanos)
                    fields["valueCount"] = DieselValue.Integer(result.reading.values.size.toLong())
                    fields["returnedValueCount"] = DieselValue.Integer(values.size.toLong())
                    fields["valuesTruncated"] = DieselValue.Flag(result.reading.values.size > values.size)
                    fields["values"] = DieselValue.ListValue(values.map { value -> if (value.isFinite()) DieselValue.Decimal(value.toDouble()) else DieselValue.Null })
                }
                SensorReadResult.Unavailable -> { fields["status"] = DieselValue.Text("unavailable") }
                is SensorReadResult.PermissionDenied -> {
                    fields["status"] = DieselValue.Text("ok")
                    fields["outcome"] = DieselValue.Text("permission_denied")
                    fields["requiredPermission"] = result.requiredPermission?.let(DieselValue::Text) ?: DieselValue.Null
                }
                SensorReadResult.Timeout -> { fields["status"] = DieselValue.Text("ok"); fields["outcome"] = DieselValue.Text("timeout") }
                is SensorReadResult.RegistrationRejected -> { fields["status"] = DieselValue.Text("ok"); fields["outcome"] = DieselValue.Text("registration_rejected"); fields["reason"] = DieselValue.Text(result.reason ?: "unknown") }
            }
            DieselValue.ObjectValue(fields)
        }
        return DieselCommandResult.ok(mapOf("results" to DieselValue.ListValue(results)))
    }

    private fun encodeReading(reading: org.aaustralian.dieselbridge.platform.sensor.SensorReading): DieselCommandResult {
        val values = reading.values.take(MAX_READ_VALUES)
        val nonFinite = linkedMapOf<String, MutableList<DieselValue>>()
        val encoded = values.mapIndexed { index, value ->
            if (value.isFinite()) {
                DieselValue.Decimal(value.toDouble())
            } else {
                val category = when {
                    value.isNaN() -> "nan"
                    value > 0 -> "positiveInfinity"
                    else -> "negativeInfinity"
                }
                nonFinite.getOrPut(category) { mutableListOf() }.add(DieselValue.Integer(index.toLong()))
                DieselValue.Null
            }
        }
        val data = linkedMapOf<String, DieselValue>(
            "outcome" to DieselValue.Text("event"),
            "capability" to DieselValue.Text(reading.capabilityId.value),
            "providerId" to DieselValue.Text(reading.providerId),
            "accuracy" to (reading.accuracy?.let { DieselValue.Integer(it.toLong()) } ?: DieselValue.Null),
            "sensorTimestampNs" to DieselValue.Integer(reading.timestampNanos),
            "timeToEventMs" to DieselValue.Integer(reading.elapsedMs),
            "valueCount" to DieselValue.Integer(reading.values.size.toLong()),
            "returnedValueCount" to DieselValue.Integer(values.size.toLong()),
            "valuesTruncated" to DieselValue.Flag(reading.values.size > values.size),
            "values" to DieselValue.ListValue(encoded),
        )
        if (nonFinite.isNotEmpty()) {
            data["nonFinite"] = DieselValue.ObjectValue(nonFinite.mapValues { DieselValue.ListValue(it.value) })
        }
        return DieselCommandResult.ok(data)
    }

    private fun invalidReadArguments(): DieselCommandResult =
        DieselCommandResult(
            DieselResponseStatus.INVALID_REQUEST,
            mapOf("reason" to DieselValue.Text("invalid_args")),
        )

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

    private fun longArgument(context: DieselCommandContext, key: String, defaultValue: Long, minimum: Long, maximum: Long): Long? {
        val value = context.args[key] ?: return defaultValue
        val integer = (value as? DieselValue.Integer)?.value ?: return null
        return integer.takeIf { it in minimum..maximum }
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
        private val CANONICAL_LOGICAL_IDS =
            SensorCapabilityCatalog.standardLogicalIds.toSet()

        const val COMMAND_SENSOR_LIST =
            "sensor.list"

        const val COMMAND_SENSOR_READ =
            "sensor.read"

        const val COMMAND_SENSOR_MATRIX =
            "sensor.matrix"

        const val COMMAND_SENSOR_EXPERIMENT =
            "sensor.experiment"

        const val ARG_OFFSET =
            "offset"

        const val ARG_LIMIT =
            "limit"

        const val ARG_TIMEOUT_MS =
            "timeoutMs"

        const val ARG_ROUNDS =
            "rounds"

        const val ARG_TOTAL_TIMEOUT_MS =
            "totalTimeoutMs"

        const val MAX_READ_VALUES = 64

        // Keeps the eight-target matrix safely below the 4 KiB protocol limit.
        const val MAX_MATRIX_VALUES = 16

        const val MAX_EXPERIMENT_VALUES = 8
        const val MAX_EXPERIMENT_SAMPLES = 16

        const val DEFAULT_PAGE_SIZE =
            4

        const val MAX_PAGE_SIZE =
            4

        private const val MAX_REMOTE_TEXT_CODE_POINTS =
            48

        private const val SOURCE_ANDROID_SENSOR_MANAGER =
            "android.sensor_manager"

        private val EXPERIMENT_ARGUMENTS = setOf(ARG_ROUNDS, ARG_TIMEOUT_MS, ARG_TOTAL_TIMEOUT_MS)

        private val ALLOWED_ARGUMENTS =
            setOf(
                ARG_OFFSET,
                ARG_LIMIT,
            )
    }
}
