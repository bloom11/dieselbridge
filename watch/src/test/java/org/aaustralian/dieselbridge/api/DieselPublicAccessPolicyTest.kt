// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselPublicAccessPolicyTest {
    private val app = DieselCallerIdentity(12345, listOf("example.client"))

    @Test fun establishedResourcesAndNonSensitiveObservationAreAllowed() {
        val policy = DieselPublicAccessPolicy(sdkInt = 35)
        assertTrue(policy.canAccess(app, DieselDefaultPublicApi.COMPANION_NEXT_ALARM, DieselResourceOperation.READ))
        assertTrue(policy.canAccess(app, DieselDefaultPublicApi.WATCH_VIBRATION, DieselResourceOperation.INVOKE))
        val accel = requireNotNull(DieselDefaultPublicApi.OBSERVATIONS.firstOrNull { it.logicalId == "accelerometer" })
        assertTrue(policy.canAccess(app, accel.descriptor, DieselResourceOperation.SUBSCRIBE))
    }

    @Test fun sensitiveObservationUsesCallerPermission() {
        val denied = DieselPublicAccessPolicy(sdkInt = 35)
        val allowed = DieselPublicAccessPolicy(
            sdkInt = 35,
            permissionChecker = DieselPermissionChecker { uid, permission ->
                uid == app.uid && permission in setOf(
                    "android.permission.BODY_SENSORS",
                    "android.permission.ACTIVITY_RECOGNITION",
                )
            },
        )
        val hr = requireNotNull(DieselDefaultPublicApi.OBSERVATIONS.firstOrNull { it.logicalId == "heart_rate" })
        val steps = requireNotNull(DieselDefaultPublicApi.OBSERVATIONS.firstOrNull { it.logicalId == "step_counter" })
        assertFalse(denied.canAccess(app, hr.descriptor, DieselResourceOperation.SUBSCRIBE))
        assertFalse(denied.canAccess(app, steps.descriptor, DieselResourceOperation.SUBSCRIBE))
        assertTrue(allowed.canAccess(app, hr.descriptor, DieselResourceOperation.SUBSCRIBE))
        assertTrue(allowed.canAccess(app, steps.descriptor, DieselResourceOperation.SUBSCRIBE))
    }

    @Test fun unknownAndNoPackageRemainDenied() {
        val policy = DieselPublicAccessPolicy()
        val future = DieselResourceDescriptor(
            "future.state",
            DieselResourceKind.STATE,
            "future.state.v1",
            setOf(DieselResourceOperation.READ),
            "Future state",
        )
        assertFalse(policy.canAccess(app, future, DieselResourceOperation.READ))
        assertFalse(
            policy.canAccess(
                DieselCallerIdentity(12345, emptyList()),
                DieselDefaultPublicApi.COMPANION_NEXT_ALARM,
                DieselResourceOperation.READ,
            ),
        )
    }
}
