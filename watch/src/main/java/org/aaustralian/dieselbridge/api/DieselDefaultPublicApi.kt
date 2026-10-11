// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import org.aaustralian.dieselbridge.companion.CompanionAlarmStateOwner
import org.aaustralian.dieselbridge.companion.CompanionNextAlarmState
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.protocol.DieselValue

/** Production bindings whose logical contract is already established by the existing core. */
object DieselDefaultPublicApi {
    val COMPANION_NEXT_ALARM =
        DieselResourceDescriptor(
            id = CompanionAlarmStateOwner.KEY.id,
            kind = DieselResourceKind.STATE,
            schema = "companion.alarm.next.v1",
            operations = setOf(DieselResourceOperation.READ),
            summary = "Current next-alarm occurrence synchronized from the phone companion",
        )

    fun create(platform: DieselPlatform): DieselPublicApi =
        DieselPublicApi(
            stateBindings =
                listOf(
                    DieselPublicStateBinding.fromStateStore(
                        states = platform.states,
                        descriptor = COMPANION_NEXT_ALARM,
                        key = CompanionAlarmStateOwner.KEY,
                        encode = ::encodeCompanionNextAlarm,
                    ),
                ),
        )

    private fun encodeCompanionNextAlarm(state: CompanionNextAlarmState): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf<String, DieselValue>().apply {
                put("kind", DieselValue.Text(state.kind.wireName))
                put("observedAtMs", DieselValue.Integer(state.observedAtMs))
                put("receivedAtMs", DieselValue.Integer(state.receivedAtMs))
                state.triggerAtMs?.let { put("triggerAtMs", DieselValue.Integer(it)) }
                state.reason?.let { put("reason", DieselValue.Text(it)) }
            },
        )
}
