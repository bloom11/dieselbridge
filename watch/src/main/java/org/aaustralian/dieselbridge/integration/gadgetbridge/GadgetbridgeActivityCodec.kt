// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.integration.gadgetbridge

import org.json.JSONObject

/**
 * Bangle.js/Gadgetbridge realtime activity wire codec.
 *
 * rt=1 keeps these reports in the realtime path rather than historical
 * activity storage. M5.1c will supply the step delta.
 */
object GadgetbridgeActivityCodec {

    fun encodeRealtime(
        timestampMs: Long,
        heartRateBpm: Int?,
        stepDelta: Int? = null,
    ): String {
        require(timestampMs >= 0L)
        require(
            heartRateBpm == null ||
                heartRateBpm >= 0,
        )
        require(
            stepDelta == null ||
                stepDelta >= 0,
        )

        return JSONObject()
            .put(
                "t",
                "act",
            )
            .put(
                "ts",
                timestampMs,
            )
            .put(
                "hrm",
                heartRateBpm
                    ?: 0,
            )
            .put(
                "stp",
                stepDelta
                    ?: 0,
            )
            .put(
                "rt",
                1,
            )
            .toString()
    }
}
