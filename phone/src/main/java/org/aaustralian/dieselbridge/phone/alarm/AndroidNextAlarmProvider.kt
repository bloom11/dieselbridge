// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.alarm

import android.app.AlarmManager
import android.content.Context

class AndroidNextAlarmProvider(
    context: Context,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : NextAlarmProvider {
    private val applicationContext = context.applicationContext

    override fun read(): NextAlarmObservation {
        val observedAtMs = clockMs()

        return try {
            val alarmManager =
                applicationContext.getSystemService(
                    AlarmManager::class.java,
                ) ?: return NextAlarmObservation.Unavailable(
                    reason = "alarm_service_unavailable",
                    observedAtMs = observedAtMs,
                )

            val next = alarmManager.nextAlarmClock

            if (next == null) {
                NextAlarmObservation.None(
                    observedAtMs = observedAtMs,
                )
            } else {
                NextAlarmObservation.Scheduled(
                    triggerAtMs = next.triggerTime,
                    observedAtMs = observedAtMs,
                )
            }
        } catch (error: Exception) {
            NextAlarmObservation.Unavailable(
                reason =
                    "alarm_read_failed_" +
                        error.javaClass.simpleName.lowercase(),
                observedAtMs = observedAtMs,
            )
        }
    }
}
