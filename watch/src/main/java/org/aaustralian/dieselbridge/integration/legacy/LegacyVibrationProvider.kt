// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.integration.legacy

import android.content.Context
import org.aaustralian.dieselbridge.notify.FindAlertController
import org.aaustralian.dieselbridge.platform.capability.VibrationCapability
import org.aaustralian.dieselbridge.platform.provider.DieselProvider

/**
 * Exposes DieselBridge's existing vibration implementation through the
 * Diesel platform capability API.
 *
 * No vibrator logic is duplicated here. FindAlertController remains the
 * legacy implementation while this adapter provides the stable platform API.
 */
class LegacyVibrationProvider(
    context: Context,
) : VibrationCapability, DieselProvider {

    private val appContext = context.applicationContext

    override val providerId: String
        get() = PROVIDER_ID

    override fun vibrate(durationMs: Long) {
        require(durationMs > 0) {
            "Vibration duration must be greater than zero"
        }

        FindAlertController.buzzOnce(appContext, durationMs)
    }

    companion object {
        const val PROVIDER_ID = "legacy.vibration"

        /**
         * Legacy implementation is currently our only vibration provider.
         * Explicit priority makes provider selection deterministic when a
         * future alternative implementation is added.
         */
        const val PRIORITY = 50
    }
}
