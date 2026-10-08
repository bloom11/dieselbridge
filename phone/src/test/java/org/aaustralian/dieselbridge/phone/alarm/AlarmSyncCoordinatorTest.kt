// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.alarm

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselExecutor
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselGatewayResult
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselResponse
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselResponseStatus
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSyncCoordinatorTest {
    @Test
    fun matchingAckConfirmsScheduledState() =
        runTest {
            val observation =
                NextAlarmObservation.Scheduled(
                    triggerAtMs = 2000L,
                    observedAtMs = 1000L,
                )

            val coordinator =
                AlarmSyncCoordinator(
                    provider =
                        NextAlarmProvider {
                            observation
                        },
                    executor =
                        object : PhoneDieselExecutor {
                            override suspend fun execute(
                                command: String,
                                name: String?,
                                args:
                                    Map<String, PhoneDieselValue>,
                                timeoutMs: Long,
                            ): PhoneDieselGatewayResult =
                                PhoneDieselGatewayResult.Success(
                                    PhoneDieselResponse(
                                        requestId = "x",
                                        command =
                                            CompanionAlarmProtocol
                                                .COMMAND_SYNC,
                                        name = null,
                                        status =
                                            PhoneDieselResponseStatus.OK,
                                        data =
                                            mapOf(
                                                "kind" to
                                                    PhoneDieselValue.Text(
                                                        "scheduled",
                                                    ),
                                                "triggerAtMs" to
                                                    PhoneDieselValue.Integer(
                                                        2000L,
                                                    ),
                                            ),
                                    ),
                                )
                        },
                    scope = backgroundScope,
                    retryDelaysMs = emptyList(),
                )

            assertTrue(coordinator.syncOnce("test"))
            assertEquals(
                AlarmSyncStatus.CONFIRMED,
                coordinator.state.value.status,
            )
            assertEquals(
                observation,
                coordinator.state.value.confirmed,
            )
        }
}
