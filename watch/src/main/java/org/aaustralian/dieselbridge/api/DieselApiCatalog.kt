// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

/** Immutable discovery metadata. Runtime ownership remains in the existing Diesel core. */
class DieselApiCatalog(
    descriptors: Iterable<DieselResourceDescriptor>,
    private val apiVersion: DieselApiVersion = DieselApiVersion.CURRENT,
) {
    private val resources: List<DieselResourceDescriptor>
    private val byId: Map<String, DieselResourceDescriptor>

    init {
        val stable = descriptors.toList().sortedBy { it.id }
        val duplicate = stable.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }?.key
        require(duplicate == null) {
            "Duplicate public Diesel resource id '$duplicate'"
        }
        resources = stable
        byId = stable.associateBy { it.id }
    }

    fun snapshot(): DieselCatalogSnapshot =
        DieselCatalogSnapshot(apiVersion = apiVersion, resources = resources)

    fun find(id: String): DieselResourceDescriptor? = byId[id]
}
