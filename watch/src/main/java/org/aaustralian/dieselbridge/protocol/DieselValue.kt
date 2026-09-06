// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

/**
 * Generic structured Diesel protocol value.
 *
 * This type is intentionally not response-specific. The same value tree can
 * be used by requests, responses, events and future plugin/module boundaries.
 */
sealed interface DieselValue {

    /**
     * Explicit JSON null.
     *
     * This is different from an absent map field and is useful for future
     * alarm, health, plugin and event payloads.
     */
    object Null : DieselValue

    data class Text(
        val value: String,
    ) : DieselValue

    data class Integer(
        val value: Long,
    ) : DieselValue

    data class Decimal(
        val value: Double,
    ) : DieselValue {
        init {
            require(value.isFinite()) {
                "Diesel decimal values must be finite"
            }
        }
    }

    data class Flag(
        val value: Boolean,
    ) : DieselValue

    data class ObjectValue(
        val value: Map<String, DieselValue>,
    ) : DieselValue

    data class ListValue(
        val value: List<DieselValue>,
    ) : DieselValue
}
