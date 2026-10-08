// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.alarm

sealed interface NextAlarmObservation {
    val observedAtMs: Long

    data class Scheduled(
        val triggerAtMs: Long,
        override val observedAtMs: Long,
    ) : NextAlarmObservation {
        init { require(triggerAtMs > 0L) }
    }

    data class None(
        override val observedAtMs: Long,
    ) : NextAlarmObservation

    data class Unavailable(
        val reason: String,
        override val observedAtMs: Long,
    ) : NextAlarmObservation {
        init { require(reason.isNotBlank()) }
    }
}

fun interface NextAlarmProvider {
    fun read(): NextAlarmObservation
}
