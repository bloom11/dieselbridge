// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import android.content.Context
import android.content.Intent

class GadgetbridgeIntentTransport(
    context: Context,
    private val deviceAddress: String? = null,
) : PhoneDieselTransport {
    private val applicationContext = context.applicationContext

    init {
        require(
            deviceAddress == null ||
                MAC_ADDRESS.matches(deviceAddress),
        )
    }

    override fun submit(line: String) {
        require(line.isNotBlank())
        val intent =
            Intent(ACTION_UART_TX)
                .putExtra(EXTRA_LINE, line)
        deviceAddress?.let {
            intent.putExtra(EXTRA_DEVICE, it)
        }
        applicationContext.sendBroadcast(intent)
    }

    companion object {
        const val ACTION_UART_TX = "com.banglejs.uart.tx"
        const val EXTRA_LINE = "line"
        const val EXTRA_DEVICE = "device"
        private val MAC_ADDRESS =
            Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
    }
}
