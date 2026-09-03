// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

/**
 * Capability for short watch vibration feedback.
 *
 * The platform API deliberately does not expose Android's Vibrator directly.
 * Callers such as native UI, automation and future JavaScript runtimes use
 * this interface instead.
 */
interface VibrationCapability : DieselCapability {

    override val id: String
        get() = ID

    fun vibrate(durationMs: Long = DEFAULT_DURATION_MS)

    companion object {
        const val ID = "vibration"
        const val DEFAULT_DURATION_MS = 400L
    }
}
