// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRoute
import org.aaustralian.dieselbridge.platform.sensor.BoundedSensorSampleOutcome
import org.aaustralian.dieselbridge.platform.sensor.SensorRawEvent
import org.aaustralian.dieselbridge.platform.sensor.SensorRegistrationKind
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbe
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbeOutcome

internal enum class SensorScanTerminalReason { FINISHED, CANCELLED, TIME_BUDGET_EXHAUSTED }

internal data class SensorScanSummary(
    val runId: String,
    val totalRoutes: Int,
    val completedRoutes: Int,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val terminalReason: SensorScanTerminalReason? = null,
)

internal data class SensorProbeRecord(
    val runId: String,
    val index: Int,
    val route: AndroidSensorRoute,
    val outcome: String,
    val registrationKind: SensorRegistrationKind?,
    val elapsedMs: Long,
    val event: SensorRawEvent? = null,
    val requiredPermission: String? = null,
    val reason: String? = null,
)

/** Bounded process-local evidence store for whole-watch route scans. */
class SensorProbeStore(private val capacity: Int = 256, private val runCapacity: Int = 8) {
    private val lock = Any()
    private val records = ArrayDeque<SensorProbeRecord>()
    private val summaries = LinkedHashMap<String, SensorScanSummary>()

    init { require(capacity > 0); require(runCapacity > 0) }

    internal fun begin(summary: SensorScanSummary) = synchronized(lock) {
        while (summaries.size >= runCapacity) summaries.remove(summaries.entries.first().key)
        summaries[summary.runId] = summary
    }

    internal fun update(summary: SensorScanSummary) = synchronized(lock) { summaries[summary.runId] = summary }

    internal fun add(record: SensorProbeRecord) = synchronized(lock) {
        if (records.size >= capacity) records.removeFirst()
        records.addLast(record)
    }

    internal fun summary(runId: String): SensorScanSummary? = synchronized(lock) { summaries[runId] }
    internal fun latestSummary(): SensorScanSummary? = synchronized(lock) { summaries.values.lastOrNull() }
    internal fun snapshot(runId: String? = null): List<SensorProbeRecord> = synchronized(lock) {
        records.filter { runId == null || it.runId == runId }
    }
}

/** Single-flight, bounded and cancellable route scanner. */
internal class SensorScanRunner(
    private val probe: SensorRouteProbe,
    private val routes: () -> List<AndroidSensorRoute>,
    private val store: SensorProbeStore,
    private val scope: CoroutineScope,
    private val perRouteTimeoutMs: Long = DEFAULT_ROUTE_TIMEOUT_MS,
    private val totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    @Volatile private var activeRunId: String? = null
    @Volatile private var activeJob: Job? = null

    fun summary(runId: String? = null): SensorScanSummary? =
        runId?.let(store::summary) ?: store.latestSummary()

    suspend fun start(): String? = lock.withLock {
        if (activeJob?.isActive == true) return@withLock null
        val runId = "scan-" + UUID.randomUUID()
        val snapshot = routes()
        val startedAt = clockMs()
        val initial = SensorScanSummary(runId, snapshot.size, 0, startedAt)
        store.begin(initial)
        activeRunId = runId
        activeJob = scope.launch {
            var completed = 0
            var terminalReason = SensorScanTerminalReason.FINISHED
            try {
                for ((index, route) in snapshot.withIndex()) {
                    if (!isActive) throw CancellationException()
                    val elapsedTotal = clockMs() - startedAt
                    val remaining = totalTimeoutMs - elapsedTotal
                    if (remaining <= 0L) {
                        terminalReason = SensorScanTerminalReason.TIME_BUDGET_EXHAUSTED
                        break
                    }
                    val outcome = withTimeoutOrNull(minOf(perRouteTimeoutMs, remaining)) {
                        probe.probe(route.descriptor.routeId, minOf(perRouteTimeoutMs, remaining))
                    }
                    store.add(record(runId, index, route, outcome))
                    completed++
                    store.update(SensorScanSummary(runId, snapshot.size, completed, startedAt))
                }
            } catch (_: CancellationException) {
                terminalReason = SensorScanTerminalReason.CANCELLED
                throw CancellationException()
            } finally {
                store.update(SensorScanSummary(runId, snapshot.size, completed, startedAt, clockMs(), terminalReason))
                activeJob = null
                activeRunId = null
            }
        }
        runId
    }

    suspend fun cancel(runId: String): Boolean = lock.withLock {
        if (activeRunId != runId || activeJob?.isActive != true) return@withLock false
        activeJob?.cancel()
        true
    }

    private fun record(
        runId: String,
        index: Int,
        route: AndroidSensorRoute,
        result: SensorRouteProbeOutcome?,
    ): SensorProbeRecord = when (result) {
        null -> SensorProbeRecord(runId, index, route, "timeout", null, perRouteTimeoutMs)
        SensorRouteProbeOutcome.RouteUnavailable -> SensorProbeRecord(runId, index, route, "route_unavailable", null, 0L)
        is SensorRouteProbeOutcome.Sample -> when (val sample = result.outcome) {
            is BoundedSensorSampleOutcome.Event -> SensorProbeRecord(runId, index, result.route, "event", sample.registrationKind, sample.elapsedMs, event = sample.event)
            is BoundedSensorSampleOutcome.Timeout -> SensorProbeRecord(runId, index, result.route, "timeout", sample.registrationKind, sample.elapsedMs)
            is BoundedSensorSampleOutcome.PermissionDenied -> SensorProbeRecord(runId, index, result.route, "permission_denied", sample.registrationKind, sample.elapsedMs, requiredPermission = sample.requiredPermission)
            is BoundedSensorSampleOutcome.RegistrationRejected -> SensorProbeRecord(runId, index, result.route, "registration_rejected", sample.registrationKind, sample.elapsedMs, reason = sample.reason)
        }
    }

    companion object {
        const val DEFAULT_ROUTE_TIMEOUT_MS = 2_000L
        const val DEFAULT_TOTAL_TIMEOUT_MS = 60_000L
    }
}
