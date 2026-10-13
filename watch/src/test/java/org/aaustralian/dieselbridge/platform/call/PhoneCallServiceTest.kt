// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.call

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallServiceTest {
    @Test
    fun inboundStateAndPublicActionsShareOneOwner() =
        runTest {
            var now =
                1_000L

            val sent =
                mutableListOf<String>()

            val inboundProjection =
                mutableListOf<String>()

            val localProjection =
                mutableListOf<String>()

            val platform =
                DieselPlatform(
                    scope =
                        backgroundScope,
                )

            val service =
                PhoneCallService(
                    platform =
                        platform,
                    sendAction =
                        {
                            sent +=
                                it

                            true
                        },
                    onInboundProjection =
                        {
                                cmd,
                                _,
                                _,
                            ->
                            inboundProjection +=
                                cmd
                        },
                    onLocalProjection =
                        {
                            localProjection +=
                                it
                        },
                    clockMs =
                        {
                            now
                        },
                )

            try {
                assertEquals(
                    PhoneCallPhase.IDLE,
                    service.current().phase,
                )

                now =
                    2_000L

                assertTrue(
                    service.receiveInbound(
                        cmd =
                            "incoming",
                        name =
                            "Alice",
                        number =
                            "123",
                    ),
                )

                assertEquals(
                    PhoneCallPhase.RINGING,
                    service.current().phase,
                )

                assertEquals(
                    PhoneCallDirection.INCOMING,
                    service.current().direction,
                )

                assertEquals(
                    listOf(
                        "incoming",
                    ),
                    inboundProjection,
                )

                now =
                    3_000L

                val accepted =
                    platform.actions.dispatch(
                        PhoneCallService.ACCEPT_KEY,
                        Unit,
                    )

                assertTrue(
                    accepted is
                        DieselActionResult.Success,
                )

                assertEquals(
                    listOf(
                        PhoneCallService.ACTION_ACCEPT,
                    ),
                    sent,
                )

                assertEquals(
                    PhoneCallPhase.ACTIVE,
                    service.current().phase,
                )

                assertEquals(
                    listOf(
                        PhoneCallService.ACTION_ACCEPT,
                    ),
                    localProjection,
                )

                now =
                    4_000L

                val ended =
                    platform.actions.dispatch(
                        PhoneCallService.END_KEY,
                        Unit,
                    )

                assertTrue(
                    ended is
                        DieselActionResult.Success,
                )

                assertEquals(
                    PhoneCallPhase.IDLE,
                    service.current().phase,
                )
            } finally {
                service.close()
                platform.transports.close()
            }
        }

    @Test
    fun invalidPhaseAndUnavailableTransportDoNotMutateState() =
        runTest {
            var transportAvailable =
                false

            val localProjection =
                mutableListOf<String>()

            val platform =
                DieselPlatform(
                    scope =
                        backgroundScope,
                )

            val service =
                PhoneCallService(
                    platform =
                        platform,
                    sendAction =
                        {
                            transportAvailable
                        },
                    onLocalProjection =
                        {
                            localProjection +=
                                it
                        },
                )

            try {
                assertEquals(
                    DieselActionResult.Rejected(
                        "call_not_ringing",
                    ),
                    platform.actions.dispatch(
                        PhoneCallService.ACCEPT_KEY,
                        Unit,
                    ),
                )

                service.receiveInbound(
                    cmd =
                        "incoming",
                    name =
                        null,
                    number =
                        null,
                )

                assertEquals(
                    DieselActionResult.Unavailable,
                    platform.actions.dispatch(
                        PhoneCallService.ACCEPT_KEY,
                        Unit,
                    ),
                )

                assertEquals(
                    PhoneCallPhase.RINGING,
                    service.current().phase,
                )

                assertTrue(
                    localProjection.isEmpty(),
                )

                transportAvailable =
                    true

                assertTrue(
                    platform.actions.dispatch(
                        PhoneCallService.REJECT_KEY,
                        Unit,
                    ) is
                        DieselActionResult.Success,
                )

                assertEquals(
                    PhoneCallPhase.IDLE,
                    service.current().phase,
                )
            } finally {
                service.close()
                platform.transports.close()
            }
        }

    @Test
    fun outgoingAndPhoneTeardownMapToStableState() =
        runTest {
            var now =
                10_000L

            val platform =
                DieselPlatform(
                    scope =
                        backgroundScope,
                )

            val service =
                PhoneCallService(
                    platform =
                        platform,
                    sendAction =
                        {
                            true
                        },
                    clockMs =
                        {
                            now
                        },
                )

            try {
                service.receiveInbound(
                    cmd =
                        "outgoing",
                    name =
                        "Bob",
                    number =
                        "456",
                )

                assertEquals(
                    PhoneCallPhase.ACTIVE,
                    service.current().phase,
                )

                assertEquals(
                    PhoneCallDirection.OUTGOING,
                    service.current().direction,
                )

                now =
                    11_000L

                service.receiveInbound(
                    cmd =
                        "end",
                    name =
                        null,
                    number =
                        null,
                )

                assertEquals(
                    PhoneCallPhase.IDLE,
                    service.current().phase,
                )

                assertEquals(
                    11_000L,
                    service.current().updatedAtMs,
                )
            } finally {
                service.close()
                platform.transports.close()
            }
        }
}
