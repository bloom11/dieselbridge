// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.companion

import org.aaustralian.dieselbridge.platform.state.DieselStateKey
import org.aaustralian.dieselbridge.platform.state.DieselStateSnapshot
import org.aaustralian.dieselbridge.platform.state.DieselStateStore
import org.aaustralian.dieselbridge.platform.state.DieselStatePublisher

enum class CompanionAlarmKind(
    val wireName: String,
) {
    SCHEDULED("scheduled"),
    NONE("none"),
    UNAVAILABLE("unavailable");

    companion object {
        fun fromWire(value: String): CompanionAlarmKind? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class CompanionNextAlarmState(
    val kind: CompanionAlarmKind,
    val triggerAtMs: Long?,
    val observedAtMs: Long,
    val receivedAtMs: Long,
    val reason: String?,
) {
    init {
        require(observedAtMs >= 0L)
        require(receivedAtMs >= 0L)
        when (kind) {
            CompanionAlarmKind.SCHEDULED -> {
                require(triggerAtMs != null && triggerAtMs > 0L)
                require(reason == null)
            }
            CompanionAlarmKind.NONE -> {
                require(triggerAtMs == null)
                require(reason == null)
            }
            CompanionAlarmKind.UNAVAILABLE -> {
                require(triggerAtMs == null)
                require(!reason.isNullOrBlank())
            }
        }
    }
}

data class CompanionAlarmApplyResult(
    val snapshot:
        DieselStateSnapshot<CompanionNextAlarmState>,
)

class CompanionAlarmStateOwner(
    private val states: DieselStateStore,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val publisher:
        DieselStatePublisher<CompanionNextAlarmState> =
        states.registerPublisher(
            key = KEY,
            ownerId = OWNER_ID,
        )

    fun apply(
        kind: CompanionAlarmKind,
        triggerAtMs: Long?,
        observedAtMs: Long,
        reason: String?,
    ): CompanionAlarmApplyResult {
        val state =
            CompanionNextAlarmState(
                kind = kind,
                triggerAtMs = triggerAtMs,
                observedAtMs = observedAtMs,
                receivedAtMs = clockMs(),
                reason = reason,
            )

        check(publisher.set(state)) {
            "Companion alarm publisher is closed"
        }

        return CompanionAlarmApplyResult(
            snapshot =
                checkNotNull(states.current(KEY)),
        )
    }

    fun current():
        DieselStateSnapshot<CompanionNextAlarmState>? =
        states.current(KEY)

    override fun close() {
        publisher.close()
    }

    companion object {
        const val OWNER_ID = "companion.alarm"

        val KEY =
            DieselStateKey(
                id = "companion.alarm.next",
                valueClass =
                    CompanionNextAlarmState::class.java,
            )
    }
}
