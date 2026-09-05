// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.capability.VibrationCapability
import org.aaustralian.dieselbridge.platform.diagnostic.PlatformDiagnostics

data class SafePlatformTestSpec(
    val name: String,
    val summary: String,
)

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
 * Registration is the single source of truth for both test discovery and
 * execution. This is deliberately not a reflection/method dispatcher.
 */
class SafePlatformTestRunner(
    private val capabilities: CapabilityRegistry,
    private val diagnostics: PlatformDiagnostics? = null,
    private val clock: () -> Long = {
        System.nanoTime() / 1_000_000L
    },
) {

    private data class Entry(
        val spec: SafePlatformTestSpec,
        val action: () -> SafePlatformTestResult,
    )

    private val entries =
        linkedMapOf<String, Entry>()

    private val vibrationLock = Any()

    private var lastVibrationAtMs: Long? = null

    init {
        register(
            spec =
                SafePlatformTestSpec(
                    name = TARGET_VIBRATION,
                    summary =
                        "Run a bounded 250 ms platform vibration test",
                ),
            action = ::runVibration,
        )
    }

    fun specs(): List<SafePlatformTestSpec> =
        entries.values.map {
            it.spec
        }

    fun run(
        target: String?,
    ): SafePlatformTestResult {
        val entry =
            target?.let {
                entries[it]
            }

        val result =
            if (entry == null) {
                SafePlatformTestResult.UnknownTarget(
                    target = target,
                )
            } else {
                entry.action()
            }

        diagnostics?.record(
            type = "developer-test",
            message = result.toDiagnosticMessage(),
        )

        return result
    }

    private fun register(
        spec: SafePlatformTestSpec,
        action: () -> SafePlatformTestResult,
    ) {
        require(TEST_NAME.matches(spec.name)) {
            "Invalid safe platform test name '${spec.name}'"
        }

        require(spec.summary.isNotBlank()) {
            "Safe platform test summary must not be blank"
        }

        require(spec.name !in entries) {
            "Safe platform test '${spec.name}' is already registered"
        }

        entries[spec.name] =
            Entry(
                spec = spec,
                action = action,
            )
    }

    private fun runVibration(): SafePlatformTestResult {
        synchronized(vibrationLock) {
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

    private fun SafePlatformTestResult.toDiagnosticMessage(): String =
        when (this) {
            is SafePlatformTestResult.Success ->
                "$target success provider=$providerId"

            is SafePlatformTestResult.Unavailable ->
                "$target unavailable"

            is SafePlatformTestResult.RateLimited ->
                "$target rate-limited retryAfterMs=$retryAfterMs"

            is SafePlatformTestResult.Failed ->
                "$target failed: $message"

            is SafePlatformTestResult.UnknownTarget ->
                "ignored unknown safe-test target: " +
                    (target ?: "<missing>")
        }

    companion object {
        const val TARGET_VIBRATION =
            "vibration"

        const val VIBRATION_TEST_DURATION_MS =
            250L

        const val MIN_VIBRATION_INTERVAL_MS =
            2_000L

        private val TEST_NAME =
            Regex("[a-z][a-z0-9_.-]*")
    }
}
