// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import org.aaustralian.dieselbridge.platform.notification.PhoneCannedRepliesService
import org.aaustralian.dieselbridge.platform.notification.PhoneCannedRepliesState
import org.aaustralian.dieselbridge.platform.state.DieselStateStore
import org.aaustralian.dieselbridge.protocol.DieselValue

object PhoneCannedRepliesPublicApi {
    val STATE =
        DieselResourceDescriptor(
            id =
                PhoneCannedRepliesService.STATE_KEY.id,
            kind =
                DieselResourceKind.STATE,
            schema =
                "phone.notification.canned-replies.v1",
            operations =
                setOf(
                    DieselResourceOperation.READ,
                ),
            summary =
                "Bounded canned notification replies synchronized from the phone",
        )

    val STATE_IDS =
        setOf(
            STATE.id,
        )

    fun stateBinding(
        states: DieselStateStore,
    ): DieselPublicStateBinding =
        DieselPublicStateBinding.fromStateStore(
            states =
                states,
            descriptor =
                STATE,
            key =
                PhoneCannedRepliesService.STATE_KEY,
            encode =
                ::encodeState,
        )

    private fun encodeState(
        state: PhoneCannedRepliesState,
    ): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf(
                "replies" to
                    DieselValue.ListValue(
                        state.replies.map {
                            DieselValue.Text(
                                it,
                            )
                        },
                    ),
                "updatedAtMs" to
                    DieselValue.Integer(
                        state.updatedAtMs,
                    ),
            ),
        )
}
