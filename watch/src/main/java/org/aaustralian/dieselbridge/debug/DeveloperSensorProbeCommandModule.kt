// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlin.coroutines.cancellation.CancellationException
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRoute
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorSampler
import org.aaustralian.dieselbridge.platform.sensor.BoundedSensorSampleOutcome
import org.aaustralian.dieselbridge.platform.sensor.SensorRawEvent
import org.aaustralian.dieselbridge.platform.sensor.SensorRegistrationKind
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteId
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbe
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbeOutcome
import org.aaustralian.dieselbridge.protocol.DieselCommandContext
import org.aaustralian.dieselbridge.protocol.DieselCommandModule
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselResponse
import org.aaustralian.dieselbridge.protocol.DieselResponseCodec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue

/**
 * Developer-only first-event experiments on exact routes, not public sensor routing.
 * Validation and local authorization precede fresh resolution and hardware activation.
 * The platform keeps raw typed evidence; only this boundary handles JSON-safe values.
 */
internal class DeveloperSensorProbeCommandModule(
    private val authorization: DeveloperRemoteAccessAuthorization,
    private val probe: SensorRouteProbe,
) : DieselCommandModule {
    override fun install(registry: DieselCommandRegistry) {
        registry.register(
            DieselCommandSpec(
                name = COMMAND_SENSOR_PROBE,
                summary = "Probe one exact sensor route with a bounded first-event sample",
                metadata = mapOf(
                    "domain" to DieselValue.Text("debug"),
                    "effect" to DieselValue.Text(DeveloperCommandEffect.SAFE_ACTION.wireName),
                    "authorization" to DieselValue.Text("local_developer_ui"),
                    "target" to DieselValue.Text("exact_sensor_route"),
                    "bounded" to DieselValue.Flag(true),
                ),
            ),
        ) { context -> probeSensor(context) }
    }

    private suspend fun probeSensor(context: DieselCommandContext): DieselCommandResult {
        if (context.name != null || context.args.keys.any { it !in ALLOWED_ARGUMENTS }) {
            return errorResult(DieselResponseStatus.INVALID_REQUEST, "invalid_args")
        }
        val routeText = (context.args[ARG_ROUTE_ID] as? DieselValue.Text)?.value
            ?: return errorResult(DieselResponseStatus.INVALID_REQUEST, "invalid_args")
        val routeId = try {
            // Route IDs remain opaque: validate their type contract, not a provider-specific grammar.
            SensorRouteId(routeText)
        } catch (_: IllegalArgumentException) {
            return errorResult(DieselResponseStatus.INVALID_REQUEST, "invalid_args")
        }
        val timeoutMs = if (ARG_TIMEOUT_MS in context.args) {
            (context.args[ARG_TIMEOUT_MS] as? DieselValue.Integer)?.value
                ?: return errorResult(DieselResponseStatus.INVALID_REQUEST, "invalid_args")
        } else {
            AndroidSensorSampler.DEFAULT_TIMEOUT_MS
        }
        if (timeoutMs !in AndroidSensorSampler.MIN_TIMEOUT_MS..AndroidSensorSampler.MAX_TIMEOUT_MS) {
            return errorResult(DieselResponseStatus.INVALID_REQUEST, "invalid_args")
        }
        if (!authorization.isEnabled()) {
            return errorResult(DieselResponseStatus.UNAVAILABLE, "remote_developer_access_disabled")
        }

        return try {
            when (val result = probe.probe(routeId, timeoutMs)) {
                SensorRouteProbeOutcome.RouteUnavailable -> DieselCommandResult.ok(
                    mapOf(
                        "outcome" to DieselValue.Text("route_unavailable"),
                        "routeId" to DieselValue.Text(routeId.value),
                    ),
                )
                is SensorRouteProbeOutcome.Sample -> boundedSampleResult(context, result, timeoutMs)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            // Resolution itself may be denied before route metadata is available.
            DieselCommandResult.ok(
                mapOf(
                    "outcome" to DieselValue.Text("permission_denied"),
                    "routeId" to DieselValue.Text(routeId.value),
                    "requiredPermission" to DieselValue.Null,
                ),
            )
        } catch (_: Exception) {
            errorResult(DieselResponseStatus.FAILED, "probe_failed")
        }
    }

    /** Shrink display metadata if needed, never identities, raw values or non-finite annotations. */
    private fun boundedSampleResult(
        context: DieselCommandContext,
        sample: SensorRouteProbeOutcome.Sample,
        timeoutMs: Long,
    ): DieselCommandResult {
        for (metadataLimit in listOf(64, 48, 32, 24, 0)) {
            val result = sampleResult(sample.route, sample.outcome, timeoutMs, metadataLimit)
            val envelope = DieselResponse(
                requestId = context.request.requestId,
                command = context.command,
                name = context.name,
                status = result.status,
                data = result.data,
                version = context.request.version,
            )
            if (DieselResponseCodec.encodeResponseJson(envelope).toByteArray(Charsets.UTF_8).size <=
                DieselResponseCodec.MAX_RESPONSE_JSON_BYTES
            ) {
                return result
            }
        }
        // A broken future backend must not enqueue an oversized response.
        return errorResult(DieselResponseStatus.FAILED, "probe_failed")
    }

    private fun sampleResult(
        route: AndroidSensorRoute,
        sample: BoundedSensorSampleOutcome,
        timeoutMs: Long,
        metadataLimit: Int,
    ): DieselCommandResult {
        val sensor = route.inventory
        val descriptor = route.descriptor
        val name = compactText(sensor.name, metadataLimit)
        val vendor = compactText(sensor.vendor, metadataLimit)
        val stringType = compactText(sensor.stringType, metadataLimit)
        val permission = (sample as? BoundedSensorSampleOutcome.PermissionDenied)?.requiredPermission
        val routeData = linkedMapOf(
            "routeId" to DieselValue.Text(descriptor.routeId.value),
            "providerId" to DieselValue.Text(descriptor.providerId),
            "logicalId" to DieselValue.Text(descriptor.logicalId.value),
            "androidId" to integer(sensor.androidId.toLong()),
            "androidType" to integer(sensor.androidType.toLong()),
            "stringType" to DieselValue.Text(stringType),
            "name" to DieselValue.Text(name),
            "vendor" to DieselValue.Text(vendor),
            // Android's public Sensor API does not expose requiredPermission here.
            "requiredPermission" to (permission?.let { DieselValue.Text(compactText(it)) } ?: DieselValue.Null),
            "reportingMode" to integer(sensor.reportingMode.toLong()),
            "wakeUp" to DieselValue.Flag(sensor.wakeUp),
            "metadataTruncated" to DieselValue.Flag(
                name != sensor.name || vendor != sensor.vendor || stringType != sensor.stringType ||
                    (permission != null && compactText(permission) != permission),
            ),
        )
        val data = linkedMapOf<String, DieselValue>(
            "outcome" to DieselValue.Text(outcomeName(sample)),
            "route" to DieselValue.ObjectValue(routeData),
            "registration" to DieselValue.ObjectValue(
                mapOf(
                    "kind" to DieselValue.Text(
                        if (sample.registrationKind == SensorRegistrationKind.TRIGGER) "trigger" else "listener",
                    ),
                    "timeoutMs" to integer(timeoutMs),
                    "samplingPeriodUs" to if (sample.registrationKind == SensorRegistrationKind.TRIGGER) {
                        DieselValue.Null
                    } else {
                        integer(AndroidSensorSampler.DEFAULT_SAMPLE_PERIOD_US.toLong())
                    },
                    "elapsedMs" to integer(sample.elapsedMs),
                ),
            ),
        )
        when (sample) {
            is BoundedSensorSampleOutcome.Event -> data["event"] = encodeEvent(sample.event, sample.elapsedMs)
            is BoundedSensorSampleOutcome.RegistrationRejected -> sample.reason?.let {
                data["reason"] = DieselValue.Text(compactText(it))
            }
            else -> Unit
        }
        return DieselCommandResult.ok(data)
    }

    private fun encodeEvent(event: SensorRawEvent, elapsedMs: Long): DieselValue.ObjectValue {
        val raw = event.values.take(MAX_EVENT_VALUES)
        val nonFinite = linkedMapOf<String, MutableList<DieselValue>>()
        val values = raw.mapIndexed { index, value ->
            if (value.isFinite()) {
                DieselValue.Decimal(value.toDouble())
            } else {
                val category = when {
                    value.isNaN() -> "nan"
                    value > 0 -> "positiveInfinity"
                    else -> "negativeInfinity"
                }
                nonFinite.getOrPut(category) { mutableListOf() }.add(integer(index.toLong()))
                DieselValue.Null
            }
        }
        val data = linkedMapOf<String, DieselValue>(
            "accuracy" to (event.accuracy?.let { integer(it.toLong()) } ?: DieselValue.Null),
            "sensorTimestampNs" to integer(event.timestampNanos),
            "timeToEventMs" to integer(elapsedMs),
            "sampleCount" to integer(1),
            "valueCount" to integer(event.values.size.toLong()),
            "returnedValueCount" to integer(raw.size.toLong()),
            "valuesTruncated" to DieselValue.Flag(event.values.size > raw.size),
            "values" to DieselValue.ListValue(values),
        )
        if (nonFinite.isNotEmpty()) {
            data["nonFinite"] = DieselValue.ObjectValue(nonFinite.mapValues { DieselValue.ListValue(it.value) })
        }
        return DieselValue.ObjectValue(data)
    }

    private fun outcomeName(sample: BoundedSensorSampleOutcome): String = when (sample) {
        is BoundedSensorSampleOutcome.Event -> "event"
        is BoundedSensorSampleOutcome.Timeout -> "timeout"
        is BoundedSensorSampleOutcome.PermissionDenied -> "permission_denied"
        is BoundedSensorSampleOutcome.RegistrationRejected -> "registration_rejected"
    }

    /** Bound vendor metadata by code points; normalize controls without splitting surrogate pairs. */
    private fun compactText(value: String, limit: Int = 64): String {
        val output = StringBuilder()
        var offset = 0
        var count = 0
        while (offset < value.length && count < limit) {
            val point = value.codePointAt(offset)
            if (Character.isISOControl(point)) output.append(' ') else output.appendCodePoint(point)
            offset += Character.charCount(point)
            count++
        }
        return output.toString()
    }

    private fun integer(value: Long) = DieselValue.Integer(value)

    private fun errorResult(status: DieselResponseStatus, reason: String) =
        DieselCommandResult(status, mapOf("reason" to DieselValue.Text(reason)))

    companion object {
        const val COMMAND_SENSOR_PROBE = "debug.sensor.probe"
        const val ARG_ROUTE_ID = "routeId"
        const val ARG_TIMEOUT_MS = "timeoutMs"
        const val MAX_EVENT_VALUES = 64
        private val ALLOWED_ARGUMENTS = setOf(ARG_ROUTE_ID, ARG_TIMEOUT_MS)
    }
}
