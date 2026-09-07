// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class DieselProtocolExecutionLaneTest {

    private var laneJob: Job? =
        null

    @After
    fun cancelExecutionLane() {
        laneJob?.cancel()
        laneJob = null
    }

    private suspend fun executionLane(
        engine: DieselProtocolEngine,
    ): DieselProtocolExecutionLane {
        check(
            laneJob == null,
        ) {
            "Only one execution lane is expected per test"
        }

        val job =
            SupervisorJob()

        laneJob =
            job

        return DieselProtocolExecutionLane(
            scope =
                CoroutineScope(
                    coroutineContext +
                        job,
                ),
            engine = engine,
        )
    }

    @Test
    fun secondCommandCannotRunWhileFirstIsSuspended(): Unit =
        runBlocking {
            val firstEntered =
                CompletableDeferred<Unit>()

            val releaseFirst =
                CompletableDeferred<Unit>()

            val secondEntered =
                CompletableDeferred<Unit>()

            val registry =
                DieselCommandRegistry()

            registry.register(
                DieselCommandSpec(
                    name = "first",
                    summary = "First synthetic command",
                ),
            ) {
                firstEntered.complete(
                    Unit,
                )

                releaseFirst.await()

                DieselCommandResult.ok()
            }

            registry.register(
                DieselCommandSpec(
                    name = "second",
                    summary = "Second synthetic command",
                ),
            ) {
                secondEntered.complete(
                    Unit,
                )

                DieselCommandResult.ok()
            }

            val responses =
                mutableListOf<String>()

            val engine =
                DieselProtocolEngine(
                    commands = registry,
                    responses =
                        DieselResponseTransport {
                                response,
                            ->
                            responses +=
                                response.command

                            true
                        },
                )

            val lane =
                executionLane(
                    engine = engine,
                )

            val firstJob =
                lane.submit(
                    DieselRequest(
                        requestId = "lane-1",
                        command = "first",
                    ),
                )

            firstEntered.await()

            val secondJob =
                lane.submit(
                    DieselRequest(
                        requestId = "lane-2",
                        command = "second",
                    ),
                )

            yield()

            assertFalse(
                secondEntered.isCompleted,
            )

            assertTrue(
                responses.isEmpty(),
            )

            releaseFirst.complete(
                Unit,
            )

            firstJob.join()
            secondJob.join()

            assertTrue(
                secondEntered.isCompleted,
            )

            assertEquals(
                listOf(
                    "first",
                    "second",
                ),
                responses,
            )
        }

    @Test
    fun invalidRequestCannotOvertakeSuspendedValidRequest(): Unit =
        runBlocking {
            val firstEntered =
                CompletableDeferred<Unit>()

            val releaseFirst =
                CompletableDeferred<Unit>()

            val registry =
                DieselCommandRegistry()

            registry.register(
                DieselCommandSpec(
                    name = "slow",
                    summary = "Synthetic suspended command",
                ),
            ) {
                firstEntered.complete(
                    Unit,
                )

                releaseFirst.await()

                DieselCommandResult.ok()
            }

            val responses =
                mutableListOf<DieselResponseStatus>()

            val engine =
                DieselProtocolEngine(
                    commands = registry,
                    responses =
                        DieselResponseTransport {
                                response,
                            ->
                            responses +=
                                response.status

                            true
                        },
                )

            val lane =
                executionLane(
                    engine = engine,
                )

            val validJob =
                lane.submit(
                    DieselRequest(
                        requestId = "valid-1",
                        command = "slow",
                    ),
                )

            firstEntered.await()

            val invalidJob =
                lane.submitInvalid(
                    DieselInvalidRequest(
                        requestId = "invalid-1",
                        command = "commands",
                        name = null,
                        reason =
                            DieselRequestFailureReason
                                .INVALID_KIND,
                        detail =
                            "test-only local detail",
                    ),
                )

            yield()

            assertTrue(
                responses.isEmpty(),
            )

            releaseFirst.complete(
                Unit,
            )

            validJob.join()
            invalidJob.join()

            assertEquals(
                listOf(
                    DieselResponseStatus.OK,
                    DieselResponseStatus.INVALID_REQUEST,
                ),
                responses,
            )
        }



    @Test
    fun submissionOrderIsPreserved(): Unit =
        runBlocking {
            val registry =
                DieselCommandRegistry()

            registry.register(
                DieselCommandSpec(
                    name = "ordered",
                    summary = "Synthetic ordered command",
                ),
            ) { context ->
                DieselCommandResult.ok(
                    data =
                        mapOf(
                            "sequence" to
                                requireNotNull(
                                    context.args[
                                        "sequence"
                                    ],
                                ),
                        ),
                )
            }

            val responses =
                mutableListOf<Long>()

            val engine =
                DieselProtocolEngine(
                    commands = registry,
                    responses =
                        DieselResponseTransport {
                                response,
                            ->
                            responses +=
                                (
                                    response.data[
                                        "sequence"
                                    ] as DieselValue.Integer
                                ).value

                            true
                        },
                )

            val lane =
                executionLane(
                    engine = engine,
                )

            val jobs =
                (0L until 32L)
                    .map { sequence ->
                        lane.submit(
                            DieselRequest(
                                requestId =
                                    "fifo-$sequence",
                                command =
                                    "ordered",
                                args =
                                    mapOf(
                                        "sequence" to
                                            DieselValue.Integer(
                                                sequence,
                                            ),
                                    ),
                            ),
                        )
                    }

            jobs.forEach {
                it.join()
            }

            assertEquals(
                (0L until 32L).toList(),
                responses,
            )
        }


}
