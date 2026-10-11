// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import android.os.Bundle
import android.os.RemoteException
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

    private class StateResource(
        override val registrationId: Long,
        private val publisher: DieselStatePublisher<DieselValue>,
        private val descriptorRegistration: DieselDynamicResourceRegistration,
    ) : DieselRemoteProviderResource {
        private val closed = AtomicBoolean(false)
        override val isClosed: Boolean get() = closed.get()

        fun publish(
            value: DieselValue?,
            clear: Boolean,
        ): Boolean =
            !closed.get() &&
                if (clear) {
                    publisher.clear()
                } else {
                    value != null && publisher.set(value)
                }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            publisher.close()
            descriptorRegistration.close()
        }
    }

    private class EventResource(
        override val registrationId: Long,
        val resourceId: String,
        private val descriptorRegistration: DieselDynamicResourceRegistration,
    ) : DieselRemoteProviderResource {
        private val closed = AtomicBoolean(false)
        override val isClosed: Boolean get() = closed.get()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            descriptorRegistration.close()
        }
    }

    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val resources = linkedMapOf<Long, DieselRemoteProviderResource>()
    private var nextRegistrationId = 1L

    fun register(
        requestBundle: Bundle,
        callback: IDieselProviderCallback?,
    ): Bundle {
        if (closed.get()) {
            return DieselIpcProviderCodec.rejected("provider_host_closed")
        }

        val request =
            when (
                val decoded =
                    DieselIpcProviderCodec.decodeRequest(requestBundle)
            ) {
                is DieselPublicDecodeResult.Invalid ->
                    return DieselIpcProviderCodec.rejected(decoded.reason)

                is DieselPublicDecodeResult.Value ->
                    decoded.value
            }

        val decision =
            DieselExternalProviderPolicy.decide(
                identity,
                request,
                runtime.api.descriptor(request.id),
            )

        val allowed =
            decision as? DieselProviderRegistrationDecision.Allowed
                ?: return DieselIpcProviderCodec.rejected(
                    (decision as DieselProviderRegistrationDecision.Rejected).reason,
                )

        if (
            (
                allowed.descriptor.kind == DieselResourceKind.ACTION ||
                allowed.descriptor.kind == DieselResourceKind.OBSERVATION
            ) &&
            callback == null
        ) {
            return DieselIpcProviderCodec.rejected(
                "provider_callback_required",
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
                runtime.api.retainProviderDescriptor(
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

                    DieselResourceKind.ACTION ->
                        DieselRemoteActionProviderResource.create(
                            registrationId = registrationId,
                            descriptor = allowed.descriptor,
                            callback = requireNotNull(callback),
                            actions = runtime.actions,
                            ownerId = ownerId,
                            descriptorRegistration = descriptorRegistration,
                            onCallbackDead = ::removeCallbackResource,
                        )

                    DieselResourceKind.OBSERVATION ->
                        DieselRemoteObservationProviderResource.create(
                            registrationId = registrationId,
                            descriptor = allowed.descriptor,
                            callback = requireNotNull(callback),
                            registry = runtime.capabilities,
                            priority = requireNotNull(allowed.effectivePriority),
                            descriptorRegistration = descriptorRegistration,
                            providerId = "external.$sessionId.$registrationId",
                            onCallbackDead = ::removeCallbackResource,
                        )
                            ?: return DieselIpcProviderCodec.rejected(
                                "callback_dead",
                            )

                    DieselResourceKind.EVENT ->
                        EventResource(
                            registrationId,
                            allowed.descriptor.id,
                            descriptorRegistration,
                        )

                    DieselResourceKind.TRANSPORT -> {
                        descriptorRegistration.close()
                        return DieselIpcProviderCodec.rejected(
                            "transport_provider_is_m75",
                        )
                    }
                }
            } catch (_: RemoteException) {
                descriptorRegistration.close()
                return DieselIpcProviderCodec.rejected("callback_dead")
            } catch (_: IllegalStateException) {
                descriptorRegistration.close()
                return DieselIpcProviderCodec.rejected(
                    "core_registration_conflict",
                )
            } catch (_: IllegalArgumentException) {
                descriptorRegistration.close()
                return DieselIpcProviderCodec.rejected(
                    "core_registration_rejected",
                )
            }

        synchronized(lock) {
            if (
                resource.isClosed ||
                closed.get() ||
                resources.size >= MAX_REGISTRATIONS
            ) {
                resource.close()
                return DieselIpcProviderCodec.rejected(
                    if (resource.isClosed) {
                        "callback_dead"
                    } else {
                        "provider_registration_limit"
                    },
                )
            }

            resources[registrationId] = resource
        }

        return DieselIpcProviderCodec.ok(registrationId)
    }

    fun unregister(
        registrationId: Long,
    ): Boolean {
        val resource =
            synchronized(lock) {
                resources.remove(registrationId)
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
            resourceAs<StateResource>(registrationId)
                ?: return DieselIpcProviderCodec.rejected(
                    "not_state_provider",
                )

        val value =
            if (clear) {
                null
            } else {
                when (
                    val decoded =
                        DieselIpcCodec.decodeValue(valueBundle)
                ) {
                    is DieselPublicDecodeResult.Invalid ->
                        return DieselIpcProviderCodec.rejected(
                            decoded.reason,
                        )

                    is DieselPublicDecodeResult.Value ->
                        decoded.value
                }
            }

        return if (resource.publish(value, clear)) {
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
            resourceAs<EventResource>(registrationId)
                ?: return DieselIpcProviderCodec.rejected(
                    "not_event_provider",
                )

        val value =
            when (val decoded = DieselIpcCodec.decodeValue(valueBundle)) {
                is DieselPublicDecodeResult.Invalid ->
                    return DieselIpcProviderCodec.rejected(decoded.reason)

                is DieselPublicDecodeResult.Value ->
                    decoded.value
            }

        val accepted =
            runtime.eventBus.tryEmit(
                DieselPublicIngressEvent(
                    resource.resourceId,
                    System.currentTimeMillis(),
                    value,
                ),
            )

        return if (accepted) {
            DieselIpcProviderCodec.ok()
        } else {
            DieselIpcProviderCodec.rejected("event_bus_busy")
        }
    }

    fun completeAction(
        registrationId: Long,
        requestId: Long,
        resultBundle: Bundle,
    ): Bundle {
        val resource =
            resourceAs<DieselRemoteActionProviderResource>(registrationId)
                ?: return DieselIpcProviderCodec.rejected(
                    "not_action_provider",
                )

        val result =
            when (
                val decoded =
                    DieselIpcProviderCodec.decodeActionCompletion(resultBundle)
            ) {
                is DieselPublicDecodeResult.Invalid ->
                    return DieselIpcProviderCodec.rejected(decoded.reason)

                is DieselPublicDecodeResult.Value ->
                    decoded.value
            }

        return if (resource.complete(requestId, result)) {
            DieselIpcProviderCodec.ok()
        } else {
            DieselIpcProviderCodec.rejected(
                "unknown_action_request",
            )
        }
    }

    fun pushObservation(
        registrationId: Long,
        acquisitionId: Long,
        update: Bundle,
    ): Bundle {
        val resource =
            resourceAs<DieselRemoteObservationProviderResource>(registrationId)
                ?: return DieselIpcProviderCodec.rejected(
                    "not_observation_provider",
                )

        val error = resource.push(acquisitionId, update)
        return if (error == null) {
            DieselIpcProviderCodec.ok()
        } else {
            DieselIpcProviderCodec.rejected(error)
        }
    }

    fun setAvailability(
        registrationId: Long,
        availability: String?,
        reason: String?,
    ): Bundle {
        val resource =
            resourceAs<DieselRemoteObservationProviderResource>(registrationId)
                ?: return DieselIpcProviderCodec.rejected(
                    "availability_not_supported",
                )

        val parsed =
            DieselIpcProviderCodec.decodeAvailability(availability)
                ?: return DieselIpcProviderCodec.rejected(
                    "invalid_availability",
                )

        val safeReason = DieselIpcProviderCodec.boundedReason(reason)
        if (reason != null && safeReason == null) {
            return DieselIpcProviderCodec.rejected(
                "invalid_availability_reason",
            )
        }

        return if (resource.setAvailability(parsed, safeReason)) {
            DieselIpcProviderCodec.ok()
        } else {
            DieselIpcProviderCodec.rejected(
                "availability_update_failed",
            )
        }
    }

    private fun removeCallbackResource(
        registrationId: Long,
    ) {
        val resource =
            synchronized(lock) {
                resources.remove(registrationId)
            }
        resource?.close()
    }

    private inline fun <reified T : DieselRemoteProviderResource>
        resourceAs(
            registrationId: Long,
        ): T? =
        synchronized(lock) {
            resources[registrationId] as? T
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        val toClose =
            synchronized(lock) {
                resources.values.toList().also {
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
