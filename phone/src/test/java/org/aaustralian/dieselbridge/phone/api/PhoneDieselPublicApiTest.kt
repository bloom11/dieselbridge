// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.api

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselExecutor
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselGatewayResult
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselProtocolRules
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselResponse
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselResponseStatus
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDieselPublicApiTest {
    private class RecordingExecutor :
        PhoneDieselExecutor {
        data class Call(
            val command: String,
            val name: String?,
            val args:
                Map<String, PhoneDieselValue>,
            val timeoutMs: Long,
        )

        val calls =
            mutableListOf<Call>()

        var result:
            PhoneDieselGatewayResult =
            PhoneDieselGatewayResult.Success(
                PhoneDieselResponse(
                    requestId =
                        "internal-request-id",
                    command =
                        "debug.build.info",
                    name =
                        null,
                    status =
                        PhoneDieselResponseStatus.OK,
                    data =
                        mapOf(
                            "build" to
                                PhoneDieselValue.Text(
                                    "test",
                                ),
                        ),
                ),
            )

        override suspend fun execute(
            command: String,
            name: String?,
            args: Map<String, PhoneDieselValue>,
            timeoutMs: Long,
        ): PhoneDieselGatewayResult {
            calls +=
                Call(
                    command,
                    name,
                    args,
                    timeoutMs,
                )

            return result
        }
    }

    @Test
    fun allowedCommandDelegatesToExistingExecutor() =
        runTest {
            val executor =
                RecordingExecutor()

            val api =
                PhoneDieselPublicApi(
                    executor,
                )

            val result =
                api.execute(
                    PhoneDieselPublicRequest(
                        command =
                            "debug.build.info",
                        timeoutMs =
                            2_000L,
                    ),
                )

            assertEquals(
                1,
                executor.calls.size,
            )
            assertEquals(
                "debug.build.info",
                executor.calls.single().command,
            )
            assertEquals(
                2_000L,
                executor.calls.single().timeoutMs,
            )

            result as
                PhoneDieselPublicResult.Success

            assertEquals(
                "ok",
                result.remoteStatus,
            )
            assertEquals(
                PhoneDieselValue.Text(
                    "test",
                ),
                result.data[
                    "build"
                ],
            )
        }

    @Test
    fun nonPublicCommandNeverTouchesGateway() =
        runTest {
            val executor =
                RecordingExecutor()

            val api =
                PhoneDieselPublicApi(
                    executor,
                )

            val result =
                api.execute(
                    PhoneDieselPublicRequest(
                        command =
                            "companion.alarm.next.sync",
                    ),
                )

            assertEquals(
                PhoneDieselPublicResult.Denied(
                    "command_not_allowed",
                ),
                result,
            )
            assertTrue(
                executor.calls.isEmpty(),
            )
        }

    @Test
    fun malformedValueTreeIsRejectedBeforeGateway() =
        runTest {
            val executor =
                RecordingExecutor()

            val api =
                PhoneDieselPublicApi(
                    executor,
                )

            val result =
                api.execute(
                    PhoneDieselPublicRequest(
                        command =
                            "debug.build.info",
                        args =
                            mapOf(
                                "bad" to
                                    PhoneDieselValue.Text(
                                        "x".repeat(
                                            PhoneDieselProtocolRules
                                                .MAX_TEXT_VALUE_BYTES +
                                                1,
                                        ),
                                    ),
                            ),
                    ),
                )

            assertEquals(
                PhoneDieselPublicResult.Invalid(
                    "invalid_arguments",
                ),
                result,
            )
            assertTrue(
                executor.calls.isEmpty(),
            )
        }

    @Test
    fun gatewayInternalFailuresAreNormalized() =
        runTest {
            val executor =
                RecordingExecutor()

            executor.result =
                PhoneDieselGatewayResult.SubmissionFailed(
                    requestId =
                        "private-id",
                    errorType =
                        "java.lang.IllegalStateException",
                    message =
                        "private transport detail",
                )

            val api =
                PhoneDieselPublicApi(
                    executor,
                )

            assertEquals(
                PhoneDieselPublicResult.Failed(
                    "submission_failed",
                ),
                api.execute(
                    PhoneDieselPublicRequest(
                        command =
                            "debug.build.info",
                    ),
                ),
            )
        }

    @Test
    fun defaultPolicyIsNarrowAndDiscoverable() {
        val policy =
            PhoneDieselPublicAccessPolicy()

        assertEquals(
            listOf(
                "commands",
                "companion.alarm.next.get",
                "debug.build.info",
            ),
            policy.commands(),
        )

        assertTrue(
            policy.canExecute(
                "companion.alarm.next.get",
            ),
        )

        assertFalse(
            policy.canExecute(
                "companion.alarm.next.sync",
            ),
        )
    }
}
