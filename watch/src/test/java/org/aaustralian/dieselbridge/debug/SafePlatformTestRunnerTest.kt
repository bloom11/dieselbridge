// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.capability.VibrationCapability
import org.aaustralian.dieselbridge.platform.diagnostic.PlatformDiagnostics
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafePlatformTestRunnerTest {

    @Test
    fun specsExposeRegisteredSafeTests() {
        val runner =
            SafePlatformTestRunner(
                capabilities = CapabilityRegistry(),
            )

        assertEquals(
            listOf(
                SafePlatformTestSpec(
                    name = "vibration",
                    summary =
                        "Run a bounded 250 ms platform vibration test",
                ),
            ),
            runner.specs(),
        )
    }

    @Test
    fun vibrationUsesSelectedPlatformProviderWithFixedDuration() {
        val registry =
            CapabilityRegistry()

        val vibration =
            FakeVibrationProvider(
                providerId = "test.vibration",
            )

        registry.register(
            capability = vibration,
            provider = vibration,
        )

        val runner =
            SafePlatformTestRunner(
                capabilities = registry,
                clock = { 10_000L },
            )

        assertEquals(
            SafePlatformTestResult.Success(
                target = "vibration",
                providerId = "test.vibration",
            ),
            runner.run("vibration"),
        )

        assertEquals(
            listOf(250L),
            vibration.durations,
        )
    }

    @Test
    fun vibrationIsUnavailableWithoutSelectedProvider() {
        val runner =
            SafePlatformTestRunner(
                capabilities = CapabilityRegistry(),
            )

        assertEquals(
            SafePlatformTestResult.Unavailable(
                target = "vibration",
            ),
            runner.run("vibration"),
        )
    }

    @Test
    fun unknownTargetCannotInvokeRegisteredCapability() {
        val registry =
            CapabilityRegistry()

        val vibration =
            FakeVibrationProvider(
                providerId = "test.vibration",
            )

        registry.register(
            capability = vibration,
            provider = vibration,
        )

        val runner =
            SafePlatformTestRunner(
                capabilities = registry,
            )

        assertEquals(
            SafePlatformTestResult.UnknownTarget(
                target = "shell",
            ),
            runner.run("shell"),
        )

        assertEquals(
            SafePlatformTestResult.UnknownTarget(
                target = null,
            ),
            runner.run(null),
        )

        assertTrue(
            vibration.durations.isEmpty(),
        )
    }

    @Test
    fun repeatedVibrationIsRateLimited() {
        val registry =
            CapabilityRegistry()

        val vibration =
            FakeVibrationProvider(
                providerId = "test.vibration",
            )

        registry.register(
            capability = vibration,
            provider = vibration,
        )

        var now = 10_000L

        val runner =
            SafePlatformTestRunner(
                capabilities = registry,
                clock = { now },
            )

        assertTrue(
            runner.run("vibration")
                is SafePlatformTestResult.Success,
        )

        now = 10_500L

        assertEquals(
            SafePlatformTestResult.RateLimited(
                target = "vibration",
                retryAfterMs = 1_500L,
            ),
            runner.run("vibration"),
        )

        assertEquals(
            listOf(250L),
            vibration.durations,
        )
    }

    @Test
    fun providerFailureIsReportedAndBounded() {
        val registry =
            CapabilityRegistry()

        val vibration =
            FakeVibrationProvider(
                providerId = "broken.vibration",
                failure =
                    IllegalStateException(
                        "provider failed",
                    ),
            )

        registry.register(
            capability = vibration,
            provider = vibration,
        )

        val runner =
            SafePlatformTestRunner(
                capabilities = registry,
                clock = { 10_000L },
            )

        assertEquals(
            SafePlatformTestResult.Failed(
                target = "vibration",
                message = "provider failed",
            ),
            runner.run("vibration"),
        )
    }

    @Test
    fun resultsAreRecordedInPlatformDiagnostics() {
        val diagnostics =
            PlatformDiagnostics(
                clock = { 42L },
            )

        val runner =
            SafePlatformTestRunner(
                capabilities = CapabilityRegistry(),
                diagnostics = diagnostics,
            )

        runner.run("vibration")

        val record =
            diagnostics
                .state
                .value
                .recentRecords
                .single()

        assertEquals(
            "developer-test",
            record.type,
        )

        assertEquals(
            "vibration unavailable",
            record.message,
        )
    }

    private class FakeVibrationProvider(
        override val providerId: String,
        private val failure: Exception? = null,
    ) : VibrationCapability, DieselProvider {

        val durations =
            mutableListOf<Long>()

        override fun vibrate(
            durationMs: Long,
        ) {
            failure?.let {
                throw it
            }

            durations += durationMs
        }
    }
}
