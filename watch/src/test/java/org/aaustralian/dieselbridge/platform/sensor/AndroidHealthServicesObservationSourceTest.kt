package org.aaustralian.dieselbridge.platform.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidHealthServicesObservationSourceTest {

    @Test
    fun ingressDropsOldestAndReportsExactMonotonicTotal() {
        val ingress =
            HealthServicesObservationIngress(
                capacity =
                    2,
            )

        ingress.offer(
            sample(
                timestampNanos =
                    1L,
            ),
        )

        ingress.offer(
            sample(
                timestampNanos =
                    2L,
            ),
        )

        ingress.offer(
            sample(
                timestampNanos =
                    3L,
            ),
        )

        assertEquals(
            1L,
            ingress.sourceDroppedTotal,
        )

        val first =
            requireNotNull(
                ingress.poll(),
            )

        val second =
            requireNotNull(
                ingress.poll(),
            )

        assertEquals(
            2L,
            first.sample.timestampNanos,
        )
        assertEquals(
            1L,
            first.sourceDroppedTotal,
        )

        assertEquals(
            3L,
            second.sample.timestampNanos,
        )
        assertEquals(
            1L,
            second.sourceDroppedTotal,
        )

        assertNull(
            ingress.poll(),
        )
    }

    @Test
    fun ingressDropCounterRemainsMonotonicDuringRepeatedReplacement() {
        /*
         * We do not mutate the private counter to Long.MAX_VALUE here.
         * This test instead exercises repeated bounded replacement and proves
         * that the externally visible value is monotonic over normal use.
         */
        val ingress =
            HealthServicesObservationIngress(
                capacity =
                    1,
            )

        var previous =
            0L

        repeat(
            1_000,
        ) {
                index,
            ->
            ingress.offer(
                sample(
                    timestampNanos =
                        index.toLong(),
                ),
            )

            val current =
                ingress
                    .sourceDroppedTotal

            if (
                index >
                0
            ) {
                assertEquals(
                    previous +
                        1L,
                    current,
                )
            }

            previous =
                current
        }

        assertEquals(
            999L,
            ingress.sourceDroppedTotal,
        )
    }

    @Test
    fun ingressPreservesSamplePayload() {
        val ingress =
            HealthServicesObservationIngress(
                capacity =
                    2,
            )

        ingress.offer(
            HealthServicesSample(
                values =
                    listOf(
                        71.5f,
                    ),
                timestampNanos =
                    123_456L,
            ),
        )

        val pending =
            requireNotNull(
                ingress.poll(),
            )

        assertEquals(
            listOf(
                71.5f,
            ),
            pending.sample.values,
        )

        assertEquals(
            123_456L,
            pending.sample.timestampNanos,
        )

        assertEquals(
            0L,
            pending.sourceDroppedTotal,
        )
    }

    private fun sample(
        timestampNanos: Long,
    ) =
        HealthServicesSample(
            values =
                listOf(
                    timestampNanos
                        .toFloat(),
                ),
            timestampNanos =
                timestampNanos,
        )
}
