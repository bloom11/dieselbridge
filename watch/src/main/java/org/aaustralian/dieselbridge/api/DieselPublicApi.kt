// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import kotlin.coroutines.cancellation.CancellationException
import org.aaustralian.dieselbridge.platform.action.DieselActionDispatcher
import org.aaustralian.dieselbridge.platform.action.DieselActionKey
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
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
            require(descriptor.id == key.id) {
                "Public state id '${descriptor.id}' must match StateStore key '${key.id}'"
            }

            return DieselPublicStateBinding(
                descriptor = descriptor,
                read = {
                    val snapshot = states.current(key)
                    if (snapshot == null) {
                        DieselPublicStateResult.Success(
                            DieselPublicStateSnapshot(value = null, revision = null, updatedAtMs = null),
                        )
                    } else {
                        try {
                            DieselPublicStateResult.Success(
                                DieselPublicStateSnapshot(
                                    value = encode(snapshot.value),
                                    revision = snapshot.revision,
                                    updatedAtMs = snapshot.updatedAtMs,
                                ),
                            )
                        } catch (_: Exception) {
                            DieselPublicStateResult.Failed(reason = "state_encoding_failed")
                        }
                    }
                },
            )
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
            require(descriptor.id == key.id) {
                "Public action id '${descriptor.id}' must match ActionDispatcher key '${key.id}'"
            }

            return DieselPublicActionBinding(
                descriptor = descriptor,
                invoke = { input ->
                    when (val decoded = decode(input)) {
                        is DieselPublicDecodeResult.Invalid ->
                            DieselPublicActionResult.InvalidInput(reason = decoded.reason)
                        is DieselPublicDecodeResult.Value -> {
                            val result =
                                try {
                                    actions.dispatch(key = key, input = decoded.value)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                }
                            when (result) {
                                is DieselActionResult.Success ->
                                    try {
                                        DieselPublicActionResult.Success(value = encode(result.value))
                                    } catch (_: Exception) {
                                        DieselPublicActionResult.Failed(reason = "action_encoding_failed")
                                    }
                                DieselActionResult.Unavailable -> DieselPublicActionResult.Unavailable
                                is DieselActionResult.Rejected ->
                                    DieselPublicActionResult.Rejected(reason = result.reason)
                                is DieselActionResult.Failed ->
                                    DieselPublicActionResult.Failed(reason = "handler_failed")
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * Process-local implementation of public Diesel semantics.
 *
 * Binder and wire transports must adapt to this surface rather than reimplement state/action
 * semantics. Later M7 slices add observation/event/provider/transport operations by delegating
 * to their existing core owners.
 */
class DieselPublicApi(
    stateBindings: Iterable<DieselPublicStateBinding> = emptyList(),
    actionBindings: Iterable<DieselPublicActionBinding> = emptyList(),
    apiVersion: DieselApiVersion = DieselApiVersion.CURRENT,
) {
    private val states: Map<String, DieselPublicStateBinding>
    private val actions: Map<String, DieselPublicActionBinding>
    private val catalog: DieselApiCatalog

    init {
        val stableStates = stateBindings.toList()
        val stableActions = actionBindings.toList()
        catalog =
            DieselApiCatalog(
                descriptors = stableStates.map { it.descriptor } + stableActions.map { it.descriptor },
                apiVersion = apiVersion,
            )
        states = stableStates.associateBy { it.descriptor.id }
        actions = stableActions.associateBy { it.descriptor.id }
    }

    fun catalog(): DieselCatalogSnapshot = catalog.snapshot()

    fun getState(id: String): DieselPublicStateResult =
        states[id]?.read?.invoke()
            ?: DieselPublicStateResult.NotFound(resourceId = id)

    suspend fun invokeAction(id: String, input: DieselValue): DieselPublicActionResult =
        actions[id]?.invoke?.invoke(input)
            ?: DieselPublicActionResult.NotFound(resourceId = id)
}
