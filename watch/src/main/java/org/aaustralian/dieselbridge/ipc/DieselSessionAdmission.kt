// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ipc

import org.aaustralian.dieselbridge.api.DieselApiVersion

internal enum class DieselSessionOpenRejection(
    val wireStatus: String,
) {
    INVALID_REQUEST(
        "invalid_request",
    ),
    UNSUPPORTED_VERSION(
        "unsupported_version",
    ),
    INVALID_CLIENT(
        "invalid_client",
    ),
    RUNTIME_UNAVAILABLE(
        "runtime_unavailable",
    ),
    SESSION_LIMIT(
        "session_limit",
    ),
}

internal sealed interface DieselSessionOpenDecision {
    data object Allowed :
        DieselSessionOpenDecision

    data class Rejected(
        val reason: DieselSessionOpenRejection,
    ) : DieselSessionOpenDecision
}

/**
 * Pure admission policy so version and limit semantics are JVM-testable.
 */
internal object DieselSessionAdmission {
    const val MAX_SESSIONS =
        8

    fun decide(
        clientMinMajor: Int,
        clientMaxMajor: Int,
        clientTokenPresent: Boolean,
        runtimeAvailable: Boolean,
        activeSessions: Int,
        currentVersion: DieselApiVersion =
            DieselApiVersion.CURRENT,
    ): DieselSessionOpenDecision {
        if (
            clientMinMajor <= 0 ||
            clientMaxMajor < clientMinMajor
        ) {
            return DieselSessionOpenDecision.Rejected(
                DieselSessionOpenRejection.INVALID_REQUEST,
            )
        }

        if (
            clientMinMajor > currentVersion.major ||
            clientMaxMajor < currentVersion.major
        ) {
            return DieselSessionOpenDecision.Rejected(
                DieselSessionOpenRejection.UNSUPPORTED_VERSION,
            )
        }

        if (!clientTokenPresent) {
            return DieselSessionOpenDecision.Rejected(
                DieselSessionOpenRejection.INVALID_CLIENT,
            )
        }

        if (!runtimeAvailable) {
            return DieselSessionOpenDecision.Rejected(
                DieselSessionOpenRejection.RUNTIME_UNAVAILABLE,
            )
        }

        if (
            activeSessions >=
            MAX_SESSIONS
        ) {
            return DieselSessionOpenDecision.Rejected(
                DieselSessionOpenRejection.SESSION_LIMIT,
            )
        }

        return DieselSessionOpenDecision.Allowed
    }
}
