// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.action

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.capability.VibrationCapability
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchVibrationActionTest {
    @Test
    fun delegatesThroughRegistry() =
        runTest {
            val scope =
                CoroutineScope(
                    SupervisorJob(),
                )

            val platform =
                DieselPlatform(
                    scope,
                )

            var observed:
                Long? =
                null

            val provider =
                object :
                    VibrationCapability,
                    DieselProvider {
                    override val providerId =
                        "test.vibration"

                    override fun vibrate(
                        durationMs: Long,
                    ) {
                        observed =
                            durationMs
                    }
                }

            platform.capabilities.register(
                capability =
                    provider,
                provider =
                    provider,
            )

            val registration =
                WatchVibrationAction.register(
                    platform,
                )

            assertEquals(
                DieselActionResult.Success(
                    WatchVibrationResult(
                        250L,
                    ),
                ),
                platform.actions.dispatch(
                    WatchVibrationAction.KEY,
                    WatchVibrationRequest(
                        250L,
                    ),
                ),
            )

            assertEquals(
                250L,
                observed,
            )

            registration.close()
            scope.coroutineContext[
                Job
            ]?.cancel()
        }

    @Test
    fun unavailableWithoutProvider() =
        runTest {
            val scope =
                CoroutineScope(
                    SupervisorJob(),
                )

            val platform =
                DieselPlatform(
                    scope,
                )

            val registration =
                WatchVibrationAction.register(
                    platform,
                )

            assertEquals(
                DieselActionResult.Unavailable,
                platform.actions.dispatch(
                    WatchVibrationAction.KEY,
                    WatchVibrationRequest(
                        100L,
                    ),
                ),
            )

            registration.close()
            scope.coroutineContext[
                Job
            ]?.cancel()
        }

    @Test
    fun rejectsInvalidDuration() =
        runTest {
            val scope =
                CoroutineScope(
                    SupervisorJob(),
                )

            val platform =
                DieselPlatform(
                    scope,
                )

            val registration =
                WatchVibrationAction.register(
                    platform,
                )

            assertEquals(
                DieselActionResult.Rejected(
                    "duration_out_of_bounds",
                ),
                platform.actions.dispatch(
                    WatchVibrationAction.KEY,
                    WatchVibrationRequest(
                        0L,
                    ),
                ),
            )

            registration.close()
            scope.coroutineContext[
                Job
            ]?.cancel()
        }
}
