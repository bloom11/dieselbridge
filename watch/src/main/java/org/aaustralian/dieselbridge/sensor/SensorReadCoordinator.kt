// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

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
    ): List<Pair<String, SensorReadResult>> =
        standardTargets.map { target -> target to read(registry, target, options) }
}
