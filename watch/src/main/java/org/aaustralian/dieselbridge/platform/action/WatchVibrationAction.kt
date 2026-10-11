// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.action

import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.capability.VibrationCapability

data class WatchVibrationRequest(
    val durationMs: Long,
)

data class WatchVibrationResult(
    val durationMs: Long,
)

/**
 * Logical vibration action above CapabilityRegistry.
 *
 * The public IPC boundary dispatches this action. Only this handler resolves
 * the active provider-neutral vibration capability.
 */
object WatchVibrationAction {
    const val ID =
        "watch.vibration.buzz"

    const val OWNER_ID =
        "platform.action.watch.vibration"

    const val MIN_DURATION_MS =
        1L

    const val MAX_DURATION_MS =
        5_000L

    val KEY =
        DieselActionKey(
            id =
                ID,
            inputClass =
                WatchVibrationRequest::class.java,
            outputClass =
                WatchVibrationResult::class.java,
        )

    fun register(
        platform: DieselPlatform,
    ): DieselActionRegistration =
        platform.actions
            .register(
                key =
                    KEY,
                ownerId =
                    OWNER_ID,
            ) {
                request ->
                if (
                    request.durationMs !in
                    MIN_DURATION_MS..
                        MAX_DURATION_MS
                ) {
                    return@register DieselActionResult.Rejected(
                        "duration_out_of_bounds",
                    )
                }

                val capability =
                    platform.capabilities
                        .resolveAs<VibrationCapability>(
                            VibrationCapability.ID,
                        )
                        ?: return@register DieselActionResult.Unavailable

                capability.vibrate(
                    request.durationMs,
                )

                DieselActionResult.Success(
                    WatchVibrationResult(
                        durationMs =
                            request.durationMs,
                    ),
                )
            }
}
