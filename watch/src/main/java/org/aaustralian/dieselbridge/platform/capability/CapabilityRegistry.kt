// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of currently available Diesel capabilities.
 *
 * Version 1 deliberately supports one active implementation per capability.
 * Provider priorities/fallback can be added later without changing capability
 * consumers.
 */
class CapabilityRegistry {

    private val capabilities = ConcurrentHashMap<String, DieselCapability>()

    /**
     * Registers a capability.
     *
     * Duplicate registration is rejected rather than silently replacing an
     * existing provider. This makes configuration errors obvious during the
     * early platform-development phase.
     */
    fun register(capability: DieselCapability) {
        require(capability.id.isNotBlank()) {
            "Capability id must not be blank"
        }

        val previous = capabilities.putIfAbsent(capability.id, capability)

        require(previous == null) {
            "Capability '${capability.id}' is already registered"
        }
    }

    /**
     * Returns the currently registered capability or null when unavailable.
     */
    fun resolve(id: String): DieselCapability? = capabilities[id]

    /**
     * Typed convenience resolver.
     */
    inline fun <reified T : DieselCapability> resolveAs(id: String): T? =
        resolve(id) as? T

    /**
     * Removes a capability.
     *
     * Primarily useful for runtime modules/plugins that are unloaded.
     */
    fun unregister(id: String): DieselCapability? =
        capabilities.remove(id)

    /**
     * Snapshot of available capability identifiers.
     */
    fun ids(): Set<String> =
        capabilities.keys.toSet()

    fun contains(id: String): Boolean =
        capabilities.containsKey(id)
}
