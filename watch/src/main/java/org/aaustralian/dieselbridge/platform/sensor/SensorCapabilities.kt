// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import org.aaustralian.dieselbridge.platform.capability.DieselCapability

@JvmInline
value class SensorCapabilityId(val value: String) {
    init { require(value.startsWith("sensor.") && value.length > 7) }
}

data class SensorReadOptions(val timeoutMs: Long = 5_000L)

sealed interface SensorReadResult {
    data class Event(val reading: SensorReading) : SensorReadResult
    data object Unavailable : SensorReadResult
    data class PermissionDenied(val requiredPermission: String?) : SensorReadResult
    data object Timeout : SensorReadResult
    data class RegistrationRejected(val reason: String?) : SensorReadResult
}

data class SensorReading(
    val capabilityId: SensorCapabilityId,
    val providerId: String,
    val values: List<Float>,
    val timestampNanos: Long,
    val accuracy: Int?,
    val elapsedMs: Long,
)

interface SensorCapability : DieselCapability {
    val capabilityId: SensorCapabilityId
    suspend fun read(options: SensorReadOptions = SensorReadOptions()): SensorReadResult
}

internal class SensorManagerProvider(
    private val source: AndroidSensorManagerSource,
    private val sampler: AndroidSensorSampler,
) : org.aaustralian.dieselbridge.platform.provider.DieselProvider {
    override val providerId: String = SensorManagerRouteCatalog.PROVIDER_ID
    fun capabilities(): List<SensorCapability> = STANDARD_LOGICAL_IDS.map { SensorManagerCapability(it, source, sampler) }
    private companion object {
        val STANDARD_LOGICAL_IDS = listOf("accelerometer", "gyroscope", "magnetic_field", "light", "pressure", "ambient_temperature", "heart_rate", "step_counter")
    }
}

private class SensorManagerCapability(
    private val logicalId: String,
    private val source: AndroidSensorManagerSource,
    private val sampler: AndroidSensorSampler,
) : SensorCapability {
    override val capabilityId = SensorCapabilityId("sensor.$logicalId")
    override val id: String = capabilityId.value

    override suspend fun read(options: SensorReadOptions): SensorReadResult {
        require(options.timeoutMs in AndroidSensorSampler.MIN_TIMEOUT_MS..AndroidSensorSampler.MAX_TIMEOUT_MS)
        val handle = source.snapshot().asSequence()
            .filter { it.route.inventory.logicalId == logicalId }
            .sortedWith(compareBy<AndroidSensorHandle> { it.route.inventory.wakeUp }.thenBy { it.route.inventory.powerMilliAmps }.thenBy { it.route.descriptor.routeId.value })
            .firstOrNull() ?: return SensorReadResult.Unavailable
        return when (val outcome = sampler.sample(handle, options.timeoutMs)) {
            is BoundedSensorSampleOutcome.Event -> SensorReadResult.Event(SensorReading(capabilityId, providerId, outcome.event.values.toList(), outcome.event.timestampNanos, outcome.event.accuracy, outcome.elapsedMs))
            is BoundedSensorSampleOutcome.Timeout -> SensorReadResult.Timeout
            is BoundedSensorSampleOutcome.PermissionDenied -> SensorReadResult.PermissionDenied(outcome.requiredPermission)
            is BoundedSensorSampleOutcome.RegistrationRejected -> SensorReadResult.RegistrationRejected(outcome.reason)
        }
    }

    private val providerId: String get() = SensorManagerRouteCatalog.PROVIDER_ID
}
