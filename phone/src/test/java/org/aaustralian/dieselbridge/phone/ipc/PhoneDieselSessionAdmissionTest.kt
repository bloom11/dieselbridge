// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.ipc

import org.aaustralian.dieselbridge.phone.api.PhoneDieselPublicApiVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PhoneDieselSessionAdmissionTest {
    @Test
    fun currentMajorWithLiveClientIsAllowed() {
        assertSame(
            PhoneDieselSessionOpenDecision.Allowed,
            decide(),
        )
    }

    @Test
    fun invalidVersionRangeIsRejected() {
        assertEquals(
            PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.INVALID_REQUEST,
            ),
            decide(
                clientMinMajor = 2,
                clientMaxMajor = 1,
            ),
        )
    }

    @Test
    fun incompatibleMajorIsRejected() {
        assertEquals(
            PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.UNSUPPORTED_VERSION,
            ),
            decide(
                clientMinMajor = 2,
                clientMaxMajor = 2,
            ),
        )
    }

    @Test
    fun missingClientIdentityIsRejected() {
        assertEquals(
            PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.INVALID_CLIENT,
            ),
            decide(
                callerPackagesPresent = false,
            ),
        )

        assertEquals(
            PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.INVALID_CLIENT,
            ),
            decide(
                clientTokenPresent = false,
            ),
        )
    }

    @Test
    fun unavailableRuntimeIsRejected() {
        assertEquals(
            PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.RUNTIME_UNAVAILABLE,
            ),
            decide(
                runtimeAvailable = false,
            ),
        )
    }

    @Test
    fun ninthSessionIsRejected() {
        assertEquals(
            PhoneDieselSessionOpenDecision.Rejected(
                PhoneDieselSessionOpenRejection.SESSION_LIMIT,
            ),
            decide(
                activeSessions =
                    PhoneDieselSessionAdmission.MAX_SESSIONS,
            ),
        )
    }

    private fun decide(
        clientMinMajor: Int = 1,
        clientMaxMajor: Int = 1,
        clientTokenPresent: Boolean = true,
        callerPackagesPresent: Boolean = true,
        runtimeAvailable: Boolean = true,
        activeSessions: Int = 0,
    ): PhoneDieselSessionOpenDecision =
        PhoneDieselSessionAdmission.decide(
            clientMinMajor =
                clientMinMajor,
            clientMaxMajor =
                clientMaxMajor,
            clientTokenPresent =
                clientTokenPresent,
            callerPackagesPresent =
                callerPackagesPresent,
            runtimeAvailable =
                runtimeAvailable,
            activeSessions =
                activeSessions,
            currentVersion =
                PhoneDieselPublicApiVersion(
                    major = 1,
                    minor = 0,
                ),
        )
}
