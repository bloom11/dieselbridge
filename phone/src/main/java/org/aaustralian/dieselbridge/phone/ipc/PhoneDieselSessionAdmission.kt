// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.ipc

import org.aaustralian.dieselbridge.phone.api.PhoneDieselPublicApiVersion

internal enum class PhoneDieselSessionOpenRejection(
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

internal sealed interface PhoneDieselSessionOpenDecision {
    data object Allowed :
        PhoneDieselSessionOpenDecision

    data class Rejected(
        val reason:
            PhoneDieselSessionOpenRejection,
    ) :
        PhoneDieselSessionOpenDecision
}

internal object PhoneDieselSessionAdmission {
    const val MAX_SESSIONS =
        8

    fun decide(
        clientMinMajor: Int,
        clientMaxMajor: Int,
        clientTokenPresent: Boolean,
        callerPackagesPresent: Boolean,
        runtimeAvailable: Boolean,
        activeSessions: Int,
        currentVersion:
            PhoneDieselPublicApiVersion =
            PhoneDieselPublicApiVersion.CURRENT,
    ): PhoneDieselSessionOpenDecision {
        if (
            clientMinMajor <= 0 ||
            clientMaxMajor <
            clientMinMajor
        ) {
            return PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.INVALID_REQUEST,
            )
        }

        if (
            clientMinMajor >
            currentVersion.major ||
            clientMaxMajor <
            currentVersion.major
        ) {
            return PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.UNSUPPORTED_VERSION,
            )
        }

        if (
            !clientTokenPresent ||
            !callerPackagesPresent
        ) {
            return PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.INVALID_CLIENT,
            )
        }

        if (!runtimeAvailable) {
            return PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.RUNTIME_UNAVAILABLE,
            )
        }

        if (
            activeSessions >=
            MAX_SESSIONS
        ) {
            return PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.SESSION_LIMIT,
            )
        }

        return PhoneDieselSessionOpenDecision.Allowed
    }
}
