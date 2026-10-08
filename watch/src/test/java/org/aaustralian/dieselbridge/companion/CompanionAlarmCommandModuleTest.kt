// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.companion

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.state.DieselStateStore
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CompanionAlarmCommandModuleTest {
    @Test
    fun syncPublishesTypedStateAndGetReadsIt() =
        runTest {
            val states =
                DieselStateStore {
                    777L
                }
            val owner =
                CompanionAlarmStateOwner(
                    states = states,
                    clockMs = {
                        555L
                    },
                )
            val registry =
                DieselCommandRegistry().apply {
                    install(
                        CompanionAlarmCommandModule(owner),
                    )
                }

            val sync =
                registry.dispatch(
                    DieselRequest(
                        requestId = "test-sync",
                        command =
                            CompanionAlarmCommandModule
                                .COMMAND_SYNC,
                        args =
                            mapOf(
                                "kind" to
                                    DieselValue.Text(
                                        "scheduled",
                                    ),
                                "triggerAtMs" to
                                    DieselValue.Integer(
                                        1234L,
                                    ),
                                "observedAtMs" to
                                    DieselValue.Integer(
                                        1000L,
                                    ),
                            ),
                    ),
                )

            assertEquals(
                DieselResponseStatus.OK,
                sync.status,
            )

            val current =
                states.current(
                    CompanionAlarmStateOwner.KEY,
                )
            assertNotNull(current)
            assertEquals(
                1234L,
                current!!.value.triggerAtMs,
            )

            val get =
                registry.dispatch(
                    DieselRequest(
                        requestId = "test-get",
                        command =
                            CompanionAlarmCommandModule
                                .COMMAND_GET,
                    ),
                )
            assertEquals(
                DieselResponseStatus.OK,
                get.status,
            )
            assertEquals(
                DieselValue.Text("scheduled"),
                get.data["kind"],
            )

            owner.close()
        }
}
