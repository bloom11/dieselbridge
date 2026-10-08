// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDieselGatewayTest {
    @Test
    fun matchingResponseCompletesRequest() =
        runTest {
            val lines = mutableListOf<String>()
            val router = PhoneDieselResponseRouter()
            val gateway =
                PhoneDieselGateway(
                    transport =
                        PhoneDieselTransport {
                            lines += it
                        },
                    responses = router,
                    requestIdFactory = {
                        "phone-test"
                    },
                )

            val pending =
                async {
                    gateway.execute(
                        command = "debug.build.info",
                        timeoutMs = 1_000L,
                    )
                }

            testScheduler.runCurrent()

            assertEquals(1, lines.size)
            assertEquals(
                PhoneDieselRouteResult.DELIVERED,
                router.accept(
                    PhoneDieselResponse(
                        requestId = "phone-test",
                        command = "debug.build.info",
                        name = null,
                        status = PhoneDieselResponseStatus.OK,
                        data = emptyMap(),
                    ),
                ),
            )

            assertTrue(
                pending.await() is
                    PhoneDieselGatewayResult.Success,
            )
        }

    @Test
    fun wrongMetadataIsIgnoredUntilTimeout() =
        runTest {
            val router = PhoneDieselResponseRouter()
            val gateway =
                PhoneDieselGateway(
                    transport =
                        PhoneDieselTransport { },
                    responses = router,
                    requestIdFactory = {
                        "phone-test"
                    },
                )

            val pending =
                async {
                    gateway.execute(
                        command = "debug.build.info",
                        timeoutMs = 10L,
                    )
                }

            testScheduler.runCurrent()

            assertEquals(
                PhoneDieselRouteResult.METADATA_MISMATCH,
                router.accept(
                    PhoneDieselResponse(
                        requestId = "phone-test",
                        command = "commands",
                        name = null,
                        status = PhoneDieselResponseStatus.OK,
                        data = emptyMap(),
                    ),
                ),
            )

            testScheduler.advanceTimeBy(11L)
            testScheduler.runCurrent()

            assertTrue(
                pending.await() is
                    PhoneDieselGatewayResult.Timeout,
            )
        }
}
