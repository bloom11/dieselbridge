// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import java.util.concurrent.atomic.AtomicBoolean

internal data class DieselProviderResourceRequest(
    val id: String,
    val kind: DieselResourceKind,
    val schema: String,
    val summary: String,
)

internal sealed interface DieselProviderRegistrationDecision {
    data class Allowed(
        val descriptor: DieselResourceDescriptor,
        val effectivePriority: Int?,
    ) : DieselProviderRegistrationDecision

    data class Rejected(
        val reason: String,
    ) : DieselProviderRegistrationDecision
}

internal object DieselExternalProviderPolicy {
    const val EXTERNAL_OBSERVATION_PRIORITY = -100

    fun decide(
        identity: DieselCallerIdentity,
        request: DieselProviderResourceRequest,
        existing: DieselResourceDescriptor?,
    ): DieselProviderRegistrationDecision {
        if (identity.packages.isEmpty()) {
            return DieselProviderRegistrationDecision.Rejected(
                "missing_package_identity",
            )
        }

        if (existing != null) {
            if (
                existing.kind != request.kind ||
                existing.schema != request.schema
            ) {
                return DieselProviderRegistrationDecision.Rejected(
                    "resource_contract_mismatch",
                )
            }

            if (
                existing.kind == DieselResourceKind.OBSERVATION &&
                DieselResourceOperation.PROVIDE in existing.operations
            ) {
                return DieselProviderRegistrationDecision.Allowed(
                    existing,
                    EXTERNAL_OBSERVATION_PRIORITY,
                )
            }

            return DieselProviderRegistrationDecision.Rejected(
                "existing_resource_not_provider_extensible",
            )
        }

        if (!isCallerOwned(identity, request.id, request.kind)) {
            return DieselProviderRegistrationDecision.Rejected(
                "resource_id_outside_caller_namespace",
            )
        }

        val operations =
            when (request.kind) {
                DieselResourceKind.STATE ->
                    setOf(
                        DieselResourceOperation.READ,
                        DieselResourceOperation.PROVIDE,
                    )

                DieselResourceKind.ACTION ->
                    setOf(
                        DieselResourceOperation.INVOKE,
                        DieselResourceOperation.PROVIDE,
                    )

                DieselResourceKind.OBSERVATION ->
                    setOf(
                        DieselResourceOperation.SUBSCRIBE,
                        DieselResourceOperation.PROVIDE,
                    )

                DieselResourceKind.EVENT ->
                    setOf(
                        DieselResourceOperation.SUBSCRIBE,
                        DieselResourceOperation.PUBLISH,
                    )

                DieselResourceKind.TRANSPORT ->
                    return DieselProviderRegistrationDecision.Rejected(
                        "transport_provider_is_m75",
                    )
            }

        val descriptor =
            try {
                DieselResourceDescriptor(
                    request.id,
                    request.kind,
                    request.schema,
                    operations,
                    request.summary,
                )
            } catch (_: IllegalArgumentException) {
                return DieselProviderRegistrationDecision.Rejected(
                    "invalid_resource_descriptor",
                )
            }

        return DieselProviderRegistrationDecision.Allowed(
            descriptor,
            if (request.kind == DieselResourceKind.OBSERVATION) {
                EXTERNAL_OBSERVATION_PRIORITY
            } else {
                null
            },
        )
    }

    fun isCallerOwned(
        identity: DieselCallerIdentity,
        id: String,
        kind: DieselResourceKind,
    ): Boolean =
        identity.packages.any { packageName ->
            val prefix =
                if (kind == DieselResourceKind.OBSERVATION) {
                    "sensor.observe.plugin.$packageName."
                } else {
                    "plugin.$packageName."
                }

            id.startsWith(prefix) &&
                id.length > prefix.length
        }
}

internal interface DieselDynamicResourceRegistration : AutoCloseable {
    val isClosed: Boolean
}

/**
 * Ref-counted dynamic descriptor set. It is discovery metadata only:
 * state/action/event/observation ownership remains in the existing
 * Diesel core managers.
 */
internal class DieselDynamicResourceCatalog(
    private val reservedIds: Set<String>,
) {
    private data class Entry(
        val descriptor: DieselResourceDescriptor,
        val tokens: MutableSet<Long>,
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private var nextToken = 1L

    fun retain(
        descriptor: DieselResourceDescriptor,
    ): DieselDynamicResourceRegistration {
        require(descriptor.id !in reservedIds) {
            "Dynamic resource collides with static resource '${descriptor.id}'"
        }

        val token =
            synchronized(lock) {
                val existing = entries[descriptor.id]
                if (existing != null) {
                    require(existing.descriptor == descriptor) {
                        "Dynamic resource contract mismatch for '${descriptor.id}'"
                    }
                    nextToken++.also { existing.tokens += it }
                } else {
                    nextToken++.also {
                        entries[descriptor.id] =
                            Entry(
                                descriptor,
                                mutableSetOf(it),
                            )
                    }
                }
            }

        return Registration(this, descriptor.id, token)
    }

    fun find(
        id: String,
    ): DieselResourceDescriptor? =
        synchronized(lock) {
            entries[id]?.descriptor
        }

    fun snapshot():
        List<DieselResourceDescriptor> =
        synchronized(lock) {
            entries.values
                .map { it.descriptor }
                .sortedBy { it.id }
        }

    private fun release(
        id: String,
        token: Long,
    ) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            if (!entry.tokens.remove(token)) return
            if (entry.tokens.isEmpty()) entries.remove(id)
        }
    }

    private class Registration(
        private val catalog: DieselDynamicResourceCatalog,
        private val id: String,
        private val token: Long,
    ) : DieselDynamicResourceRegistration {
        private val closed = AtomicBoolean(false)
        override val isClosed: Boolean get() = closed.get()

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                catalog.release(id, token)
            }
        }
    }
}
