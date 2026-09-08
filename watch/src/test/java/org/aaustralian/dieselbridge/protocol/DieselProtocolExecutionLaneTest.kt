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
        queueCapacity: Int =
            DieselProtocolExecutionLane.DEFAULT_QUEUE_CAPACITY,
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
            queueCapacity = queueCapacity,
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

            firstJob.completion.join()
            secondJob.completion.join()

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

            validJob.completion.join()
            invalidJob.completion.join()

            assertEquals(
                listOf(
                    DieselResponseStatus.OK,
                    DieselResponseStatus.INVALID_REQUEST,
                ),
                responses,
            )
        }



    @Test
    fun fullQueueRejectsWithoutBlockingAndWorkerContinues(): Unit =
        runBlocking {
            val firstEntered =
                CompletableDeferred<Unit>()
            val release =
                CompletableDeferred<Unit>()

            val registry =
                DieselCommandRegistry()

            registry.register(
                DieselCommandSpec(
                    name = "slow",
                    summary = "Synthetic slow command",
                ),
            ) {
                firstEntered.complete(Unit)
                release.await()
                DieselCommandResult.ok()
            }

            val responses =
                mutableListOf<String>()

            val engine =
                DieselProtocolEngine(
                    commands = registry,
                    responses =
                        DieselResponseTransport { response ->
                            responses += requireNotNull(response.requestId)
                            true
                        },
                )

            val lane =
                executionLane(
                    engine = engine,
                    queueCapacity = 2,
                )

            val running =
                lane.submit(
                    DieselRequest(
                        requestId = "running",
                        command = "slow",
                    ),
                )

            assertEquals(
                DieselProtocolAdmission.ACCEPTED,
                running.admission,
            )

            firstEntered.await()

            val waitingOne =
                lane.submit(
                    DieselRequest(
                        requestId = "waiting-1",
                        command = "slow",
                    ),
                )

            val waitingTwo =
                lane.submit(
                    DieselRequest(
                        requestId = "waiting-2",
                        command = "slow",
                    ),
                )

            val overflow =
                lane.submit(
                    DieselRequest(
                        requestId = "overflow",
                        command = "slow",
                    ),
                )

            assertEquals(
                DieselProtocolAdmission.ACCEPTED,
                waitingOne.admission,
            )
            assertEquals(
                DieselProtocolAdmission.ACCEPTED,
                waitingTwo.admission,
            )
            assertEquals(
                DieselProtocolAdmission.FULL,
                overflow.admission,
            )
            assertTrue(
                overflow.completion.isCancelled,
            )

            release.complete(Unit)

            running.completion.join()
            waitingOne.completion.join()
            waitingTwo.completion.join()

            assertEquals(
                listOf(
                    "running",
                    "waiting-1",
                    "waiting-2",
                ),
                responses,
            )

            val afterDrain =
                lane.submit(
                    DieselRequest(
                        requestId = "after-drain",
                        command = "slow",
                    ),
                )

            assertEquals(
                DieselProtocolAdmission.ACCEPTED,
                afterDrain.admission,
            )

            afterDrain.completion.join()

            assertEquals(
                listOf(
                    "running",
                    "waiting-1",
                    "waiting-2",
                    "after-drain",
                ),
                responses,
            )
        }

    @Test
    fun invalidRequestsShareTheSameBoundedAdmissionBudget(): Unit =
        runBlocking {
            val firstEntered =
                CompletableDeferred<Unit>()
            val release =
                CompletableDeferred<Unit>()

            val registry =
                DieselCommandRegistry()

            registry.register(
                DieselCommandSpec(
                    name = "slow",
                    summary = "Synthetic slow command",
                ),
            ) {
                firstEntered.complete(Unit)
                release.await()
                DieselCommandResult.ok()
            }

            val statuses =
                mutableListOf<DieselResponseStatus>()

            val engine =
                DieselProtocolEngine(
                    commands = registry,
                    responses =
                        DieselResponseTransport { response ->
                            statuses += response.status
                            true
                        },
                )

            val lane =
                executionLane(
                    engine = engine,
                    queueCapacity = 1,
                )

            val running =
                lane.submit(
                    DieselRequest(
                        requestId = "running",
                        command = "slow",
                    ),
                )

            firstEntered.await()

            val invalid =
                lane.submitInvalid(
                    DieselInvalidRequest(
                        requestId = "invalid",
                        command = "commands",
                        name = null,
                        reason =
                            DieselRequestFailureReason.INVALID_KIND,
                        detail = "local test detail",
                    ),
                )

            val overflow =
                lane.submit(
                    DieselRequest(
                        requestId = "overflow",
                        command = "slow",
                    ),
                )

            assertEquals(
                DieselProtocolAdmission.ACCEPTED,
                running.admission,
            )
            assertEquals(
                DieselProtocolAdmission.ACCEPTED,
                invalid.admission,
            )
            assertEquals(
                DieselProtocolAdmission.FULL,
                overflow.admission,
            )

            release.complete(Unit)

            running.completion.join()
            invalid.completion.join()

            assertEquals(
                listOf(
                    DieselResponseStatus.OK,
                    DieselResponseStatus.INVALID_REQUEST,
                ),
                statuses,
            )
        }

    @Test
    fun stoppedLaneReportsClosedInsteadOfFull(): Unit =
        runBlocking {
            val engine =
                DieselProtocolEngine(
                    commands = DieselCommandRegistry(),
                    responses =
                        DieselResponseTransport {
                            true
                        },
                )

            val lane =
                executionLane(
                    engine = engine,
                    queueCapacity = 1,
                )

            val job =
                requireNotNull(laneJob)

            job.cancel()
            job.join()

            val submission =
                lane.submit(
                    DieselRequest(
                        requestId = "closed",
                        command = "commands",
                    ),
                )

            assertEquals(
                DieselProtocolAdmission.CLOSED,
                submission.admission,
            )
            assertTrue(
                submission.completion.isCancelled,
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
                    queueCapacity = 32,
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
                it.completion.join()
            }

            assertEquals(
                (0L until 32L).toList(),
                responses,
            )
        }


}
