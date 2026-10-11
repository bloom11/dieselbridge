// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import kotlin.coroutines.cancellation.CancellationException
import org.aaustralian.dieselbridge.platform.action.DieselActionDispatcher
import org.aaustralian.dieselbridge.platform.action.DieselActionKey
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorBufferPolicy
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationClient
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscription
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionRequest
import org.aaustralian.dieselbridge.platform.state.DieselStateKey
import org.aaustralian.dieselbridge.platform.state.DieselStateStore
import org.aaustralian.dieselbridge.protocol.DieselValue

class DieselPublicStateBinding private constructor(
    val descriptor: DieselResourceDescriptor,
    internal val read: () -> DieselPublicStateResult,
) {
    companion object {
        fun <T : Any> fromStateStore(
            states: DieselStateStore,
            descriptor: DieselResourceDescriptor,
            key: DieselStateKey<T>,
            encode: (T) -> DieselValue,
        ): DieselPublicStateBinding {
            require(descriptor.kind == DieselResourceKind.STATE)
            require(DieselResourceOperation.READ in descriptor.operations)
            require(descriptor.id == key.id)
            return DieselPublicStateBinding(descriptor) {
                val snapshot = states.current(key)
                if (snapshot == null) {
                    DieselPublicStateResult.Success(DieselPublicStateSnapshot(null, null, null))
                } else {
                    try {
                        DieselPublicStateResult.Success(
                            DieselPublicStateSnapshot(encode(snapshot.value), snapshot.revision, snapshot.updatedAtMs),
                        )
                    } catch (_: Exception) {
                        DieselPublicStateResult.Failed("state_encoding_failed")
                    }
                }
            }
        }
    }
}

class DieselPublicActionBinding private constructor(
    val descriptor: DieselResourceDescriptor,
    internal val invoke: suspend (DieselValue) -> DieselPublicActionResult,
) {
    companion object {
        fun <I : Any, O : Any> fromActionDispatcher(
            actions: DieselActionDispatcher,
            descriptor: DieselResourceDescriptor,
            key: DieselActionKey<I, O>,
            decode: (DieselValue) -> DieselPublicDecodeResult<I>,
            encode: (O) -> DieselValue,
        ): DieselPublicActionBinding {
            require(descriptor.kind == DieselResourceKind.ACTION)
            require(DieselResourceOperation.INVOKE in descriptor.operations)
            require(descriptor.id == key.id)
            return DieselPublicActionBinding(descriptor) { input ->
                when (val decoded = decode(input)) {
                    is DieselPublicDecodeResult.Invalid -> DieselPublicActionResult.InvalidInput(decoded.reason)
                    is DieselPublicDecodeResult.Value -> {
                        val result = try {
                            actions.dispatch(key, decoded.value)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        }
                        when (result) {
                            is DieselActionResult.Success -> try {
                                DieselPublicActionResult.Success(encode(result.value))
                            } catch (_: Exception) {
                                DieselPublicActionResult.Failed("action_encoding_failed")
                            }
                            DieselActionResult.Unavailable -> DieselPublicActionResult.Unavailable
                            is DieselActionResult.Rejected -> DieselPublicActionResult.Rejected(result.reason)
                            is DieselActionResult.Failed -> DieselPublicActionResult.Failed("handler_failed")
                        }
                    }
                }
            }
        }
    }
}

data class DieselPublicObservationBinding(
    val descriptor: DieselResourceDescriptor,
    val logicalId: String,
) {
    init {
        require(descriptor.kind == DieselResourceKind.OBSERVATION)
        require(DieselResourceOperation.SUBSCRIBE in descriptor.operations)
        require(logicalId.isNotBlank())
    }
}

data class DieselPublicObservationLimits(
    val minPeriodMs: Long = 20L,
    val maxPeriodMs: Long = 60_000L,
) {
    init {
        require(minPeriodMs > 0L)
        require(maxPeriodMs >= minPeriodMs)
    }
}

sealed interface DieselPublicObservationOpenResult {
    data class Opened(val subscription: SensorSubscription) : DieselPublicObservationOpenResult
    data class NotFound(val resourceId: String) : DieselPublicObservationOpenResult
    data class Rejected(val reason: String) : DieselPublicObservationOpenResult
}

class DieselPublicApi(
    stateBindings: Iterable<DieselPublicStateBinding> = emptyList(),
    actionBindings: Iterable<DieselPublicActionBinding> = emptyList(),
    observationBindings: Iterable<DieselPublicObservationBinding> = emptyList(),
    apiVersion: DieselApiVersion = DieselApiVersion.CURRENT,
    private val observationLimits: DieselPublicObservationLimits = DieselPublicObservationLimits(),
) {
    private val states = stateBindings.toList().associateBy { it.descriptor.id }
    private val actions = actionBindings.toList().associateBy { it.descriptor.id }
    private val observations = observationBindings.toList().associateBy { it.descriptor.id }
    private val catalog = DieselApiCatalog(
        states.values.map { it.descriptor } +
            actions.values.map { it.descriptor } +
            observations.values.map { it.descriptor },
        apiVersion,
    )

    fun catalog(): DieselCatalogSnapshot = catalog.snapshot()
    fun descriptor(id: String): DieselResourceDescriptor? = catalog.find(id)
    fun getState(id: String): DieselPublicStateResult =
        states[id]?.read?.invoke() ?: DieselPublicStateResult.NotFound(id)

    suspend fun invokeAction(id: String, input: DieselValue): DieselPublicActionResult =
        actions[id]?.invoke?.invoke(input) ?: DieselPublicActionResult.NotFound(id)

    fun openObservation(
        client: SensorObservationClient,
        resourceId: String,
        periodMs: Long,
    ): DieselPublicObservationOpenResult {
        val binding = observations[resourceId]
            ?: return DieselPublicObservationOpenResult.NotFound(resourceId)

        if (periodMs !in observationLimits.minPeriodMs..observationLimits.maxPeriodMs) {
            return DieselPublicObservationOpenResult.Rejected("period_out_of_bounds")
        }

        return try {
            DieselPublicObservationOpenResult.Opened(
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId = binding.logicalId,
                        periodMs = periodMs,
                        bufferPolicy = SensorBufferPolicy.Latest,
                    ),
                ),
            )
        } catch (_: IllegalStateException) {
            DieselPublicObservationOpenResult.Rejected("observation_limit_or_runtime_closed")
        } catch (_: IllegalArgumentException) {
            DieselPublicObservationOpenResult.Rejected("invalid_observation_request")
        }
    }
}
