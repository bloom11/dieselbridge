// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.sensor.SensorReadOptions
import org.aaustralian.dieselbridge.platform.sensor.SensorReadResult

/** A bounded, repeatable logical-sensor experiment definition. */
data class SensorExperimentSpec(
    val id: String = "standard_matrix",
    val targets: List<String> = SensorReadCoordinator.standardTargets,
    val rounds: Int = 1,
    val timeoutMs: Long = SensorReadOptions().timeoutMs,
    val totalTimeoutMs: Long = SensorExperimentRunner.DEFAULT_TOTAL_TIMEOUT_MS,
) {
    init {
        require(id.isNotBlank())
        require(targets.isNotEmpty() && targets.distinct().size == targets.size)
        require(rounds in 1..SensorExperimentRunner.MAX_ROUNDS)
        require(timeoutMs in SensorExperimentRunner.MIN_TIMEOUT_MS..SensorExperimentRunner.MAX_TIMEOUT_MS)
        require(totalTimeoutMs in SensorExperimentRunner.MIN_TOTAL_TIMEOUT_MS..SensorExperimentRunner.MAX_TOTAL_TIMEOUT_MS)
    }
}

data class SensorExperimentSample(
    val round: Int,
    val target: String,
    val elapsedMs: Long,
    val result: SensorReadResult,
)

data class SensorExperimentResult(
    val spec: SensorExperimentSpec,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val samples: List<SensorExperimentSample>,
) {
    val roundsCompleted: Int = samples.groupBy { it.round }.count { (_, roundSamples) ->
        roundSamples.size == spec.targets.size && roundSamples.none { it.elapsedMs == 0L && it.result is SensorReadResult.Timeout }
    }
    val eventCount: Int = samples.count { it.result is SensorReadResult.Event }
    val unavailableCount: Int = samples.count { it.result is SensorReadResult.Unavailable }
    val timeoutCount: Int = samples.count { it.result is SensorReadResult.Timeout }
    val nonEventCount: Int = samples.size - eventCount - unavailableCount - timeoutCount
}

/** Serializes bounded experiments so two runs cannot activate sensors concurrently. */
class SensorExperimentRunner(
    private val capabilities: CapabilityRegistry,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    suspend fun run(spec: SensorExperimentSpec = SensorExperimentSpec()): SensorExperimentResult = mutex.withLock {
        val startedAt = clockMs()
        val startedNanos = System.nanoTime()
        val samples = mutableListOf<SensorExperimentSample>()
        for (round in 1..spec.rounds) {
            for (target in spec.targets) {
                val elapsedTotal = (System.nanoTime() - startedNanos) / 1_000_000L
                val remaining = spec.totalTimeoutMs - elapsedTotal
                if (remaining <= 0L) {
                    samples += SensorExperimentSample(round, target, 0L, SensorReadResult.Timeout)
                    continue
                }
                val readStarted = System.nanoTime()
                val result = withTimeoutOrNull(remaining.coerceAtMost(spec.timeoutMs)) {
                    SensorReadCoordinator.read(capabilities, target, SensorReadOptions(spec.timeoutMs))
                } ?: SensorReadResult.Timeout
                samples += SensorExperimentSample(
                    round = round,
                    target = target,
                    elapsedMs = (System.nanoTime() - readStarted) / 1_000_000L,
                    result = result,
                )
            }
        }
        SensorExperimentResult(spec, startedAt, clockMs(), samples)
    }

    companion object {
        const val MAX_ROUNDS = 8
        const val MIN_TIMEOUT_MS = 500L
        const val MAX_TIMEOUT_MS = 15_000L
        const val MIN_TOTAL_TIMEOUT_MS = 1_000L
        const val MAX_TOTAL_TIMEOUT_MS = 30_000L
        const val DEFAULT_TOTAL_TIMEOUT_MS = 30_000L
    }
}
