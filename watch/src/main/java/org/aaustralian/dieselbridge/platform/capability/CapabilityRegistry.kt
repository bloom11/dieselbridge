// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.aaustralian.dieselbridge.platform.diagnostic.PlatformDiagnostics
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.aaustralian.dieselbridge.platform.provider.ProviderBindingInfo
import org.aaustralian.dieselbridge.platform.provider.ProviderStatus

/**
 * Registry of Diesel capabilities and their providers.
 *
 * Multiple providers may implement the same capability. The highest-priority
 * AVAILABLE provider is selected automatically. If it becomes unavailable or
 * enters ERROR state, the next usable provider becomes active.
 *
 * Consumers resolve capabilities by capability id and never need to know
 * which provider is currently supplying them.
 */
class CapabilityRegistry(
    private val diagnostics: PlatformDiagnostics? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private data class Entry(
        val capability: DieselCapability,
        val provider: DieselProvider,
        val priority: Int,
        val registrationOrder: Long,
        val registeredAtMs: Long,
        var availability: ProviderAvailability,
        var reason: String?,
    )

    private val lock = Any()

    private val entries =
        mutableMapOf<String, MutableList<Entry>>()

    /*
     * Created lazily for capabilities that have reactive observers.
     *
     * These flows deliberately survive provider removal so a long-lived
     * consumer can observe ACTIVE -> null -> ACTIVE without resubscribing.
     */
    private val activeSelections =
        mutableMapOf<String, MutableStateFlow<ActiveCapabilitySelection?>>()

    private var nextRegistrationOrder = 0L

    /**
     * Registers one provider implementation for one capability.
     *
     * The same provider id may expose multiple different capabilities, but the
     * same provider-capability binding cannot be registered twice.
     */
    fun register(
        capability: DieselCapability,
        provider: DieselProvider,
        priority: Int = DEFAULT_PRIORITY,
        initialAvailability: ProviderAvailability =
            ProviderAvailability.AVAILABLE,
    ): ProviderBindingInfo =
        synchronized(lock) {
            require(capability.id.isNotBlank()) {
                "Capability id must not be blank"
            }

            require(provider.providerId.isNotBlank()) {
                "Provider id must not be blank"
            }

            val previousSelection =
                activeProviderIdLocked(capability.id)

            val capabilityEntries =
                entries.getOrPut(capability.id) { mutableListOf() }

            require(
                capabilityEntries.none {
                    it.provider.providerId == provider.providerId
                },
            ) {
                "Provider '${provider.providerId}' is already registered " +
                    "for capability '${capability.id}'"
            }

            val entry = Entry(
                capability = capability,
                provider = provider,
                priority = priority,
                registrationOrder = nextRegistrationOrder++,
                registeredAtMs = clock(),
                availability = initialAvailability,
                reason = null,
            )

            capabilityEntries += entry

            publishDiagnosticsLocked(
                type = "capability.register",
                message =
                    "${capability.id} -> ${provider.providerId} " +
                        "(priority=$priority)",
                previousSelections =
                    mapOf(capability.id to previousSelection),
            )

            infoForLocked(
                entry,
                activeEntryLocked(capability.id),
            )
        }

    /**
     * Returns the currently selected implementation or null when no usable
     * provider exists.
     */
    fun resolve(id: String): DieselCapability? =
        synchronized(lock) {
            activeEntryLocked(id)?.capability
        }

    inline fun <reified T : DieselCapability> resolveAs(id: String): T? =
        resolve(id) as? T

    /**
     * Observes the currently selected provider implementation.
     *
     * The returned StateFlow is stable for the lifetime of this registry.
     * It emits null when no usable provider exists and automatically changes
     * when priority, availability, registration or removal changes selection.
     */
    fun observeActive(
        capabilityId: String,
    ): StateFlow<ActiveCapabilitySelection?> =
        synchronized(lock) {
            activeSelections
                .getOrPut(capabilityId) {
                    MutableStateFlow(
                        activeSelectionLocked(capabilityId),
                    )
                }
        }

    /**
     * Changes the health/availability of one provider-capability binding.
     *
     * Selection is recalculated immediately. Marking the active provider
     * ERROR or UNAVAILABLE therefore automatically activates the next
     * available provider.
     */
    fun setAvailability(
        capabilityId: String,
        providerId: String,
        availability: ProviderAvailability,
        reason: String? = null,
    ): Boolean =
        synchronized(lock) {
            val entry =
                entries[capabilityId]
                    ?.firstOrNull {
                        it.provider.providerId == providerId
                    }
                    ?: return@synchronized false

            val previousSelection =
                activeProviderIdLocked(capabilityId)

            entry.availability = availability
            entry.reason =
                if (availability == ProviderAvailability.AVAILABLE) {
                    null
                } else {
                    reason
                }

            publishDiagnosticsLocked(
                type = "provider.status",
                message =
                    "$capabilityId / $providerId -> $availability" +
                        if (reason.isNullOrBlank()) {
                            ""
                        } else {
                            " ($reason)"
                        },
                previousSelections =
                    mapOf(capabilityId to previousSelection),
            )

            true
        }

    /**
     * Removes one provider from one capability.
     */
    fun unregister(
        capabilityId: String,
        providerId: String,
    ): DieselCapability? =
        synchronized(lock) {
            val capabilityEntries =
                entries[capabilityId]
                    ?: return@synchronized null

            val previousSelection =
                activeProviderIdLocked(capabilityId)

            val index =
                capabilityEntries.indexOfFirst {
                    it.provider.providerId == providerId
                }

            if (index < 0) {
                return@synchronized null
            }

            val removed = capabilityEntries.removeAt(index)

            if (capabilityEntries.isEmpty()) {
                entries.remove(capabilityId)
            }

            publishDiagnosticsLocked(
                type = "capability.unregister",
                message = "$capabilityId -> $providerId",
                previousSelections =
                    mapOf(capabilityId to previousSelection),
            )

            removed.capability
        }

    /**
     * Removes every capability binding supplied by one provider.
     *
     * This will be useful when external plugin providers disconnect.
     */
    fun unregisterProvider(
        providerId: String,
    ): List<DieselCapability> =
        synchronized(lock) {
            val removed = mutableListOf<DieselCapability>()

            val affectedCapabilityIds =
                entries
                    .filterValues { capabilityEntries ->
                        capabilityEntries.any {
                            it.provider.providerId == providerId
                        }
                    }
                    .keys
                    .toSet()

            val previousSelections =
                affectedCapabilityIds.associateWith {
                    activeProviderIdLocked(it)
                }

            entries.keys.toList().forEach { capabilityId ->
                val capabilityEntries = entries[capabilityId] ?: return@forEach

                val iterator = capabilityEntries.iterator()

                while (iterator.hasNext()) {
                    val entry = iterator.next()

                    if (entry.provider.providerId == providerId) {
                        removed += entry.capability
                        iterator.remove()
                    }
                }

                if (capabilityEntries.isEmpty()) {
                    entries.remove(capabilityId)
                }
            }

            if (removed.isNotEmpty()) {
                publishDiagnosticsLocked(
                    type = "provider.unregister",
                    message =
                        "$providerId removed ${removed.size} capability binding(s)",
                    previousSelections = previousSelections,
                )
            }

            removed
        }

    /**
     * Snapshot of all providers registered for one capability.
     */
    fun providers(
        capabilityId: String,
    ): List<ProviderBindingInfo> =
        synchronized(lock) {
            providerInfosLocked(capabilityId)
        }

    /**
     * Metadata for the currently selected provider.
     */
    fun activeProvider(
        capabilityId: String,
    ): ProviderBindingInfo? =
        synchronized(lock) {
            val active =
                activeEntryLocked(capabilityId)
                    ?: return@synchronized null

            infoForLocked(active, active)
        }

    /**
     * Snapshot of every provider-capability binding.
     */
    fun allProviders(): List<ProviderBindingInfo> =
        synchronized(lock) {
            allProviderInfosLocked()
        }

    fun ids(): Set<String> =
        synchronized(lock) {
            entries.keys.toSet()
        }

    fun contains(id: String): Boolean =
        synchronized(lock) {
            entries[id]?.isNotEmpty() == true
        }

    private fun activeProviderIdLocked(
        capabilityId: String,
    ): String? =
        activeEntryLocked(capabilityId)
            ?.provider
            ?.providerId

    private fun activeSelectionLocked(
        capabilityId: String,
    ): ActiveCapabilitySelection? {
        val active =
            activeEntryLocked(capabilityId)
                ?: return null

        return ActiveCapabilitySelection(
            capabilityId = capabilityId,
            providerId = active.provider.providerId,
            capability = active.capability,
        )
    }

    private fun publishActiveSelectionLocked(
        capabilityId: String,
    ) {
        activeSelections[capabilityId]?.value =
            activeSelectionLocked(capabilityId)
    }

    private fun activeEntryLocked(
        capabilityId: String,
    ): Entry? =
        entries[capabilityId]
            ?.asSequence()
            ?.filter {
                it.availability == ProviderAvailability.AVAILABLE
            }
            ?.sortedWith(
                compareByDescending<Entry> { it.priority }
                    .thenBy { it.registrationOrder },
            )
            ?.firstOrNull()

    private fun providerInfosLocked(
        capabilityId: String,
    ): List<ProviderBindingInfo> {
        val active = activeEntryLocked(capabilityId)

        return entries[capabilityId]
            .orEmpty()
            .sortedWith(
                compareByDescending<Entry> { it.priority }
                    .thenBy { it.registrationOrder },
            )
            .map {
                infoForLocked(it, active)
            }
    }

    private fun allProviderInfosLocked(): List<ProviderBindingInfo> =
        entries.keys
            .sorted()
            .flatMap {
                providerInfosLocked(it)
            }

    private fun infoForLocked(
        entry: Entry,
        active: Entry?,
    ): ProviderBindingInfo {
        val status =
            when (entry.availability) {
                ProviderAvailability.AVAILABLE ->
                    if (entry === active) {
                        ProviderStatus.ACTIVE
                    } else {
                        ProviderStatus.STANDBY
                    }

                ProviderAvailability.UNAVAILABLE ->
                    ProviderStatus.UNAVAILABLE

                ProviderAvailability.ERROR ->
                    ProviderStatus.ERROR
            }

        return ProviderBindingInfo(
            capabilityId = entry.capability.id,
            providerId = entry.provider.providerId,
            priority = entry.priority,
            status = status,
            providerClass = entry.provider.javaClass.name,
            registrationOrder = entry.registrationOrder,
            registeredAtMs = entry.registeredAtMs,
            reason = entry.reason,
        )
    }

    private fun publishDiagnosticsLocked(
        type: String,
        message: String,
        previousSelections: Map<String, String?> = emptyMap(),
    ) {
        diagnostics?.replaceProviders(
            allProviderInfosLocked(),
        )

        diagnostics?.record(
            type = type,
            message = message,
        )

        previousSelections.forEach { (capabilityId, previousProviderId) ->
            /*
             * Update reactive consumers for every affected capability, not
             * only when the provider id changed. This also handles a provider
             * being removed and later re-registered with a new implementation.
             */
            publishActiveSelectionLocked(capabilityId)

            val currentProviderId =
                activeProviderIdLocked(capabilityId)

            if (previousProviderId != currentProviderId) {
                diagnostics?.record(
                    type = "capability.select",
                    message =
                        "$capabilityId: " +
                            "${previousProviderId ?: "<none>"} -> " +
                            "${currentProviderId ?: "<none>"}",
                )
            }
        }
    }

    companion object {
        const val DEFAULT_PRIORITY = 0
    }
}
