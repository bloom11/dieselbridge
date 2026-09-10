// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import kotlin.coroutines.resume

/** Narrow source seam so Health Services behavior can be tested without a watch. */
interface HealthServicesSource {
    suspend fun supports(logicalId: String): Boolean
    suspend fun read(logicalId: String, timeoutMs: Long): HealthServicesSample
}

data class HealthServicesSample(val values: List<Float>, val timestampNanos: Long = System.nanoTime())

/** Optional higher-priority provider for Wear OS Health Services spot measurements. */
class HealthServicesProvider(private val source: HealthServicesSource) : DieselProvider {
    override val providerId: String = PROVIDER_ID

    fun capabilities(): List<SensorCapability> = listOf(
        Capability("heart_rate", source),
        Capability("step_counter", source),
    )

    private class Capability(
        private val logicalId: String,
        private val source: HealthServicesSource,
    ) : SensorCapability {
        override val capabilityId = SensorCapabilityId("sensor.$logicalId")
        override val id: String = capabilityId.value

        override suspend fun read(options: SensorReadOptions): SensorReadResult {
            require(options.timeoutMs in 500L..15_000L)
            return try {
                if (!source.supports(logicalId)) return SensorReadResult.Unavailable
                val sample = source.read(logicalId, options.timeoutMs)
                SensorReadResult.Event(
                    SensorReading(
                        capabilityId = capabilityId,
                        providerId = PROVIDER_ID,
                        values = sample.values,
                        timestampNanos = sample.timestampNanos,
                        accuracy = null,
                        elapsedMs = 0L,
                    ),
                )
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                SensorReadResult.Timeout
            } catch (e: SecurityException) {
                SensorReadResult.PermissionDenied(requiredPermission(logicalId))
            } catch (e: IllegalStateException) {
                SensorReadResult.RegistrationRejected(e.message)
            }
        }
    }

    companion object {
        const val PROVIDER_ID = "wear.health_services"
        fun requiredPermission(logicalId: String): String = when (logicalId) {
            "heart_rate" -> Manifest.permission.BODY_SENSORS
            else -> Manifest.permission.ACTIVITY_RECOGNITION
        }
    }
}

/** Production adapter around MeasureClient. It intentionally performs only bounded foreground reads. */
class AndroidHealthServicesSource(private val context: Context) : HealthServicesSource {
    private val measureClient: MeasureClient by lazy {
        HealthServices.getClient(context.applicationContext).measureClient
    }

    override suspend fun supports(logicalId: String): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val type = dataType(logicalId) ?: return false
        return measureClient.getCapabilitiesAsync().awaitFuture().supportedDataTypesMeasure.contains(type)
    }

    override suspend fun read(logicalId: String, timeoutMs: Long): HealthServicesSample = withTimeout(timeoutMs) {
        val type = dataType(logicalId) ?: throw IllegalArgumentException("Unsupported Health Services sensor: $logicalId")
        suspendCancellableCoroutine { continuation ->
            val callback = object : MeasureCallback {
                override fun onDataReceived(data: DataPointContainer) {
                    val value = extract(logicalId, data) ?: return
                    if (continuation.isActive) continuation.resume(HealthServicesSample(listOf(value.toFloat())))
                }

                override fun onRegistrationFailed(throwable: Throwable) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException(throwable.message, throwable)))
                }
            }
            measureClient.registerMeasureCallback(type, callback)
            continuation.invokeOnCancellation {
                measureClient.unregisterMeasureCallbackAsync(type, callback)
            }
        }
    }

    private fun dataType(logicalId: String): DeltaDataType<*, *>? = when (logicalId) {
        "heart_rate" -> DataType.HEART_RATE_BPM
        "step_counter" -> DataType.STEPS
        else -> null
    }

    private fun extract(logicalId: String, data: DataPointContainer): Number? = when (logicalId) {
        "heart_rate" -> data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
        "step_counter" -> data.getData(DataType.STEPS).lastOrNull()?.value
        else -> null
    }

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.awaitFuture(): T =
        suspendCancellableCoroutine { continuation ->
            addListener(
                { if (continuation.isActive) runCatching { get() }.fold(continuation::resume) { continuation.resumeWith(Result.failure(it)) } },
                DirectExecutor,
            )
            continuation.invokeOnCancellation { cancel(true) }
        }

    private object DirectExecutor : java.util.concurrent.Executor {
        override fun execute(command: Runnable) = command.run()
        val INSTANCE = this
    }
}
