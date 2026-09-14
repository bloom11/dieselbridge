// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorManagerObservationIngressTest {

    @Test
    fun fullIngressDropsOldestAndKeepsFreshestSamples() {
        val ingress =
            SensorManagerObservationIngress<Int>(
                capacity =
                    2,
            )

        ingress.offer(
            1,
        )
        ingress.offer(
            2,
        )
        ingress.offer(
            3,
        )

        assertEquals(
            1L,
            ingress.sourceDroppedTotal,
        )

        val first =
            ingress.poll()

        val second =
            ingress.poll()

        assertEquals(
            2,
            first?.value,
        )

        assertEquals(
            3,
            second?.value,
        )

        assertEquals(
            1L,
            first?.sourceDroppedTotal,
        )

        assertEquals(
            1L,
            second?.sourceDroppedTotal,
        )

        assertNull(
            ingress.poll(),
        )
    }

    @Test
    fun ingressNeverExceedsConfiguredCapacity() {
        val ingress =
            SensorManagerObservationIngress<Int>(
                capacity =
                    3,
            )

        repeat(
            100,
        ) {
            ingress.offer(
                it,
            )
        }

        assertEquals(
            3,
            ingress.size,
        )

        assertEquals(
            97L,
            ingress.sourceDroppedTotal,
        )

        assertEquals(
            97,
            ingress.poll()
                ?.value,
        )

        assertEquals(
            98,
            ingress.poll()
                ?.value,
        )

        assertEquals(
            99,
            ingress.poll()
                ?.value,
        )
    }
}
