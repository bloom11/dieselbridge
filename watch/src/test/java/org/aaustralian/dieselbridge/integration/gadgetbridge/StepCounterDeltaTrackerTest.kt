// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.integration.gadgetbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class StepCounterDeltaTrackerTest {

    @Test
    fun firstSampleIsBaselineAndQueuedReportAdvancesOnlyByDelta() {
        val tracker =
            StepCounterDeltaTracker()

        var state =
            tracker.observeSample(
                "android.sensor_manager",
                1_000L,
            )

        assertEquals(1_000L, state.baselineRaw)
        assertEquals(0, state.pendingDelta)

        state =
            tracker.observeSample(
                "android.sensor_manager",
                1_004L,
            )

        assertEquals(4, state.pendingDelta)

        state =
            tracker.acknowledgeQueued(4)

        assertEquals(1_004L, state.baselineRaw)
        assertEquals(0, state.pendingDelta)

        state =
            tracker.observeSample(
                "android.sensor_manager",
                1_013L,
            )

        assertEquals(9, state.pendingDelta)
    }

    @Test
    fun unacknowledgedDeltaRemainsPendingForRetry() {
        val tracker =
            StepCounterDeltaTracker()

        tracker.observeSample(
            "android.sensor_manager",
            500L,
        )
        tracker.observeSample(
            "android.sensor_manager",
            507L,
        )

        assertEquals(7, tracker.pendingDelta())
        assertEquals(7, tracker.pendingDelta())

        tracker.acknowledgeQueued(7)

        assertEquals(0, tracker.pendingDelta())
    }

    @Test
    fun counterRollbackStartsFreshDomain() {
        val tracker =
            StepCounterDeltaTracker()

        tracker.observeSample(
            "android.sensor_manager",
            10_000L,
        )
        tracker.observeSample(
            "android.sensor_manager",
            10_006L,
        )

        assertEquals(6, tracker.pendingDelta())

        val reset =
            tracker.observeSample(
                "android.sensor_manager",
                2L,
            )

        assertEquals(2L, reset.baselineRaw)
        assertEquals(2L, reset.latestRaw)
        assertEquals(0, reset.pendingDelta)
        assertEquals(1L, reset.domainResetCount)

        assertEquals(
            3,
            tracker
                .observeSample(
                    "android.sensor_manager",
                    5L,
                )
                .pendingDelta,
        )
    }

    @Test
    fun providerChangeDropsOldPendingDomain() {
        val tracker =
            StepCounterDeltaTracker()

        tracker.observeSample(
            "provider.one",
            100L,
        )
        tracker.observeSample(
            "provider.one",
            108L,
        )

        assertEquals(8, tracker.pendingDelta())

        val switched =
            tracker.observeProvider(
                "provider.two",
            )

        assertEquals("provider.two", switched.providerId)
        assertEquals(null, switched.baselineRaw)
        assertEquals(0, switched.pendingDelta)
        assertEquals(1L, switched.domainResetCount)

        val baseline =
            tracker.observeSample(
                "provider.two",
                700L,
            )

        assertEquals(700L, baseline.baselineRaw)
        assertEquals(0, baseline.pendingDelta)
    }

    @Test
    fun resetMakesReenableStartFromNewBaseline() {
        val tracker =
            StepCounterDeltaTracker()

        tracker.observeSample(
            "provider",
            1_000L,
        )
        tracker.observeSample(
            "provider",
            1_010L,
        )

        tracker.reset()

        val state =
            tracker.observeSample(
                "provider",
                1_050L,
            )

        assertEquals(1_050L, state.baselineRaw)
        assertEquals(0, state.pendingDelta)
    }
}
