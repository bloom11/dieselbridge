// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.sensor.SensorReadOptions
import org.aaustralian.dieselbridge.platform.sensor.SensorReadResult

data class SensorMatrixExperimentResult(
    val entries: List<Pair<String, SensorReadResult>>,
) {
    val eventCount: Int = entries.count { it.second is SensorReadResult.Event }
    val unavailableCount: Int = entries.count { it.second is SensorReadResult.Unavailable }
    val nonEventCount: Int = entries.size - eventCount - unavailableCount
}

/** Bounded developer experiment over every standard logical sensor target. */
class SensorMatrixExperiment(
    private val capabilities: CapabilityRegistry,
) {
    suspend fun run(
        timeoutMs: Long = SensorReadOptions().timeoutMs,
    ): SensorMatrixExperimentResult =
        SensorMatrixExperimentResult(
            SensorReadCoordinator.matrix(
                registry = capabilities,
                options = SensorReadOptions(timeoutMs),
            ),
        )
}
