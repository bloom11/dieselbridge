// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.integration.gadgetbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class GadgetbridgeActivityCodecTest {

    @Test
    fun encodesCanonicalRealtimeActivityLine() {
        val json =
            JSONObject(
                GadgetbridgeActivityCodec
                    .encodeRealtime(
                        timestampMs =
                            1_700_000_000_123L,
                        heartRateBpm =
                            76,
                    ),
            )

        assertEquals("act", json.getString("t"))
        assertEquals(1_700_000_000_123L, json.getLong("ts"))
        assertEquals(76, json.getInt("hrm"))
        assertEquals(0, json.getInt("stp"))
        assertEquals(1, json.getInt("rt"))
    }

    @Test
    fun unavailableRealtimeValuesEncodeAsZero() {
        val json =
            JSONObject(
                GadgetbridgeActivityCodec
                    .encodeRealtime(
                        timestampMs =
                            123L,
                        heartRateBpm =
                            null,
                        stepDelta =
                            null,
                    ),
            )

        assertEquals(0, json.getInt("hrm"))
        assertEquals(0, json.getInt("stp"))
    }
}
