// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ipc

import org.junit.Assert.assertEquals
import org.junit.Test

class DieselSessionAdmissionTest {
    @Test
    fun compatibleLiveClientIsAllowed() {
        assertEquals(
            DieselSessionOpenDecision.Allowed,
            DieselSessionAdmission.decide(
                clientMinMajor =
                    1,
                clientMaxMajor =
                    1,
                clientTokenPresent =
                    true,
                runtimeAvailable =
                    true,
                activeSessions =
                    0,
            ),
        )
    }

    @Test
    fun expectedAdmissionFailuresAreExplicit() {
        assertRejected(
            DieselSessionOpenRejection.INVALID_REQUEST,
            clientMinMajor =
                0,
            clientMaxMajor =
                1,
            clientTokenPresent =
                true,
            runtimeAvailable =
                true,
            activeSessions =
                0,
        )

        assertRejected(
            DieselSessionOpenRejection.UNSUPPORTED_VERSION,
            clientMinMajor =
                2,
            clientMaxMajor =
                2,
            clientTokenPresent =
                true,
            runtimeAvailable =
                true,
            activeSessions =
                0,
        )

        assertRejected(
            DieselSessionOpenRejection.INVALID_CLIENT,
            clientMinMajor =
                1,
            clientMaxMajor =
                1,
            clientTokenPresent =
                false,
            runtimeAvailable =
                true,
            activeSessions =
                0,
        )

        assertRejected(
            DieselSessionOpenRejection.RUNTIME_UNAVAILABLE,
            clientMinMajor =
                1,
            clientMaxMajor =
                1,
            clientTokenPresent =
                true,
            runtimeAvailable =
                false,
            activeSessions =
                0,
        )

        assertRejected(
            DieselSessionOpenRejection.SESSION_LIMIT,
            clientMinMajor =
                1,
            clientMaxMajor =
                1,
            clientTokenPresent =
                true,
            runtimeAvailable =
                true,
            activeSessions =
                DieselSessionAdmission.MAX_SESSIONS,
        )
    }

    private fun assertRejected(
        expected: DieselSessionOpenRejection,
        clientMinMajor: Int,
        clientMaxMajor: Int,
        clientTokenPresent: Boolean,
        runtimeAvailable: Boolean,
        activeSessions: Int,
    ) {
        assertEquals(
            DieselSessionOpenDecision.Rejected(
                expected,
            ),
            DieselSessionAdmission.decide(
                clientMinMajor =
                    clientMinMajor,
                clientMaxMajor =
                    clientMaxMajor,
                clientTokenPresent =
                    clientTokenPresent,
                runtimeAvailable =
                    runtimeAvailable,
                activeSessions =
                    activeSessions,
            ),
        )
    }
}
