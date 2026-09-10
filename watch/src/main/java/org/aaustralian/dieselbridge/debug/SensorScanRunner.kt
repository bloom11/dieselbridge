// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.debug

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRoute
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbe
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbeOutcome

sealed interface SensorScanState {
    data object Idle : SensorScanState
    data class Running(val runId: String, val total: Int, val completed: Int) : SensorScanState
    data class Finished(val runId: String, val completed: Int, val total: Int, val cancelled: Boolean) : SensorScanState
}

data class SensorProbeRecord(
    val runId: String,
    val index: Int,
    val route: AndroidSensorRoute?,
    val outcome: String,
    val elapsedMs: Long,
    val reason: String? = null,
)

/** Bounded process-local evidence store for whole-watch route scans. */
class SensorProbeStore(private val capacity: Int = 256) {
    private val lock = Any()
    private val records = ArrayDeque<SensorProbeRecord>()
    fun add(record: SensorProbeRecord) = synchronized(lock) {
        if (records.size == capacity) records.removeFirst()
        records.addLast(record)
    }
    fun snapshot(runId: String? = null): List<SensorProbeRecord> = synchronized(lock) {
        records.filter { runId == null || it.runId == runId }
    }
    fun clear() = synchronized(lock) { records.clear() }
}

/** Single-flight, cancellable route scanner. It never fan-outs sensor activation. */
class SensorScanRunner(
    private val probe: SensorRouteProbe,
    private val routes: () -> List<AndroidSensorRoute>,
    private val store: SensorProbeStore,
    private val scope: CoroutineScope,
    private val perRouteTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val lock = Mutex()
    @Volatile private var state: SensorScanState = SensorScanState.Idle
    @Volatile private var activeJob: Job? = null

    fun state(): SensorScanState = state

    suspend fun start(): String? = lock.withLock {
        if (activeJob?.isActive == true) return@withLock null
        val runId = "scan-" + UUID.randomUUID().toString()
        val snapshot = routes()
        state = SensorScanState.Running(runId, snapshot.size, 0)
        activeJob = scope.launch {
            try {
                snapshot.forEachIndexed { index, route ->
                    if (!isActive) return@launch
                    val started = System.nanoTime()
                    val result = withTimeoutOrNull(perRouteTimeoutMs) { probe.probe(route.descriptor.routeId, perRouteTimeoutMs) }
                    val elapsed = (System.nanoTime() - started) / 1_000_000L
                    val outcome = when (result) {
                        null -> "timeout"
                        SensorRouteProbeOutcome.RouteUnavailable -> "route_unavailable"
                        is SensorRouteProbeOutcome.Sample -> outcomeName(result.outcome)
                    }
                    val reason = (result as? SensorRouteProbeOutcome.Sample)?.outcome
                        ?.let { if (it is org.aaustralian.dieselbridge.platform.sensor.BoundedSensorSampleOutcome.RegistrationRejected) it.reason else null }
                    store.add(SensorProbeRecord(runId, index, route, outcome, elapsed, reason))
                    state = SensorScanState.Running(runId, snapshot.size, index + 1)
                }
                state = SensorScanState.Finished(runId, snapshot.size, snapshot.size, !isActive)
            } finally { activeJob = null }
        }
        runId
    }

    suspend fun cancel(runId: String): Boolean = lock.withLock {
        if ((state as? SensorScanState.Running)?.runId != runId) return@withLock false
        activeJob?.cancel()
        state = SensorScanState.Finished(runId, store.snapshot(runId).size, (state as SensorScanState.Running).total, true)
        true
    }

    private fun outcomeName(outcome: org.aaustralian.dieselbridge.platform.sensor.BoundedSensorSampleOutcome) = when (outcome) {
        is org.aaustralian.dieselbridge.platform.sensor.BoundedSensorSampleOutcome.Event -> "event"
        is org.aaustralian.dieselbridge.platform.sensor.BoundedSensorSampleOutcome.Timeout -> "timeout"
        is org.aaustralian.dieselbridge.platform.sensor.BoundedSensorSampleOutcome.PermissionDenied -> "permission_denied"
        is org.aaustralian.dieselbridge.platform.sensor.BoundedSensorSampleOutcome.RegistrationRejected -> "registration_rejected"
    }

    companion object { const val DEFAULT_TIMEOUT_MS = 2_000L }
}
