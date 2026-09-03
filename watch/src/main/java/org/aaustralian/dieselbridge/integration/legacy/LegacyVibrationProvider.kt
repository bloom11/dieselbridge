// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.integration.legacy

import android.content.Context
import org.aaustralian.dieselbridge.notify.FindAlertController
import org.aaustralian.dieselbridge.platform.capability.VibrationCapability

/**
 * Exposes DieselBridge's existing vibration implementation through the
 * Diesel platform capability API.
 *
 * No vibrator logic is duplicated here. FindAlertController remains the
 * legacy implementation while this adapter provides the stable platform API.
 */
class LegacyVibrationProvider(
    context: Context,
) : VibrationCapability {

    private val appContext = context.applicationContext

    override fun vibrate(durationMs: Long) {
        require(durationMs > 0) {
            "Vibration duration must be greater than zero"
        }

        FindAlertController.buzzOnce(appContext, durationMs)
    }
}
