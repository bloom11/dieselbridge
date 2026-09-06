// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.protocol.DieselValue

/**
 * One coherent snapshot of one developer-export section.
 *
 * Items are transport-neutral structured values. Pagination and the protocol
 * response byte budget are owned by DeveloperExportCommandModule.
 */
data class DeveloperExportSnapshot(
    val source: String,
    val items: List<DieselValue.ObjectValue>,
    val metadata: Map<String, DieselValue> =
        emptyMap(),
)

/**
 * Extensible catalogue of developer-data sections.
 *
 * Section discovery and execution use this same registry. Adding a future
 * health, plugin, LCD or alarm section therefore does not require a central
 * section switch in BLE or the protocol engine.
 */
class DeveloperExportRegistry {

    private data class Entry(
        val section: String,
        val snapshot:
            () -> DeveloperExportSnapshot,
    )

    private val entries =
        linkedMapOf<String, Entry>()

    fun register(
        section: String,
        snapshot: () -> DeveloperExportSnapshot,
    ) {
        require(
            SECTION_NAME.matches(
                section,
            ),
        ) {
            "Invalid developer export section '$section'"
        }

        require(
            section !in entries,
        ) {
            "Developer export section '$section' is already registered"
        }

        entries[section] =
            Entry(
                section = section,
                snapshot = snapshot,
            )
    }

    fun sections(): List<String> =
        entries.keys.toList()

    fun snapshot(
        section: String,
    ): DeveloperExportSnapshot? =
        entries[section]
            ?.snapshot
            ?.invoke()

    companion object {
        private val SECTION_NAME =
            Regex(
                "[a-z][a-z0-9_.-]*",
            )
    }
}
