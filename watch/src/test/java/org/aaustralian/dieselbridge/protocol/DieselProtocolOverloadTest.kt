// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DieselProtocolOverloadTest {
    @Test(timeout = 5000)
    fun feedbackIsBoundedSharedAndRecoversWithoutExecutingRejectedCommands(): Unit = runBlocking {
        val job = SupervisorJob()
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var executions = 0
            val registry = DieselCommandRegistry()
            registry.register(DieselCommandSpec("slow", "Synthetic slow command")) {
                executions++
                entered.complete(Unit)
                release.await()
                DieselCommandResult.ok()
            }
            val responses = mutableListOf<DieselResponse>()
            val lane = DieselProtocolExecutionLane(
                CoroutineScope(coroutineContext + job),
                DieselProtocolEngine(registry, DieselResponseTransport { responses += it; true }),
                queueCapacity = 1,
                rejectionCapacity = 2,
            )
            val running = lane.submit(DieselRequest("running", "slow"))
            entered.await()
            val waiting = lane.submit(DieselRequest("waiting", "slow"))
            assertEquals(DieselProtocolAdmission.FULL, lane.submit(DieselRequest("full-valid", "slow")).admission)
            assertEquals(DieselProtocolAdmission.FULL, lane.submitInvalid(invalid("full-invalid")).admission)
            assertEquals(DieselProtocolAdmission.FULL, lane.submit(DieselRequest("full-extra", "slow")).admission)
            // The waiting feedback worker has claimed the first item; two more fit in its queue.
            // No yield: flood cannot allocate more workers or waiting slots.
            repeat(1000) {
                val submission = if (it % 2 == 0) {
                    lane.submit(DieselRequest("drop-$it", "slow"))
                } else {
                    lane.submitInvalid(invalid("drop-$it"))
                }
                assertEquals(DieselProtocolAdmission.FULL, submission.admission)
                assertTrue(submission.completion.isCancelled)
            }
            assertEquals(2, job.children.count())
            yield()
            assertEquals(listOf("full-valid", "full-invalid", "full-extra"), responses.map { it.requestId })
            responses.forEach {
                assertEquals(DieselResponseStatus.RATE_LIMITED, it.status)
                assertEquals(mapOf("reason" to DieselValue.Text("execution_queue_full")), it.data)
            }
            assertEquals(1, executions)
            lane.submit(DieselRequest("feedback-recovered", "slow"))
            yield()
            assertEquals("feedback-recovered", responses.last().requestId)
            release.complete(Unit)
            running.completion.join()
            waiting.completion.join()
            assertEquals(2, executions)
            assertEquals(listOf("running", "waiting"), responses.takeLast(2).map { it.requestId })
            val afterDrain = lane.submit(DieselRequest("admission-recovered", "slow"))
            assertEquals(DieselProtocolAdmission.ACCEPTED, afterDrain.admission)
            afterDrain.completion.join()
            assertEquals(3, executions)
            assertEquals("admission-recovered", responses.last().requestId)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test(timeout = 5000)
    fun feedbackTransportFailureDoesNotKillEitherWorker(): Unit = runBlocking {
        val job = SupervisorJob()
        try {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val registry = DieselCommandRegistry()
            registry.register(DieselCommandSpec("slow", "Synthetic slow command")) {
                entered.complete(Unit)
                release.await()
                DieselCommandResult.ok()
            }
            val dispatches = mutableListOf<DieselProtocolDispatch>()
            val lane = DieselProtocolExecutionLane(
                CoroutineScope(coroutineContext + job),
                DieselProtocolEngine(
                    registry,
                    DieselResponseTransport {
                        when (it.requestId) {
                            "throws" -> error("transport failure")
                            "refused" -> false
                            else -> true
                        }
                    },
                    onDispatch = { dispatches += it },
                ),
                queueCapacity = 1,
            )
            val running = lane.submit(DieselRequest("running", "slow"))
            entered.await()
            val waiting = lane.submit(DieselRequest("waiting", "slow"))
            listOf("throws", "refused", "recovered").forEach {
                lane.submit(DieselRequest(it, "slow"))
                yield()
            }
            assertEquals(listOf(false, false, true), dispatches.map { it.sent })
            assertNotNull(dispatches[0].transportError)
            assertNull(dispatches[1].transportError)
            release.complete(Unit)
            running.completion.join()
            waiting.completion.join()
            assertEquals(listOf("running", "waiting"), dispatches.takeLast(2).map { it.request.requestId })
            assertTrue(dispatches.takeLast(2).all { it.sent })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test(timeout = 5000)
    fun shutdownDropsQueuedFeedbackAndClosedSubmissionsNeverSend(): Unit = runBlocking {
        val job = SupervisorJob()
        try {
            var sends = 0
            val lane = DieselProtocolExecutionLane(
                CoroutineScope(coroutineContext + job),
                DieselProtocolEngine(DieselCommandRegistry(), DieselResponseTransport { sends++; true }),
                queueCapacity = 1,
            )
            val accepted = lane.submit(DieselRequest("queued", "commands"))
            lane.submit(DieselRequest("full", "commands"))
            // Cancel before either worker gets CPU; also check CLOSED before cleanup has run.
            job.cancel()
            assertEquals(DieselProtocolAdmission.CLOSED, lane.submit(DieselRequest("closed", "commands")).admission)
            assertEquals(DieselProtocolAdmission.CLOSED, lane.submitInvalid(invalid("closed-invalid")).admission)
            job.join()
            assertTrue(accepted.completion.isCancelled)
            assertEquals(0, sends)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun engineOwnsOverloadCorrelationAndDoesNotLeakInvalidRequestDetail() {
        val engine = DieselProtocolEngine(DieselCommandRegistry(), DieselResponseTransport { true })
        val request = DieselRequest("correlation", "sensor.list", name = "heart_rate")
        val valid = engine.handleOverloaded(request).response
        assertEquals(request.requestId, valid.requestId)
        assertEquals(request.command, valid.command)
        assertEquals(request.name, valid.name)
        assertEquals(request.version, valid.version)
        assertEquals(DieselResponseStatus.RATE_LIMITED, valid.status)
        val invalid = engine.handleInvalidOverloaded(invalid(null)).response
        assertNull(invalid.requestId)
        assertEquals("protocol", invalid.command)
        assertNull(invalid.name)
        assertEquals(DieselResponseStatus.RATE_LIMITED, invalid.status)
        assertEquals(mapOf("reason" to DieselValue.Text("execution_queue_full")), invalid.data)
    }

    @Test
    fun maximumCorrelationFitsTheWireBudget() {
        val engine = DieselProtocolEngine(DieselCommandRegistry(), DieselResponseTransport { true })
        val request = DieselRequest(
            requestId = "\\".repeat(DieselProtocolRules.MAX_REQUEST_ID_LENGTH),
            command = "a".repeat(DieselProtocolRules.MAX_IDENTIFIER_LENGTH),
            name = "b".repeat(DieselProtocolRules.MAX_IDENTIFIER_LENGTH),
        )
        val response = engine.handleOverloaded(request).response
        assertTrue(
            DieselResponseCodec.encodeResponseJson(response).toByteArray(Charsets.UTF_8).size <=
                DieselResponseCodec.MAX_RESPONSE_JSON_BYTES,
        )
        assertTrue(DieselResponseCodec.encodeGadgetbridgeIntent(response).isNotEmpty())
    }

    @Test
    fun invalidFeedbackAndOrdinaryInvalidResponsesPropagateTransportCancellation() {
        val engine = DieselProtocolEngine(DieselCommandRegistry(), DieselResponseTransport {
            throw CancellationException("shutting down")
        })
        listOf<() -> Unit>(
            { engine.handleInvalid(invalid("invalid")) },
            { engine.handleInvalidOverloaded(invalid("overloaded")) },
            { engine.handleOverloaded(DieselRequest("valid", "commands")) },
        ).forEach { send ->
            try {
                send()
                fail("Cancellation must propagate")
            } catch (_: CancellationException) {
                // Expected lifecycle control flow.
            }
        }
    }

    private fun invalid(id: String?) = DieselInvalidRequest(
        requestId = id,
        command = null,
        name = null,
        reason = DieselRequestFailureReason.INVALID_COMMAND,
        detail = "private parser detail",
    )
}
