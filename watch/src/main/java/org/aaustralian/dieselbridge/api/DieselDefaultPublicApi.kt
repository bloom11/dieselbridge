// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import org.aaustralian.dieselbridge.companion.CompanionAlarmStateOwner
import org.aaustralian.dieselbridge.companion.CompanionNextAlarmState
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.action.WatchVibrationAction
import org.aaustralian.dieselbridge.platform.action.WatchVibrationRequest
import org.aaustralian.dieselbridge.platform.action.WatchVibrationResult
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityCatalog
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapabilityId
import org.aaustralian.dieselbridge.protocol.DieselValue

object DieselDefaultPublicApi {
    val COMPANION_NEXT_ALARM = DieselResourceDescriptor(
        CompanionAlarmStateOwner.KEY.id,
        DieselResourceKind.STATE,
        "companion.alarm.next.v1",
        setOf(DieselResourceOperation.READ),
        "Current next-alarm occurrence synchronized from the phone companion",
    )

    val WATCH_VIBRATION = DieselResourceDescriptor(
        WatchVibrationAction.ID,
        DieselResourceKind.ACTION,
        "watch.vibration.buzz.v1",
        setOf(DieselResourceOperation.INVOKE),
        "Request bounded watch vibration through the active Diesel provider",
    )

    val OBSERVATIONS: List<DieselPublicObservationBinding> =
        SensorCapabilityCatalog.standardLogicalIds.map { logicalId ->
            val id = SensorObservationCapabilityId.forLogical(logicalId).value
            DieselPublicObservationBinding(
                DieselResourceDescriptor(
                    id,
                    DieselResourceKind.OBSERVATION,
                    "sensor.reading.v1",
                    setOf(DieselResourceOperation.SUBSCRIBE),
                    "Provider-neutral logical $logicalId observation",
                ),
                logicalId,
            )
        }

    val OBSERVATION_IDS: Set<String> = OBSERVATIONS.map { it.descriptor.id }.toSet()

    fun create(platform: DieselPlatform): DieselPublicApi = DieselPublicApi(
        stateBindings = listOf(
            DieselPublicStateBinding.fromStateStore(
                platform.states,
                COMPANION_NEXT_ALARM,
                CompanionAlarmStateOwner.KEY,
                ::encodeAlarm,
            ),
        ),
        actionBindings = listOf(
            DieselPublicActionBinding.fromActionDispatcher(
                platform.actions,
                WATCH_VIBRATION,
                WatchVibrationAction.KEY,
                ::decodeVibration,
                ::encodeVibration,
            ),
        ),
        observationBindings = OBSERVATIONS,
    )

    private fun decodeVibration(value: DieselValue): DieselPublicDecodeResult<WatchVibrationRequest> {
        val fields = (value as? DieselValue.ObjectValue)?.value
            ?: return DieselPublicDecodeResult.Invalid("object_required")
        if (fields.keys != setOf("durationMs")) return DieselPublicDecodeResult.Invalid("invalid_fields")
        val duration = (fields["durationMs"] as? DieselValue.Integer)?.value
            ?: return DieselPublicDecodeResult.Invalid("duration_integer_required")
        if (duration !in WatchVibrationAction.MIN_DURATION_MS..WatchVibrationAction.MAX_DURATION_MS) {
            return DieselPublicDecodeResult.Invalid("duration_out_of_bounds")
        }
        return DieselPublicDecodeResult.Value(WatchVibrationRequest(duration))
    }

    private fun encodeVibration(result: WatchVibrationResult): DieselValue =
        DieselValue.ObjectValue(mapOf("durationMs" to DieselValue.Integer(result.durationMs)))

    private fun encodeAlarm(state: CompanionNextAlarmState): DieselValue =
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
