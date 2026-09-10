// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
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
    fun hasRequiredPermission(logicalId: String): Boolean
    suspend fun read(logicalId: String, timeoutMs: Long): HealthServicesSample
}

data class HealthServicesSample(
    val values: List<Float>,
    val timestampNanos: Long,
)

/** Optional higher-priority provider for Wear OS Health Services spot heart-rate measurements. */
class HealthServicesProvider(private val source: HealthServicesSource) : DieselProvider {
    override val providerId: String = PROVIDER_ID

    fun capabilities(): List<SensorCapability> = listOf(Capability("heart_rate", source))

    private class Capability(
        private val logicalId: String,
        private val source: HealthServicesSource,
    ) : SensorCapability {
        override val capabilityId = SensorCapabilityId("sensor.$logicalId")
        override val id: String = capabilityId.value

        override suspend fun read(options: SensorReadOptions): SensorReadResult {
            require(options.timeoutMs in 500L..15_000L)
            val startedAt = System.nanoTime()
            return try {
                if (!source.hasRequiredPermission(logicalId)) {
                    return SensorReadResult.PermissionDenied(requiredPermission(logicalId))
                }
                if (!source.supports(logicalId)) return SensorReadResult.Unavailable
                val sample = source.read(logicalId, options.timeoutMs)
                SensorReadResult.Event(
                    SensorReading(
                        capabilityId = capabilityId,
                        providerId = PROVIDER_ID,
                        values = sample.values,
                        timestampNanos = sample.timestampNanos,
                        accuracy = null,
                        elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
                    ),
                )
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                SensorReadResult.Timeout
            } catch (_: UnsupportedOperationException) {
                SensorReadResult.Unavailable
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
            else -> error("No Health Services permission mapping for $logicalId")
        }
    }
}

/** Production adapter around MeasureClient. It performs only bounded foreground reads. */
class AndroidHealthServicesSource(private val context: Context) : HealthServicesSource {
    private val measureClient: MeasureClient by lazy {
        HealthServices.getClient(context.applicationContext).measureClient
    }

    override suspend fun supports(logicalId: String): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val type = dataType(logicalId) ?: return false
        return measureClient.getCapabilitiesAsync().awaitFuture().supportedDataTypesMeasure.contains(type)
    }

    override fun hasRequiredPermission(logicalId: String): Boolean =
        logicalId == "heart_rate" &&
            context.checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED

    override suspend fun read(logicalId: String, timeoutMs: Long): HealthServicesSample = withTimeout(timeoutMs) {
        if (Build.VERSION.SDK_INT < 30) throw UnsupportedOperationException("Health Services requires API 30")
        val type = dataType(logicalId) ?: throw IllegalArgumentException("Unsupported Health Services sensor: $logicalId")
        lateinit var callback: MeasureCallback
        try {
            suspendCancellableCoroutine { continuation ->
                callback = object : MeasureCallback {
                    override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit

                    override fun onDataReceived(data: DataPointContainer) {
                        val sample = extract(logicalId, data) ?: return
                        if (continuation.isActive) continuation.resume(sample)
                    }

                    override fun onRegistrationFailed(throwable: Throwable) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(IllegalStateException(throwable.message, throwable)))
                    }
                }
                measureClient.registerMeasureCallback(type, callback)
            }
        } finally {
            if (::callback.isInitialized) {
                runCatching { measureClient.unregisterMeasureCallbackAsync(type, callback) }
            }
        }
    }

    private fun dataType(logicalId: String): DeltaDataType<*, *>? = when (logicalId) {
        "heart_rate" -> DataType.HEART_RATE_BPM
        else -> null
    }

    private fun extract(logicalId: String, data: DataPointContainer): HealthServicesSample? = when (logicalId) {
        "heart_rate" -> data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let { point ->
            HealthServicesSample(
                values = listOf(point.value.toFloat()),
                timestampNanos = point.timeDurationFromBoot.inWholeNanoseconds,
            )
        }
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
    }
}
