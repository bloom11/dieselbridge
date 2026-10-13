// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import org.aaustralian.dieselbridge.platform.action.DieselActionDispatcher
import org.aaustralian.dieselbridge.platform.call.PhoneCallActionResult
import org.aaustralian.dieselbridge.platform.call.PhoneCallChangedEvent
import org.aaustralian.dieselbridge.platform.call.PhoneCallService
import org.aaustralian.dieselbridge.platform.call.PhoneCallState
import org.aaustralian.dieselbridge.platform.state.DieselStateStore
import org.aaustralian.dieselbridge.protocol.DieselValue

object PhoneCallPublicApi {
    val CURRENT =
        DieselResourceDescriptor(
            id =
                PhoneCallService.CURRENT_KEY.id,
            kind =
                DieselResourceKind.STATE,
            schema =
                "phone.call.current.v1",
            operations =
                setOf(
                    DieselResourceOperation.READ,
                ),
            summary =
                "Current phone-call phase, direction and caller identity",
        )

    val ACCEPT =
        actionDescriptor(
            id =
                PhoneCallService.ACCEPT_KEY.id,
            schema =
                "phone.call.accept.v1",
            summary =
                "Accept the current ringing phone call",
        )

    val REJECT =
        actionDescriptor(
            id =
                PhoneCallService.REJECT_KEY.id,
            schema =
                "phone.call.reject.v1",
            summary =
                "Reject the current ringing phone call",
        )

    val IGNORE =
        actionDescriptor(
            id =
                PhoneCallService.IGNORE_KEY.id,
            schema =
                "phone.call.ignore.v1",
            summary =
                "Ignore the current ringing phone call",
        )

    val END =
        actionDescriptor(
            id =
                PhoneCallService.END_KEY.id,
            schema =
                "phone.call.end.v1",
            summary =
                "End the current active phone call",
        )

    val CHANGED =
        DieselResourceDescriptor(
            id =
                PhoneCallChangedEvent.CHANGED_EVENT_ID,
            kind =
                DieselResourceKind.EVENT,
            schema =
                "phone.call.changed.v1",
            operations =
                setOf(
                    DieselResourceOperation.SUBSCRIBE,
                ),
            summary =
                "Phone-call state changed",
        )

    val STATE_IDS =
        setOf(
            CURRENT.id,
        )

    val ACTION_IDS =
        setOf(
            ACCEPT.id,
            REJECT.id,
            IGNORE.id,
            END.id,
        )

    val EVENT_IDS =
        setOf(
            CHANGED.id,
        )

    fun stateBinding(
        states: DieselStateStore,
    ): DieselPublicStateBinding =
        DieselPublicStateBinding.fromStateStore(
            states =
                states,
            descriptor =
                CURRENT,
            key =
                PhoneCallService.CURRENT_KEY,
            encode =
                ::encodeState,
        )

    fun actionBindings(
        actions: DieselActionDispatcher,
    ): List<DieselPublicActionBinding> =
        listOf(
            DieselPublicActionBinding.fromActionDispatcher(
                actions =
                    actions,
                descriptor =
                    ACCEPT,
                key =
                    PhoneCallService.ACCEPT_KEY,
                decode =
                    ::decodeNoInput,
                encode =
                    ::encodeActionResult,
            ),
            DieselPublicActionBinding.fromActionDispatcher(
                actions =
                    actions,
                descriptor =
                    REJECT,
                key =
                    PhoneCallService.REJECT_KEY,
                decode =
                    ::decodeNoInput,
                encode =
                    ::encodeActionResult,
            ),
            DieselPublicActionBinding.fromActionDispatcher(
                actions =
                    actions,
                descriptor =
                    IGNORE,
                key =
                    PhoneCallService.IGNORE_KEY,
                decode =
                    ::decodeNoInput,
                encode =
                    ::encodeActionResult,
            ),
            DieselPublicActionBinding.fromActionDispatcher(
                actions =
                    actions,
                descriptor =
                    END,
                key =
                    PhoneCallService.END_KEY,
                decode =
                    ::decodeNoInput,
                encode =
                    ::encodeActionResult,
            ),
        )

    fun eventBinding():
        DieselPublicEventBinding =
        DieselPublicEventBinding(
            descriptor =
                CHANGED,
            matches =
                {
                    it is
                        PhoneCallChangedEvent
                },
            encode =
                {
                    encodeEvent(
                        it as
                            PhoneCallChangedEvent,
                    )
                },
        )

    private fun actionDescriptor(
        id: String,
        schema: String,
        summary: String,
    ) =
        DieselResourceDescriptor(
            id =
                id,
            kind =
                DieselResourceKind.ACTION,
            schema =
                schema,
            operations =
                setOf(
                    DieselResourceOperation.INVOKE,
                ),
            summary =
                summary,
        )

    private fun decodeNoInput(
        value: DieselValue,
    ): DieselPublicDecodeResult<Unit> =
        when (value) {
            DieselValue.Null ->
                DieselPublicDecodeResult.Value(
                    Unit,
                )

            is DieselValue.ObjectValue ->
                if (
                    value.value.isEmpty()
                ) {
                    DieselPublicDecodeResult.Value(
                        Unit,
                    )
                } else {
                    DieselPublicDecodeResult.Invalid(
                        "empty_object_required",
                    )
                }

            else ->
                DieselPublicDecodeResult.Invalid(
                    "null_or_empty_object_required",
                )
        }

    private fun encodeActionResult(
        result: PhoneCallActionResult,
    ): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf(
                "sent" to
                    DieselValue.Flag(
                        result.sent,
                    ),
                "phase" to
                    DieselValue.Text(
                        result.phase.wireName,
                    ),
            ),
        )

    private fun encodeEvent(
        event: PhoneCallChangedEvent,
    ): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf(
                "reason" to
                    DieselValue.Text(
                        event.reason,
                    ),
                "call" to
                    encodeState(
                        event.state,
                    ),
            ),
        )

    private fun encodeState(
        state: PhoneCallState,
    ): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf<String, DieselValue>().apply {
                put(
                    "phase",
                    DieselValue.Text(
                        state.phase.wireName,
                    ),
                )
                put(
                    "direction",
                    DieselValue.Text(
                        state.direction.wireName,
                    ),
                )
                put(
                    "updatedAtMs",
                    DieselValue.Integer(
                        state.updatedAtMs,
                    ),
                )
                state.name
                    ?.let {
                        put(
                            "name",
                            DieselValue.Text(
                                it,
                            ),
                        )
                    }
                state.number
                    ?.let {
                        put(
                            "number",
                            DieselValue.Text(
                                it,
                            ),
                        )
                    }
            },
        )
}
