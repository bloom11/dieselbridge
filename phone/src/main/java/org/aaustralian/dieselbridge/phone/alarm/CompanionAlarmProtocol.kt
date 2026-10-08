// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.alarm

import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselResponse
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselResponseStatus
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselValue

object CompanionAlarmProtocol {
    const val COMMAND_SYNC = "companion.alarm.next.sync"
    const val COMMAND_GET = "companion.alarm.next.get"
    const val TOPIC_SYNC_REQUEST = "companion.sync.request"

    fun toArgs(
        observation: NextAlarmObservation,
    ): Map<String, PhoneDieselValue> =
        when (observation) {
            is NextAlarmObservation.Scheduled ->
                linkedMapOf(
                    "kind" to PhoneDieselValue.Text("scheduled"),
                    "triggerAtMs" to
                        PhoneDieselValue.Integer(
                            observation.triggerAtMs,
                        ),
                    "observedAtMs" to
                        PhoneDieselValue.Integer(
                            observation.observedAtMs,
                        ),
                )
            is NextAlarmObservation.None ->
                linkedMapOf(
                    "kind" to PhoneDieselValue.Text("none"),
                    "observedAtMs" to
                        PhoneDieselValue.Integer(
                            observation.observedAtMs,
                        ),
                )
            is NextAlarmObservation.Unavailable ->
                linkedMapOf(
                    "kind" to PhoneDieselValue.Text("unavailable"),
                    "reason" to
                        PhoneDieselValue.Text(observation.reason),
                    "observedAtMs" to
                        PhoneDieselValue.Integer(
                            observation.observedAtMs,
                        ),
                )
        }

    fun matchesAcknowledgement(
        observation: NextAlarmObservation,
        response: PhoneDieselResponse,
    ): Boolean {
        if (
            response.command != COMMAND_SYNC ||
            response.status != PhoneDieselResponseStatus.OK
        ) {
            return false
        }

        val kind =
            (response.data["kind"] as? PhoneDieselValue.Text)
                ?.value
                ?: return false

        return when (observation) {
            is NextAlarmObservation.Scheduled ->
                kind == "scheduled" &&
                    (response.data["triggerAtMs"] as?
                        PhoneDieselValue.Integer)
                        ?.value == observation.triggerAtMs
            is NextAlarmObservation.None ->
                kind == "none"
            is NextAlarmObservation.Unavailable ->
                kind == "unavailable"
        }
    }
}
