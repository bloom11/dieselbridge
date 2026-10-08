// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.aaustralian.dieselbridge.phone.DieselBridgePhoneApplication

class PhoneDieselInboundReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_DIESEL_MESSAGE) {
            return
        }

        val json =
            intent.getStringExtra(EXTRA_JSON)
                ?: return

        val application =
            context.applicationContext as?
                DieselBridgePhoneApplication
                ?: return

        val inbound =
            runCatching {
                PhoneDieselInboundCodec.parse(json)
            }.getOrNull()
                ?: return

        when (inbound) {
            is PhoneDieselInbound.Response ->
                application.runtime.responseRouter.accept(
                    inbound.value,
                )

            is PhoneDieselInbound.Event -> {
                if (
                    application.runtime.isKnownWakeEvent(
                        inbound.value.topic,
                    )
                ) {
                    application.runtime.requestWatchResync(
                        goAsync(),
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_DIESEL_MESSAGE =
            "io.github.bloom11.dieselbridge.DIESEL_MESSAGE"
        const val EXTRA_JSON = "json"
    }
}
