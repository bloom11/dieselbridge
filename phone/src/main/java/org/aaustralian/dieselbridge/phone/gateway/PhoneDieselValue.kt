// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

sealed interface PhoneDieselValue {
    data object Null : PhoneDieselValue
    data class Text(val value: String) : PhoneDieselValue
    data class Integer(val value: Long) : PhoneDieselValue
    data class Decimal(val value: Double) : PhoneDieselValue {
        init { require(value.isFinite()) }
    }
    data class Flag(val value: Boolean) : PhoneDieselValue
    data class ObjectValue(
        val value: Map<String, PhoneDieselValue>,
    ) : PhoneDieselValue
    data class ListValue(
        val value: List<PhoneDieselValue>,
    ) : PhoneDieselValue
}
