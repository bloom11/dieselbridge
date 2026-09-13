// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlinx.coroutines.runBlocking
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteId
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbe
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbeOutcome
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeveloperSensorScanCommandModuleTest {

    private class FakeProbe : SensorRouteProbe {
        var calls = 0

        override suspend fun probe(
            routeId: SensorRouteId,
            timeoutMs: Long,
        ): SensorRouteProbeOutcome {
            calls++
            return SensorRouteProbeOutcome.RouteUnavailable
        }
    }

    @Test
    fun disabledAuthorizationReturnsUnavailableForValidScanCommands(): Unit =
        runBlocking {
            val probe = FakeProbe()
            val store = SensorProbeStore()
            val runner =
                SensorScanRunner(
                    probe = probe,
                    routes = { emptyList() },
                    store = store,
                    scope = this,
                )

            val registry =
                DieselCommandRegistry().apply {
                    install(
                        DeveloperSensorScanCommandModule(
                            authorization =
                                DeveloperRemoteAccessAuthorization {
                                    false
                                },
                            runner = runner,
                            store = store,
                        ),
                    )
                }

            val requests =
                listOf(
                    DieselRequest(
                        requestId = "start",
                        command =
                            DeveloperSensorScanCommandModule.COMMAND_START,
                    ),
                    DieselRequest(
                        requestId = "status",
                        command =
                            DeveloperSensorScanCommandModule.COMMAND_STATUS,
                    ),
                    DieselRequest(
                        requestId = "cancel",
                        command =
                            DeveloperSensorScanCommandModule.COMMAND_CANCEL,
                        args =
                            mapOf(
                                DeveloperSensorScanCommandModule.ARG_RUN_ID to
                                    DieselValue.Text("scan-test"),
                            ),
                    ),
                )

            requests.forEach { request ->
                val result = registry.dispatch(request)

                assertEquals(
                    DieselResponseStatus.UNAVAILABLE,
                    result.status,
                )
                assertEquals(
                    DieselValue.Text(
                        "remote_developer_access_disabled",
                    ),
                    result.data["reason"],
                )
            }

            assertEquals(0, probe.calls)
            assertNull(store.latestSummary())
        }

    @Test
    fun invalidSyntaxIsRejectedBeforeAuthorization(): Unit =
        runBlocking {
            val probe = FakeProbe()
            val store = SensorProbeStore()
            val runner =
                SensorScanRunner(
                    probe = probe,
                    routes = { emptyList() },
                    store = store,
                    scope = this,
                )

            val registry =
                DieselCommandRegistry().apply {
                    install(
                        DeveloperSensorScanCommandModule(
                            authorization =
                                DeveloperRemoteAccessAuthorization {
                                    error(
                                        "Authorization must follow syntax validation",
                                    )
                                },
                            runner = runner,
                            store = store,
                        ),
                    )
                }

            val invalid =
                listOf(
                    DieselRequest(
                        requestId = "bad-start",
                        command =
                            DeveloperSensorScanCommandModule.COMMAND_START,
                        args =
                            mapOf(
                                "unexpected" to
                                    DieselValue.Null,
                            ),
                    ),
                    DieselRequest(
                        requestId = "bad-status",
                        command =
                            DeveloperSensorScanCommandModule.COMMAND_STATUS,
                        args =
                            mapOf(
                                DeveloperSensorScanCommandModule.ARG_RUN_ID to
                                    DieselValue.Integer(1),
                            ),
                    ),
                    DieselRequest(
                        requestId = "bad-cancel",
                        command =
                            DeveloperSensorScanCommandModule.COMMAND_CANCEL,
                        args =
                            mapOf(
                                DeveloperSensorScanCommandModule.ARG_RUN_ID to
                                    DieselValue.Integer(1),
                            ),
                    ),
                )

            invalid.forEach { request ->
                assertEquals(
                    DieselResponseStatus.INVALID_REQUEST,
                    registry.dispatch(request).status,
                )
            }

            assertEquals(0, probe.calls)
            assertNull(store.latestSummary())
        }
}
