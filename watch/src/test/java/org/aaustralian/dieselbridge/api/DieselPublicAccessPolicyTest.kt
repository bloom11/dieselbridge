// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselPublicAccessPolicyTest {
    private val policy =
        DieselPublicAccessPolicy()

    private val app =
        DieselCallerIdentity(
            uid =
                12_345,
            packages =
                listOf(
                    "example.client",
                ),
        )

    @Test
    fun onlyExplicitM72ResourcesAreExternallyAllowed() {
        assertTrue(
            policy.canAccess(
                identity =
                    app,
                descriptor =
                    DieselDefaultPublicApi.COMPANION_NEXT_ALARM,
                operation =
                    DieselResourceOperation.READ,
            ),
        )

        assertTrue(
            policy.canAccess(
                identity =
                    app,
                descriptor =
                    DieselDefaultPublicApi.WATCH_VIBRATION,
                operation =
                    DieselResourceOperation.INVOKE,
            ),
        )

        val futureState =
            DieselResourceDescriptor(
                id =
                    "future.state",
                kind =
                    DieselResourceKind.STATE,
                schema =
                    "future.state.v1",
                operations =
                    setOf(
                        DieselResourceOperation.READ,
                    ),
                summary =
                    "Future state",
            )

        assertFalse(
            policy.canAccess(
                identity =
                    app,
                descriptor =
                    futureState,
                operation =
                    DieselResourceOperation.READ,
            ),
        )
    }

    @Test
    fun callerWithoutAndroidPackageIdentityIsDenied() {
        assertFalse(
            policy.canAccess(
                identity =
                    DieselCallerIdentity(
                        uid =
                            12_345,
                        packages =
                            emptyList(),
                    ),
                descriptor =
                    DieselDefaultPublicApi.COMPANION_NEXT_ALARM,
                operation =
                    DieselResourceOperation.READ,
            ),
        )
    }
}
