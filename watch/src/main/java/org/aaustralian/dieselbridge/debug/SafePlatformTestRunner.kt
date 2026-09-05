// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.capability.VibrationCapability

sealed interface SafePlatformTestResult {

    data class Success(
        val target: String,
        val providerId: String,
    ) : SafePlatformTestResult

    data class Unavailable(
        val target: String,
    ) : SafePlatformTestResult

    data class RateLimited(
        val target: String,
        val retryAfterMs: Long,
    ) : SafePlatformTestResult

    data class Failed(
        val target: String,
        val message: String,
    ) : SafePlatformTestResult

    data class UnknownTarget(
        val target: String?,
    ) : SafePlatformTestResult
}

/**
 * Explicit allow-list of bounded developer tests that exercise the Diesel
 * platform API rather than Android or legacy implementations directly.
 *
 * This is deliberately not a reflection/method dispatcher.
 */
class SafePlatformTestRunner(
    private val capabilities: CapabilityRegistry,
    private val clock: () -> Long = {
        System.currentTimeMillis()
    },
) {

    private val lock = Any()

    private var lastVibrationAtMs: Long? = null

    fun run(
        target: String?,
    ): SafePlatformTestResult =
        when (target) {
            TARGET_VIBRATION ->
                runVibration()

            else ->
                SafePlatformTestResult.UnknownTarget(
                    target = target,
                )
        }

    private fun runVibration(): SafePlatformTestResult {
        synchronized(lock) {
            val now = clock()

            lastVibrationAtMs?.let { previous ->
                val elapsed = now - previous

                if (elapsed < MIN_VIBRATION_INTERVAL_MS) {
                    return SafePlatformTestResult.RateLimited(
                        target = TARGET_VIBRATION,
                        retryAfterMs =
                            MIN_VIBRATION_INTERVAL_MS - elapsed,
                    )
                }
            }

            val selection =
                capabilities
                    .observeActive(
                        VibrationCapability.ID,
                    )
                    .value
                    ?: return SafePlatformTestResult.Unavailable(
                        target = TARGET_VIBRATION,
                    )

            val vibration =
                selection.capability as? VibrationCapability
                    ?: return SafePlatformTestResult.Unavailable(
                        target = TARGET_VIBRATION,
                    )

            /*
             * Reserve the rate-limit window before executing. A failing
             * provider must not allow an unbounded retry loop.
             */
            lastVibrationAtMs = now

            return try {
                vibration.vibrate(
                    VIBRATION_TEST_DURATION_MS,
                )

                SafePlatformTestResult.Success(
                    target = TARGET_VIBRATION,
                    providerId = selection.providerId,
                )
            } catch (error: Exception) {
                SafePlatformTestResult.Failed(
                    target = TARGET_VIBRATION,
                    message =
                        error.message
                            ?: error.javaClass.simpleName,
                )
            }
        }
    }

    companion object {
        const val TARGET_VIBRATION =
            "vibration"

        const val VIBRATION_TEST_DURATION_MS =
            250L

        const val MIN_VIBRATION_INTERVAL_MS =
            2_000L
    }
}
