// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import kotlin.coroutines.cancellation.CancellationException
import org.aaustralian.dieselbridge.platform.action.DieselActionDispatcher
import org.aaustralian.dieselbridge.platform.action.DieselActionKey
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.aaustralian.dieselbridge.platform.event.DieselEvent
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorBufferPolicy
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapabilityId
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

data class DieselPublicEncodedEvent(
    val resourceId: String,
    val timestampMs: Long,
    val value: DieselValue,
)

data class DieselPublicIngressEvent(
    override val type: String,
    override val timestampMs: Long,
    val value: DieselValue,
) : DieselEvent

class DieselPublicEventBinding(
    val descriptor: DieselResourceDescriptor,
    private val matches: (DieselEvent) -> Boolean,
    private val encode: (DieselEvent) -> DieselValue,
) {
    init {
        require(descriptor.kind == DieselResourceKind.EVENT)
        require(DieselResourceOperation.SUBSCRIBE in descriptor.operations)
    }

    internal fun encodeIfMatches(event: DieselEvent): DieselPublicEncodedEvent? {
        if (!matches(event)) return null
        return try {
            DieselPublicEncodedEvent(descriptor.id, event.timestampMs, encode(event))
        } catch (_: Exception) {
            null
        }
    }
}

class DieselPublicApi(
    stateBindings: Iterable<DieselPublicStateBinding> = emptyList(),
    actionBindings: Iterable<DieselPublicActionBinding> = emptyList(),
    observationBindings: Iterable<DieselPublicObservationBinding> = emptyList(),
    eventBindings: Iterable<DieselPublicEventBinding> = emptyList(),
    apiVersion: DieselApiVersion = DieselApiVersion.CURRENT,
    private val dynamicStates: DieselStateStore? = null,
    private val dynamicActions: DieselActionDispatcher? = null,
    private val observationLimits: DieselPublicObservationLimits = DieselPublicObservationLimits(),
) {
    private val states = stateBindings.toList().associateBy { it.descriptor.id }
    private val actions = actionBindings.toList().associateBy { it.descriptor.id }
    private val observations = observationBindings.toList().associateBy { it.descriptor.id }
    private val events = eventBindings.toList().associateBy { it.descriptor.id }
    private val catalog = DieselApiCatalog(
        states.values.map { it.descriptor } +
            actions.values.map { it.descriptor } +
            observations.values.map { it.descriptor } +
            events.values.map { it.descriptor },
        apiVersion,
    )
    private val dynamicCatalog =
        DieselDynamicResourceCatalog(
            catalog.snapshot().resources.map { it.id }.toSet(),
        )
    private val publicApiVersion = apiVersion

    fun catalog(): DieselCatalogSnapshot =
        DieselCatalogSnapshot(
            publicApiVersion,
            (catalog.snapshot().resources + dynamicCatalog.snapshot())
                .sortedBy { it.id },
        )

    fun descriptor(id: String): DieselResourceDescriptor? =
        catalog.find(id) ?: dynamicCatalog.find(id)

    internal fun retainDynamicDescriptor(
        descriptor: DieselResourceDescriptor,
    ): DieselDynamicResourceRegistration =
        dynamicCatalog.retain(descriptor)

    internal fun retainTransportDescriptor(
        descriptor: DieselResourceDescriptor,
    ): DieselDynamicResourceRegistration {
        require(descriptor.kind == DieselResourceKind.TRANSPORT)
        require(
            descriptor.operations ==
                setOf(
                    DieselResourceOperation.OPEN,
                ),
        )
        return retainProviderDescriptor(
            descriptor,
        )
    }

    fun hasTransport(
        resourceId: String,
    ): Boolean =
        descriptor(resourceId)
            ?.kind ==
            DieselResourceKind.TRANSPORT

    internal fun retainProviderDescriptor(
        descriptor: DieselResourceDescriptor,
    ): DieselDynamicResourceRegistration {
        val static = catalog.find(descriptor.id)
        if (static != null) {
            require(static == descriptor) {
                "Static public resource contract mismatch for '${descriptor.id}'"
            }

            return object : DieselDynamicResourceRegistration {
                private var closed = false
                override val isClosed: Boolean
                    get() = closed

                override fun close() {
                    closed = true
                }
            }
        }

        return dynamicCatalog.retain(descriptor)
    }

    fun getState(id: String): DieselPublicStateResult {
        states[id]?.let {
            return it.read()
        }

        val descriptor = dynamicCatalog.find(id)
        if (descriptor?.kind != DieselResourceKind.STATE) {
            return DieselPublicStateResult.NotFound(id)
        }

        val store =
            dynamicStates
                ?: return DieselPublicStateResult.Failed(
                    "dynamic_state_runtime_unavailable",
                )

        val snapshot =
            store.current(
                DieselStateKey(
                    id,
                    DieselValue::class.java,
                ),
            )

        return if (snapshot == null) {
            DieselPublicStateResult.Success(
                DieselPublicStateSnapshot(
                    null,
                    null,
                    null,
                ),
            )
        } else {
            DieselPublicStateResult.Success(
                DieselPublicStateSnapshot(
                    snapshot.value,
                    snapshot.revision,
                    snapshot.updatedAtMs,
                ),
            )
        }
    }

    suspend fun invokeAction(
        id: String,
        input: DieselValue,
    ): DieselPublicActionResult {
        actions[id]?.let {
            return it.invoke(input)
        }

        val descriptor = dynamicCatalog.find(id)
        if (descriptor?.kind != DieselResourceKind.ACTION) {
            return DieselPublicActionResult.NotFound(id)
        }

        val dispatcher =
            dynamicActions
                ?: return DieselPublicActionResult.Failed(
                    "dynamic_action_runtime_unavailable",
                )

        return when (
            val result =
                dispatcher.dispatch(
                    DieselActionKey(
                        id,
                        DieselValue::class.java,
                        DieselValue::class.java,
                    ),
                    input,
                )
        ) {
            is DieselActionResult.Success ->
                DieselPublicActionResult.Success(
                    result.value,
                )

            DieselActionResult.Unavailable ->
                DieselPublicActionResult.Unavailable

            is DieselActionResult.Rejected ->
                DieselPublicActionResult.Rejected(
                    result.reason,
                )

            is DieselActionResult.Failed ->
                DieselPublicActionResult.Failed(
                    "handler_failed",
                )
        }
    }

    fun openObservation(
        client: SensorObservationClient,
        resourceId: String,
        periodMs: Long,
    ): DieselPublicObservationOpenResult {
        val logicalId =
            observations[resourceId]
                ?.logicalId
                ?: dynamicCatalog
                    .find(resourceId)
                    ?.takeIf {
                        it.kind == DieselResourceKind.OBSERVATION
                    }
                    ?.let {
                        try {
                            SensorObservationCapabilityId(
                                resourceId,
                            ).logicalId
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }
                ?: return DieselPublicObservationOpenResult.NotFound(
                    resourceId,
                )

        if (periodMs !in observationLimits.minPeriodMs..observationLimits.maxPeriodMs) {
            return DieselPublicObservationOpenResult.Rejected("period_out_of_bounds")
        }

        return try {
            DieselPublicObservationOpenResult.Opened(
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId = logicalId,
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

    fun hasEvent(resourceId: String): Boolean =
        resourceId in events ||
            dynamicCatalog.find(resourceId)?.kind == DieselResourceKind.EVENT

    fun encodeEvent(
        resourceId: String,
        event: DieselEvent,
    ): DieselPublicEncodedEvent? {
        events[resourceId]
            ?.encodeIfMatches(event)
            ?.let {
                return it
            }

        val descriptor = dynamicCatalog.find(resourceId)

        if (
            descriptor?.kind == DieselResourceKind.EVENT &&
            event is DieselPublicIngressEvent &&
            event.type == resourceId
        ) {
            return DieselPublicEncodedEvent(
                resourceId,
                event.timestampMs,
                event.value,
            )
        }

        return null
    }
}
