// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

/**
 * One sensor exposed to this process.
 *
 * This is an internal platform model, not a wire-protocol object. It keeps
 * enough Android metadata for the hardware census while allowing protocol,
 * UI and future provider layers to choose their own compact representation.
 */
data class SensorInventoryEntry(
    /**
     * Normalized logical sensor family, such as accelerometer or pressure.
     *
     * This is deliberately not assumed to be unique: Android may expose
     * multiple concrete sensors for one logical family.
     */
    val logicalId: String,

    /**
     * Android's process-visible sensor identifier.
     *
     * Keep this separate from [logicalId] so the census can distinguish
     * multiple concrete implementations of the same logical sensor family.
     */
    val androidId: Int,

    val androidType: Int,
    val stringType: String,
    val name: String,
    val vendor: String,
    val version: Int,
    val maxRange: Float,
    val resolution: Float,
    val powerMilliAmps: Float,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val fifoReservedEventCount: Int,
    val fifoMaxEventCount: Int,
    val reportingMode: Int,
    val wakeUp: Boolean,
)

/**
 * Read-only sensor census.
 *
 * Calling [snapshot] must not register listeners, activate a sensor, or retain
 * a sampling resource.
 */
fun interface SensorInventory {

    fun snapshot(): List<SensorInventoryEntry>
}
