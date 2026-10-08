// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.alarm

import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselValue
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionAlarmProtocolTest {
    @Test
    fun noneAndUnavailableAreDistinct() {
        val none =
            CompanionAlarmProtocol.toArgs(
                NextAlarmObservation.None(10L),
            )
        val unavailable =
            CompanionAlarmProtocol.toArgs(
                NextAlarmObservation.Unavailable(
                    reason = "test",
                    observedAtMs = 10L,
                ),
            )

        assertEquals(
            PhoneDieselValue.Text("none"),
            none["kind"],
        )
        assertEquals(
            PhoneDieselValue.Text("unavailable"),
            unavailable["kind"],
        )
    }
}
