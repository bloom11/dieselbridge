// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import android.os.Bundle
import java.util.concurrent.atomic.AtomicBoolean
import org.aaustralian.dieselbridge.api.DieselCallerIdentity
import org.aaustralian.dieselbridge.api.DieselDynamicResourceRegistration
import org.aaustralian.dieselbridge.api.DieselExternalProviderPolicy
import org.aaustralian.dieselbridge.api.DieselProviderRegistrationDecision
import org.aaustralian.dieselbridge.api.DieselPublicDecodeResult
import org.aaustralian.dieselbridge.api.DieselPublicIngressEvent
import org.aaustralian.dieselbridge.api.DieselPublicRuntimeContext
import org.aaustralian.dieselbridge.api.DieselResourceKind
import org.aaustralian.dieselbridge.platform.state.DieselStateKey
import org.aaustralian.dieselbridge.platform.state.DieselStatePublisher
import org.aaustralian.dieselbridge.protocol.DieselValue

internal class DieselProviderSessionHost(
    private val sessionId: Long,
    private val identity: DieselCallerIdentity,
    private val runtime: DieselPublicRuntimeContext,
) : AutoCloseable {
    private sealed interface Resource : AutoCloseable {
        val registrationId: Long
    }

    private class StateResource(
        override val registrationId: Long,
        private val publisher: DieselStatePublisher<DieselValue>,
        private val descriptorRegistration:
            DieselDynamicResourceRegistration,
    ) : Resource {
        fun publish(
            value: DieselValue?,
            clear: Boolean,
        ): Boolean =
            if (clear) {
                publisher.clear()
            } else {
                value != null &&
                    publisher.set(value)
            }

        override fun close() {
            publisher.close()
            descriptorRegistration.close()
        }
    }

    private class EventResource(
        override val registrationId: Long,
        val resourceId: String,
        private val descriptorRegistration:
            DieselDynamicResourceRegistration,
    ) : Resource {
        override fun close() {
            descriptorRegistration.close()
        }
    }

    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val resources = linkedMapOf<Long, Resource>()
    private var nextRegistrationId = 1L

    fun register(
        requestBundle: Bundle,
        callback: IDieselProviderCallback?,
    ): Bundle {
        @Suppress("UNUSED_VARIABLE")
        val reservedCallback = callback

        if (closed.get()) {
            return DieselIpcProviderCodec.rejected(
                "provider_host_closed",
            )
        }

        val request =
            when (
                val decoded =
                    DieselIpcProviderCodec.decodeRequest(
                        requestBundle,
                    )
            ) {
                is DieselPublicDecodeResult.Invalid ->
                    return DieselIpcProviderCodec.rejected(
                        decoded.reason,
                    )

                is DieselPublicDecodeResult.Value ->
                    decoded.value
            }

        val decision =
            DieselExternalProviderPolicy.decide(
                identity,
                request,
                runtime.api.descriptor(
                    request.id,
                ),
            )

        val allowed =
            decision as?
                DieselProviderRegistrationDecision.Allowed
                ?: return DieselIpcProviderCodec.rejected(
                    (
                        decision as
                            DieselProviderRegistrationDecision.Rejected
                    ).reason,
                )

        if (
            allowed.descriptor.kind != DieselResourceKind.STATE &&
            allowed.descriptor.kind != DieselResourceKind.EVENT
        ) {
            return DieselIpcProviderCodec.rejected(
                "provider_role_pending_m74b2",
            )
        }

        val registrationId =
            synchronized(lock) {
                if (resources.size >= MAX_REGISTRATIONS) {
                    return DieselIpcProviderCodec.rejected(
                        "provider_registration_limit",
                    )
                }
                nextRegistrationId++
            }

        val ownerId =
            "ipc.provider:$sessionId:$registrationId:${identity.uid}"

        val descriptorRegistration =
            try {
                runtime.api.retainDynamicDescriptor(
                    allowed.descriptor,
                )
            } catch (_: IllegalArgumentException) {
                return DieselIpcProviderCodec.rejected(
                    "resource_contract_conflict",
                )
            }

        val resource =
            try {
                when (allowed.descriptor.kind) {
                    DieselResourceKind.STATE -> {
                        val publisher =
                            runtime.states.registerPublisher(
                                DieselStateKey(
                                    allowed.descriptor.id,
                                    DieselValue::class.java,
                                ),
                                ownerId,
                            )

                        StateResource(
                            registrationId,
                            publisher,
                            descriptorRegistration,
                        )
                    }

                    DieselResourceKind.EVENT ->
                        EventResource(
                            registrationId,
                            allowed.descriptor.id,
                            descriptorRegistration,
                        )

                    else -> {
                        descriptorRegistration.close()
                        return DieselIpcProviderCodec.rejected(
                            "provider_role_pending_m74b2",
                        )
                    }
                }
            } catch (_: IllegalStateException) {
                descriptorRegistration.close()
                return DieselIpcProviderCodec.rejected(
                    "core_registration_conflict",
                )
            }

        synchronized(lock) {
            if (
                closed.get() ||
                resources.size >= MAX_REGISTRATIONS
            ) {
                resource.close()
                return DieselIpcProviderCodec.rejected(
                    "provider_registration_limit",
                )
            }

            resources[
                registrationId
            ] =
                resource
        }

        return DieselIpcProviderCodec.ok(
            registrationId,
        )
    }

    fun unregister(
        registrationId: Long,
    ): Boolean {
        val resource =
            synchronized(lock) {
                resources.remove(
                    registrationId,
                )
            } ?: return false

        resource.close()
        return true
    }

    fun publishState(
        registrationId: Long,
        valueBundle: Bundle,
        clear: Boolean,
    ): Bundle {
        val resource =
            synchronized(lock) {
                resources[
                    registrationId
                ] as?
                    StateResource
            }
                ?: return DieselIpcProviderCodec.rejected(
                    "not_state_provider",
                )

        val value =
            if (clear) {
                null
            } else {
                when (
                    val decoded =
                        DieselIpcCodec.decodeValue(
                            valueBundle,
                        )
                ) {
                    is DieselPublicDecodeResult.Invalid ->
                        return DieselIpcProviderCodec.rejected(
                            decoded.reason,
                        )

                    is DieselPublicDecodeResult.Value ->
                        decoded.value
                }
            }

        return if (
            resource.publish(
                value,
                clear,
            )
        ) {
            DieselIpcProviderCodec.ok()
        } else {
            DieselIpcProviderCodec.rejected(
                "state_publication_failed",
            )
        }
    }

    fun publishEvent(
        registrationId: Long,
        valueBundle: Bundle,
    ): Bundle {
        val resource =
            synchronized(lock) {
                resources[
                    registrationId
                ] as?
                    EventResource
            }
                ?: return DieselIpcProviderCodec.rejected(
                    "not_event_provider",
                )

        val value =
            when (
                val decoded =
                    DieselIpcCodec.decodeValue(
                        valueBundle,
                    )
            ) {
                is DieselPublicDecodeResult.Invalid ->
                    return DieselIpcProviderCodec.rejected(
                        decoded.reason,
                    )

                is DieselPublicDecodeResult.Value ->
                    decoded.value
            }

        val accepted =
            runtime.eventBus.tryEmit(
                DieselPublicIngressEvent(
                    type =
                        resource.resourceId,
                    timestampMs =
                        System.currentTimeMillis(),
                    value =
                        value,
                ),
            )

        return if (accepted) {
            DieselIpcProviderCodec.ok()
        } else {
            DieselIpcProviderCodec.rejected(
                "event_bus_busy",
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }

        val toClose =
            synchronized(lock) {
                resources.values
                    .toList()
                    .also {
                        resources.clear()
                    }
            }

        toClose.forEach {
            it.close()
        }
    }

    private companion object {
        const val MAX_REGISTRATIONS = 8
    }
}
