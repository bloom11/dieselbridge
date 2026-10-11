// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import org.aaustralian.dieselbridge.companion.CompanionAlarmStateOwner
import org.aaustralian.dieselbridge.companion.CompanionNextAlarmState
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.action.WatchVibrationAction
import org.aaustralian.dieselbridge.platform.action.WatchVibrationRequest
import org.aaustralian.dieselbridge.platform.action.WatchVibrationResult
import org.aaustralian.dieselbridge.protocol.DieselValue

object DieselDefaultPublicApi {
    val COMPANION_NEXT_ALARM =
        DieselResourceDescriptor(
            id =
                CompanionAlarmStateOwner.KEY.id,
            kind =
                DieselResourceKind.STATE,
            schema =
                "companion.alarm.next.v1",
            operations =
                setOf(
                    DieselResourceOperation.READ,
                ),
            summary =
                "Current next-alarm occurrence synchronized from the phone companion",
        )

    val WATCH_VIBRATION =
        DieselResourceDescriptor(
            id =
                WatchVibrationAction.ID,
            kind =
                DieselResourceKind.ACTION,
            schema =
                "watch.vibration.buzz.v1",
            operations =
                setOf(
                    DieselResourceOperation.INVOKE,
                ),
            summary =
                "Request bounded watch vibration through the active Diesel provider",
        )

    fun create(
        platform: DieselPlatform,
    ): DieselPublicApi =
        DieselPublicApi(
            stateBindings =
                listOf(
                    DieselPublicStateBinding
                        .fromStateStore(
                            states =
                                platform.states,
                            descriptor =
                                COMPANION_NEXT_ALARM,
                            key =
                                CompanionAlarmStateOwner.KEY,
                            encode =
                                ::encodeCompanionNextAlarm,
                        ),
                ),
            actionBindings =
                listOf(
                    DieselPublicActionBinding
                        .fromActionDispatcher(
                            actions =
                                platform.actions,
                            descriptor =
                                WATCH_VIBRATION,
                            key =
                                WatchVibrationAction.KEY,
                            decode =
                                ::decodeVibration,
                            encode =
                                ::encodeVibrationResult,
                        ),
                ),
        )

    private fun decodeVibration(
        value: DieselValue,
    ): DieselPublicDecodeResult<WatchVibrationRequest> {
        val fields =
            (
                value as?
                    DieselValue.ObjectValue
            )
                ?.value
                ?: return DieselPublicDecodeResult.Invalid(
                    "object_required",
                )

        if (
            fields.keys !=
            setOf(
                "durationMs",
            )
        ) {
            return DieselPublicDecodeResult.Invalid(
                "invalid_fields",
            )
        }

        val duration =
            (
                fields[
                    "durationMs"
                ] as?
                    DieselValue.Integer
            )
                ?.value
                ?: return DieselPublicDecodeResult.Invalid(
                    "duration_integer_required",
                )

        if (
            duration !in
            WatchVibrationAction.MIN_DURATION_MS..
                WatchVibrationAction.MAX_DURATION_MS
        ) {
            return DieselPublicDecodeResult.Invalid(
                "duration_out_of_bounds",
            )
        }

        return DieselPublicDecodeResult.Value(
            WatchVibrationRequest(
                durationMs =
                    duration,
            ),
        )
    }

    private fun encodeVibrationResult(
        result: WatchVibrationResult,
    ): DieselValue =
        DieselValue.ObjectValue(
            mapOf(
                "durationMs" to
                    DieselValue.Integer(
                        result.durationMs,
                    ),
            ),
        )

    private fun encodeCompanionNextAlarm(
        state: CompanionNextAlarmState,
    ): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf<String, DieselValue>()
                .apply {
                    put(
                        "kind",
                        DieselValue.Text(
                            state.kind.wireName,
                        ),
                    )
                    put(
                        "observedAtMs",
                        DieselValue.Integer(
                            state.observedAtMs,
                        ),
                    )
                    put(
                        "receivedAtMs",
                        DieselValue.Integer(
                            state.receivedAtMs,
                        ),
                    )
                    state.triggerAtMs?.let {
                        put(
                            "triggerAtMs",
                            DieselValue.Integer(
                                it,
                            ),
                        )
                    }
                    state.reason?.let {
                        put(
                            "reason",
                            DieselValue.Text(
                                it,
                            ),
                        )
                    }
                },
        )
}
