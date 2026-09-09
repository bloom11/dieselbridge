// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import kotlinx.coroutines.withTimeoutOrNull
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.sensor.SensorCapability
import org.aaustralian.dieselbridge.platform.sensor.SensorReadOptions
import org.aaustralian.dieselbridge.platform.sensor.SensorReadResult

/** Shared logical sensor resolution used by local UI and Diesel command transports. */
object SensorReadCoordinator {
    val standardTargets: List<String> = listOf(
        "accelerometer",
        "gyroscope",
        "magnetic_field",
        "light",
        "pressure",
        "ambient_temperature",
        "heart_rate",
        "step_counter",
    )

    suspend fun read(
        registry: CapabilityRegistry,
        target: String,
        options: SensorReadOptions,
    ): SensorReadResult =
        registry.resolveAs<SensorCapability>("sensor.$target")?.read(options)
            ?: SensorReadResult.Unavailable

    suspend fun matrix(
        registry: CapabilityRegistry,
        options: SensorReadOptions,
        totalTimeoutMs: Long = DEFAULT_MATRIX_TIMEOUT_MS,
    ): List<Pair<String, SensorReadResult>> {
        val started = System.nanoTime()
        return standardTargets.map { target ->
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val remainingMs = totalTimeoutMs - elapsedMs
            val result = if (remainingMs <= 0L) {
                SensorReadResult.Timeout
            } else {
                withTimeoutOrNull(remainingMs.coerceAtMost(options.timeoutMs)) {
                    read(registry, target, options)
                } ?: SensorReadResult.Timeout
            }
            target to result
        }
    }

    const val DEFAULT_MATRIX_TIMEOUT_MS = 30_000L
}
