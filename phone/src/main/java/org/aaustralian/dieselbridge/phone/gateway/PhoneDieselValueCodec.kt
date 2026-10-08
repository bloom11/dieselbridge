// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import org.json.JSONArray
import org.json.JSONObject

object PhoneDieselValueCodec {
    private class Budget(var nodes: Int = 0)

    fun encodeObject(
        values: Map<String, PhoneDieselValue>,
    ): JSONObject =
        encodeObject(values, 0, Budget())

    fun decodeObject(
        value: JSONObject,
    ): Map<String, PhoneDieselValue> =
        decodeObject(value, 0, Budget())

    private fun encodeObject(
        values: Map<String, PhoneDieselValue>,
        depth: Int,
        budget: Budget,
    ): JSONObject {
        requireDepth(depth)
        require(
            values.size <= PhoneDieselProtocolRules.MAX_COLLECTION_ENTRIES,
        )
        return JSONObject().apply {
            values.forEach { (key, value) ->
                require(PhoneDieselProtocolRules.isValidFieldName(key))
                put(key, encodeValue(value, depth + 1, budget))
            }
        }
    }

    private fun encodeValue(
        value: PhoneDieselValue,
        depth: Int,
        budget: Budget,
    ): Any {
        consumeNode(budget)
        requireDepth(depth)
        return when (value) {
            PhoneDieselValue.Null -> JSONObject.NULL
            is PhoneDieselValue.Text -> {
                requireText(value.value)
                value.value
            }
            is PhoneDieselValue.Integer -> value.value
            is PhoneDieselValue.Decimal -> value.value
            is PhoneDieselValue.Flag -> value.value
            is PhoneDieselValue.ObjectValue ->
                encodeObject(value.value, depth, budget)
            is PhoneDieselValue.ListValue -> {
                require(
                    value.value.size <=
                        PhoneDieselProtocolRules.MAX_COLLECTION_ENTRIES,
                )
                JSONArray().apply {
                    value.value.forEach {
                        put(encodeValue(it, depth + 1, budget))
                    }
                }
            }
        }
    }

    private fun decodeObject(
        value: JSONObject,
        depth: Int,
        budget: Budget,
    ): Map<String, PhoneDieselValue> {
        requireDepth(depth)
        require(
            value.length() <=
                PhoneDieselProtocolRules.MAX_COLLECTION_ENTRIES,
        )
        val result = linkedMapOf<String, PhoneDieselValue>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            require(PhoneDieselProtocolRules.isValidFieldName(key))
            result[key] =
                decodeValue(value.get(key), depth + 1, budget)
        }
        return result
    }

    private fun decodeValue(
        value: Any?,
        depth: Int,
        budget: Budget,
    ): PhoneDieselValue {
        consumeNode(budget)
        requireDepth(depth)
        return when {
            value == null || value === JSONObject.NULL ->
                PhoneDieselValue.Null
            value is String -> {
                requireText(value)
                PhoneDieselValue.Text(value)
            }
            value is Boolean ->
                PhoneDieselValue.Flag(value)
            value is Byte || value is Short ||
                value is Int || value is Long ->
                PhoneDieselValue.Integer((value as Number).toLong())
            value is Number -> {
                val decimal = value.toDouble()
                require(decimal.isFinite())
                PhoneDieselValue.Decimal(decimal)
            }
            value is JSONObject ->
                PhoneDieselValue.ObjectValue(
                    decodeObject(value, depth, budget),
                )
            value is JSONArray -> {
                require(
                    value.length() <=
                        PhoneDieselProtocolRules.MAX_COLLECTION_ENTRIES,
                )
                val items = ArrayList<PhoneDieselValue>(value.length())
                for (index in 0 until value.length()) {
                    items +=
                        decodeValue(
                            value.get(index),
                            depth + 1,
                            budget,
                        )
                }
                PhoneDieselValue.ListValue(items)
            }
            else ->
                error("Unsupported Diesel JSON value")
        }
    }

    private fun consumeNode(budget: Budget) {
        budget.nodes += 1
        require(
            budget.nodes <= PhoneDieselProtocolRules.MAX_VALUE_NODES,
        )
    }

    private fun requireDepth(depth: Int) {
        require(depth <= PhoneDieselProtocolRules.MAX_VALUE_DEPTH)
    }

    private fun requireText(value: String) {
        require(
            value.toByteArray(Charsets.UTF_8).size <=
                PhoneDieselProtocolRules.MAX_TEXT_VALUE_BYTES,
        )
    }
}
