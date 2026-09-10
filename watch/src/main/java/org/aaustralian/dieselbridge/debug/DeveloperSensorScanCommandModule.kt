// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.protocol.DieselCommandContext
import org.aaustralian.dieselbridge.protocol.DieselCommandModule
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue

/** Developer-only asynchronous whole-watch route scan controls. */
internal class DeveloperSensorScanCommandModule(
    private val authorization: DeveloperRemoteAccessAuthorization,
    private val runner: SensorScanRunner,
    private val store: SensorProbeStore,
) : DieselCommandModule {
    override fun install(registry: DieselCommandRegistry) {
        registry.register(spec(COMMAND_START, "Start a bounded background whole-watch sensor scan", emptyMap())) { start(it) }
        registry.register(spec(COMMAND_STATUS, "Show a sensor scan's progress or final result", mapOf(ARG_RUN_ID to "optional scan id"))) { status(it) }
        registry.register(spec(COMMAND_CANCEL, "Cancel one running whole-watch sensor scan", mapOf(ARG_RUN_ID to "required scan id"))) { cancel(it) }
    }

    private suspend fun start(context: DieselCommandContext): DieselCommandResult {
        if (!valid(context, emptySet())) return invalid()
        val runId = runner.start() ?: return DieselCommandResult(DieselResponseStatus.RATE_LIMITED, mapOf("reason" to DieselValue.Text("sensor_scan_running")))
        return DieselCommandResult.ok(mapOf("runId" to DieselValue.Text(runId), "state" to DieselValue.Text("running")))
    }

    private fun status(context: DieselCommandContext): DieselCommandResult {
        if (!valid(context, setOf(ARG_RUN_ID))) return invalid()
        if (ARG_RUN_ID in context.args && textArgument(context, ARG_RUN_ID) == null) return invalid()
        val summary = runner.summary(textArgument(context, ARG_RUN_ID)) ?: return missing()
        return DieselCommandResult.ok(summaryFields(summary))
    }

    private suspend fun cancel(context: DieselCommandContext): DieselCommandResult {
        if (!valid(context, setOf(ARG_RUN_ID))) return invalid()
        val runId = textArgument(context, ARG_RUN_ID) ?: return invalid()
        return if (runner.cancel(runId)) {
            DieselCommandResult.ok(mapOf("runId" to DieselValue.Text(runId), "state" to DieselValue.Text("cancelling")))
        } else {
            missing()
        }
    }

    private fun summaryFields(summary: SensorScanSummary): Map<String, DieselValue> {
        val records = store.snapshot(summary.runId)
        val state = when (summary.terminalReason) {
            null -> "running"
            SensorScanTerminalReason.FINISHED -> "finished"
            SensorScanTerminalReason.CANCELLED -> "cancelled"
            SensorScanTerminalReason.TIME_BUDGET_EXHAUSTED -> "time_budget_exhausted"
        }
        return linkedMapOf(
            "runId" to DieselValue.Text(summary.runId),
            "state" to DieselValue.Text(state),
            "totalRoutes" to DieselValue.Integer(summary.totalRoutes.toLong()),
            "completedRoutes" to DieselValue.Integer(summary.completedRoutes.toLong()),
            "startedAtMs" to DieselValue.Integer(summary.startedAtMs),
            "finishedAtMs" to (summary.finishedAtMs?.let(DieselValue::Integer) ?: DieselValue.Null),
            "recordCount" to DieselValue.Integer(records.size.toLong()),
            "eventCount" to DieselValue.Integer(records.count { it.outcome == "event" }.toLong()),
            "timeoutCount" to DieselValue.Integer(records.count { it.outcome == "timeout" }.toLong()),
            "permissionDeniedCount" to DieselValue.Integer(records.count { it.outcome == "permission_denied" }.toLong()),
        )
    }

    private fun valid(context: DieselCommandContext, allowed: Set<String>) =
        authorization.isEnabled() && context.name == null && context.args.keys.all { it in allowed }

    private fun textArgument(context: DieselCommandContext, key: String): String? =
        (context.args[key] as? DieselValue.Text)?.value

    private fun invalid() = DieselCommandResult(DieselResponseStatus.INVALID_REQUEST, mapOf("reason" to DieselValue.Text("invalid_args")))
    private fun missing() = DieselCommandResult(DieselResponseStatus.UNAVAILABLE, mapOf("reason" to DieselValue.Text("sensor_scan_not_found")))

    private fun spec(name: String, summary: String, arguments: Map<String, String>) = DieselCommandSpec(
        name = name,
        summary = summary,
        metadata = mapOf(
            "domain" to DieselValue.Text("debug"),
            "effect" to DieselValue.Text("safe_action"),
            "authorization" to DieselValue.Text("local_developer_ui"),
            "bounded" to DieselValue.Text("one active scan; 2s per route; 60s total; 256 records"),
            "arguments" to DieselValue.ObjectValue(arguments.mapValues { DieselValue.Text(it.value) }),
        ),
    )

    companion object {
        const val COMMAND_START = "debug.sensor.scan.start"
        const val COMMAND_STATUS = "debug.sensor.scan.status"
        const val COMMAND_CANCEL = "debug.sensor.scan.cancel"
        const val ARG_RUN_ID = "runId"
    }
}
