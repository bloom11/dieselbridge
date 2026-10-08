// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone

import android.app.AlarmManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.aaustralian.dieselbridge.phone.alarm.AlarmSyncCoordinator
import org.aaustralian.dieselbridge.phone.alarm.AndroidNextAlarmProvider
import org.aaustralian.dieselbridge.phone.alarm.CompanionAlarmProtocol
import org.aaustralian.dieselbridge.phone.gateway.GadgetbridgeIntentTransport
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselGateway
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselInboundReceiver
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselResponseRouter

class PhoneCompanionRuntime(
    private val application: Application,
) {
    val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default,
        )

    val responseRouter =
        PhoneDieselResponseRouter()

    val gateway =
        PhoneDieselGateway(
            transport =
                GadgetbridgeIntentTransport(application),
            responses = responseRouter,
        )

    val nextAlarmProvider =
        AndroidNextAlarmProvider(application)

    val alarmSync =
        AlarmSyncCoordinator(
            provider = nextAlarmProvider,
            executor = gateway,
            scope = scope,
        )

    private val dynamicInboundReceiver =
        PhoneDieselInboundReceiver()

    private val alarmChangedReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: android.content.Context,
                intent: Intent,
            ) {
                if (
                    intent.action ==
                        AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED
                ) {
                    alarmSync.requestRefresh(
                        "android_alarm_changed",
                    )
                }
            }
        }

    fun start() {
        ContextCompat.registerReceiver(
            application,
            dynamicInboundReceiver,
            IntentFilter(
                PhoneDieselInboundReceiver.ACTION_DIESEL_MESSAGE,
            ),
            ContextCompat.RECEIVER_EXPORTED,
        )

        ContextCompat.registerReceiver(
            application,
            alarmChangedReceiver,
            IntentFilter(
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
            ),
            ContextCompat.RECEIVER_EXPORTED,
        )

        alarmSync.requestRefresh("process_start")
    }

    fun requestWatchResync(
        pending: BroadcastReceiver.PendingResult?,
    ) {
        if (pending == null) {
            alarmSync.requestRefresh("watch_subscribed")
            return
        }

        scope.launch {
            try {
                withTimeoutOrNull(9_000L) {
                    alarmSync.syncOnce("watch_subscribed")
                }
            } finally {
                pending.finish()
            }
        }
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    fun isKnownWakeEvent(topic: String): Boolean =
        topic == CompanionAlarmProtocol.TOPIC_SYNC_REQUEST
}
