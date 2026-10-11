// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import org.aaustralian.dieselbridge.protocol.DieselProtocolRules
import org.aaustralian.dieselbridge.protocol.DieselValue

data class DieselApiVersion(
    val major: Int,
    val minor: Int,
) {
    init {
        require(major > 0)
        require(minor >= 0)
    }

    companion object {
        val CURRENT = DieselApiVersion(major = 1, minor = 0)
    }
}

enum class DieselResourceKind {
    STATE,
    ACTION,
    OBSERVATION,
    EVENT,
    TRANSPORT,
}

enum class DieselResourceOperation {
    READ,
    INVOKE,
    SUBSCRIBE,
    PROVIDE,
    PUBLISH,
    OPEN,
}

/** Stable public metadata; never contains provider IDs or Android route IDs. */
data class DieselResourceDescriptor(
    val id: String,
    val kind: DieselResourceKind,
    val schema: String,
    val operations: Set<DieselResourceOperation>,
    val summary: String,
) {
    init {
        require(DieselProtocolRules.isValidIdentifier(id)) {
            "Invalid public Diesel resource id '$id'"
        }
        require(DieselProtocolRules.isValidIdentifier(schema)) {
            "Invalid public Diesel schema id '$schema'"
        }
        require(summary.isNotBlank()) {
            "Public Diesel resource summary must not be blank"
        }
        require(summary.length <= DieselProtocolRules.MAX_COMMAND_SUMMARY_LENGTH) {
            "Public Diesel resource summary is too long"
        }
        require(operations.isNotEmpty()) {
            "Public Diesel resource must expose at least one operation"
        }

        val allowed =
            when (kind) {
                DieselResourceKind.STATE ->
                    setOf(DieselResourceOperation.READ, DieselResourceOperation.PROVIDE)
                DieselResourceKind.ACTION ->
                    setOf(DieselResourceOperation.INVOKE, DieselResourceOperation.PROVIDE)
                DieselResourceKind.OBSERVATION ->
                    setOf(DieselResourceOperation.SUBSCRIBE, DieselResourceOperation.PROVIDE)
                DieselResourceKind.EVENT ->
                    setOf(DieselResourceOperation.SUBSCRIBE, DieselResourceOperation.PUBLISH)
                DieselResourceKind.TRANSPORT ->
                    setOf(DieselResourceOperation.OPEN)
            }

        require(operations.all { it in allowed }) {
            "Operations $operations are invalid for public Diesel kind $kind"
        }
    }
}

data class DieselCatalogSnapshot(
    val apiVersion: DieselApiVersion,
    val resources: List<DieselResourceDescriptor>,
)

/** Null value/revision/time means the logical state currently has no publication. */
data class DieselPublicStateSnapshot(
    val value: DieselValue?,
    val revision: Long?,
    val updatedAtMs: Long?,
) {
    init {
        if (value == null) {
            require(revision == null)
            require(updatedAtMs == null)
        } else {
            require(revision != null && revision > 0L)
            require(updatedAtMs != null && updatedAtMs >= 0L)
        }
    }
}

sealed interface DieselPublicStateResult {
    data class Success(val snapshot: DieselPublicStateSnapshot) : DieselPublicStateResult
    data class NotFound(val resourceId: String) : DieselPublicStateResult
    data class Failed(val reason: String) : DieselPublicStateResult
}

sealed interface DieselPublicDecodeResult<out T : Any> {
    data class Value<T : Any>(val value: T) : DieselPublicDecodeResult<T>
    data class Invalid(val reason: String) : DieselPublicDecodeResult<Nothing>
}

sealed interface DieselPublicActionResult {
    data class Success(val value: DieselValue) : DieselPublicActionResult
    data class NotFound(val resourceId: String) : DieselPublicActionResult
    data class InvalidInput(val reason: String) : DieselPublicActionResult
    data object Unavailable : DieselPublicActionResult
    data class Rejected(val reason: String) : DieselPublicActionResult
    data class Failed(val reason: String) : DieselPublicActionResult
}
