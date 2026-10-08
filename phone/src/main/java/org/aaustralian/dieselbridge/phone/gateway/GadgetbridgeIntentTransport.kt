// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import android.content.Context
import android.content.Intent

/**
 * Android -> stock Gadgetbridge Bangle.js request adapter.
 *
 * No package or device-MAC filter is applied in M6.0b. This intentionally
 * matches tools/diesel-adb and keeps target-selection policy out of the first
 * transport proof. M6.0d owns explicit multi-device/session policy.
 */
class GadgetbridgeIntentTransport(
    context: Context,
) : PhoneDieselTransport {

    private val applicationContext =
        context.applicationContext

    override fun submit(
        line: String,
    ) {
        require(
            line.isNotBlank(),
        ) {
            "Gadgetbridge line must not be blank"
        }

        applicationContext
            .sendBroadcast(
                Intent(
                    ACTION_UART_TX,
                ).putExtra(
                    EXTRA_LINE,
                    line,
                ),
            )
    }

    companion object {
        const val ACTION_UART_TX =
            "com.banglejs.uart.tx"

        const val EXTRA_LINE =
            "line"
    }
}
