// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.debug

import kotlinx.coroutines.CancellationException
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbe
import org.aaustralian.dieselbridge.protocol.*

/** Developer-only asynchronous whole-watch route scan controls. */
internal class DeveloperSensorScanCommandModule(
    private val authorization: DeveloperRemoteAccessAuthorization,
    private val runner: SensorScanRunner,
    private val store: SensorProbeStore,
) : DieselCommandModule {
    override fun install(registry: DieselCommandRegistry) {
        registry.register(spec(COMMAND_START, "Start a background bounded whole-watch sensor scan")) { context -> start(context) }
        registry.register(spec(COMMAND_STATUS, "Show whole-watch sensor scan progress")) { context -> status(context) }
        registry.register(spec(COMMAND_CANCEL, "Cancel a running whole-watch sensor scan")) { context -> cancel(context) }
    }

    private suspend fun start(context: DieselCommandContext): DieselCommandResult {
        if (!authorized(context) || context.name != null || context.args.isNotEmpty()) return invalid()
        val runId = runner.start() ?: return DieselCommandResult(DieselResponseStatus.RATE_LIMITED, mapOf("reason" to DieselValue.Text("sensor_scan_running")))
        return DieselCommandResult.ok(mapOf("runId" to DieselValue.Text(runId), "state" to DieselValue.Text("running")))
    }

    private fun status(context: DieselCommandContext): DieselCommandResult {
        if (!authorized(context) || context.name != null || context.args.keys.any { it != ARG_RUN_ID }) return invalid()
        val runId = (context.args[ARG_RUN_ID] as? DieselValue.Text)?.value
        val state = runner.state()
        if (runId != null && state is SensorScanState.Running && state.runId != runId) return missing()
        return DieselCommandResult.ok(stateFields(state, runId))
    }

    private suspend fun cancel(context: DieselCommandContext): DieselCommandResult {
        if (!authorized(context) || context.name != null || context.args.keys != setOf(ARG_RUN_ID)) return invalid()
        val runId = (context.args[ARG_RUN_ID] as? DieselValue.Text)?.value ?: return invalid()
        return if (runner.cancel(runId)) DieselCommandResult.ok(mapOf("runId" to DieselValue.Text(runId), "state" to DieselValue.Text("cancelled"))) else missing()
    }

    private fun stateFields(state: SensorScanState, requested: String?): Map<String, DieselValue> {
        val data = linkedMapOf<String, DieselValue>()
        when (state) {
            SensorScanState.Idle -> data["state"] = DieselValue.Text("idle")
            is SensorScanState.Running -> { data["runId"] = DieselValue.Text(state.runId); data["state"] = DieselValue.Text("running"); data["totalRoutes"] = DieselValue.Integer(state.total.toLong()); data["completedRoutes"] = DieselValue.Integer(state.completed.toLong()) }
            is SensorScanState.Finished -> { data["runId"] = DieselValue.Text(state.runId); data["state"] = DieselValue.Text(if (state.cancelled) "cancelled" else "finished"); data["totalRoutes"] = DieselValue.Integer(state.total.toLong()); data["completedRoutes"] = DieselValue.Integer(state.completed.toLong()) }
        }
        val id = (state as? SensorScanState.Running)?.runId ?: (state as? SensorScanState.Finished)?.runId ?: requested
        val records = store.snapshot(id)
        data["eventCount"] = DieselValue.Integer(records.count { it.outcome == "event" }.toLong())
        data["timeoutCount"] = DieselValue.Integer(records.count { it.outcome == "timeout" }.toLong())
        data["permissionDeniedCount"] = DieselValue.Integer(records.count { it.outcome == "permission_denied" }.toLong())
        data["recordCount"] = DieselValue.Integer(records.size.toLong())
        return data
    }

    private fun authorized(context: DieselCommandContext) = authorization.isEnabled()
    private fun invalid() = DieselCommandResult(DieselResponseStatus.INVALID_REQUEST, mapOf("reason" to DieselValue.Text("invalid_args")))
    private fun missing() = DieselCommandResult(DieselResponseStatus.UNAVAILABLE, mapOf("reason" to DieselValue.Text("sensor_scan_not_found")))
    private fun spec(name: String, summary: String) = DieselCommandSpec(name, summary, mapOf("domain" to DieselValue.Text("debug"), "effect" to DieselValue.Text("read_only"), "authorization" to DieselValue.Text("local_developer_ui"), "bounded" to DieselValue.Flag(true)))

    companion object { const val COMMAND_START = "debug.sensor.scan.start"; const val COMMAND_STATUS = "debug.sensor.scan.status"; const val COMMAND_CANCEL = "debug.sensor.scan.cancel"; const val ARG_RUN_ID = "runId" }
}
