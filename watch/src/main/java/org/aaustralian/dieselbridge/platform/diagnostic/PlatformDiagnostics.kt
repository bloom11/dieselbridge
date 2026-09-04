// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.diagnostic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.aaustralian.dieselbridge.platform.provider.ProviderBindingInfo

/**
 * One lightweight in-memory diagnostic record.
 */
data class DiagnosticRecord(
    val timestampMs: Long,
    val type: String,
    val message: String,
)

/**
 * Current platform diagnostic state.
 *
 * Diagnostics are deliberately process-local and bounded. Persistent logging
 * can be added later if it proves useful.
 */
data class PlatformDiagnosticsSnapshot(
    val providers: List<ProviderBindingInfo> = emptyList(),
    val recentRecords: List<DiagnosticRecord> = emptyList(),
)

/**
 * Structured observability for the Diesel runtime.
 *
 * The native UI, future JavaScript APIs and development tools can all observe
 * this same state without depending on Android Logcat.
 */
class PlatformDiagnostics(
    private val recentCapacity: Int = DEFAULT_RECENT_CAPACITY,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    init {
        require(recentCapacity > 0) {
            "Diagnostic record capacity must be greater than zero"
        }
    }

    private val lock = Any()

    private val mutableState =
        MutableStateFlow(PlatformDiagnosticsSnapshot())

    val state: StateFlow<PlatformDiagnosticsSnapshot> =
        mutableState.asStateFlow()

    fun replaceProviders(providers: List<ProviderBindingInfo>) {
        synchronized(lock) {
            mutableState.value =
                mutableState.value.copy(providers = providers)
        }
    }

    fun record(type: String, message: String) {
        require(type.isNotBlank()) {
            "Diagnostic record type must not be blank"
        }

        synchronized(lock) {
            val record = DiagnosticRecord(
                timestampMs = clock(),
                type = type,
                message = message,
            )

            val current = mutableState.value

            mutableState.value = current.copy(
                recentRecords =
                    (current.recentRecords + record)
                        .takeLast(recentCapacity),
            )
        }
    }

    companion object {
        const val DEFAULT_RECENT_CAPACITY = 100
    }
}
