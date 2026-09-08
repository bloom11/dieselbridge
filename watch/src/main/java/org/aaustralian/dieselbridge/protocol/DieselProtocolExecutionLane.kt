// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Synchronous result of attempting to admit protocol work.
 */
enum class DieselProtocolAdmission {
    ACCEPTED,
    FULL,
    CLOSED,
}

/**
 * Admission result plus completion of accepted work.
 *
 * Delegating Job preserves the existing join/cancel caller API.
 */
data class DieselProtocolSubmission(
    val admission: DieselProtocolAdmission,
    val completion: Job,
) : Job by completion

/**
 * FIFO asynchronous execution boundary for Diesel control-plane work.
 *
 * Transports enqueue requests and return immediately. A single service-owned
 * worker consumes requests in submission order. Commands may therefore
 * suspend without blocking BLE/GATT callbacks while still preserving strict
 * serialized protocol execution.
 *
 * This class deliberately contains no BLE, sensor, Gadgetbridge or
 * command-specific knowledge.
 */
class DieselProtocolExecutionLane(
    scope: CoroutineScope,
    private val engine: DieselProtocolEngine,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private sealed interface Work {
        val completion: CompletableDeferred<Unit>

        data class Request(
            val request: DieselRequest,
            override val completion:
                CompletableDeferred<Unit>,
        ) : Work

        data class Invalid(
            val failure: DieselInvalidRequest,
            override val completion:
                CompletableDeferred<Unit>,
        ) : Work
    }

    init {
        require(queueCapacity > 0) {
            "Diesel protocol queue capacity must be positive"
        }
    }

    /*
     * Submission itself must never block the transport callback.
     *
     * Capacity counts waiting work, not the item currently held by the
     * single worker. The default bound is therefore:
     *
     *     1 running + 4 waiting = at most 5 admitted requests.
     */
    private val queue =
        Channel<Work>(
            capacity = queueCapacity,
            onUndeliveredElement = {
                    work,
                ->
                work.completion.cancel(
                    CancellationException(
                        "Diesel protocol work was not delivered",
                    ),
                )
            },
        )

    private val worker =
        scope.launch {
            for (work in queue) {
                execute(
                    work,
                )
            }
        }

    init {
        worker.invokeOnCompletion {
                cause,
            ->
            queue.cancel(
                cancellationException(
                    cause,
                ),
            )
        }
    }

    /**
     * Queue a valid Diesel request.
     *
     * Channel.trySend is non-blocking, so transport callbacks return without
     * waiting for command execution.
     */
    fun submit(
        request: DieselRequest,
    ): DieselProtocolSubmission {
        val completion =
            CompletableDeferred<Unit>()

        val result =
            queue.trySend(
                Work.Request(
                    request = request,
                    completion = completion,
                ),
            )

        return submissionFor(
            result = result,
            completion = completion,
        )
    }

    /**
     * Invalid decoded requests use the same FIFO worker. They therefore cannot
     * overtake valid requests already accepted by the protocol lane.
     */
    fun submitInvalid(
        failure: DieselInvalidRequest,
    ): DieselProtocolSubmission {
        val completion =
            CompletableDeferred<Unit>()

        val result =
            queue.trySend(
                Work.Invalid(
                    failure = failure,
                    completion = completion,
                ),
            )

        return submissionFor(
            result = result,
            completion = completion,
        )
    }

    private fun submissionFor(
        result: kotlinx.coroutines.channels.ChannelResult<Unit>,
        completion: CompletableDeferred<Unit>,
    ): DieselProtocolSubmission {
        val admission =
            when {
                result.isSuccess ->
                    DieselProtocolAdmission.ACCEPTED

                result.isClosed ->
                    DieselProtocolAdmission.CLOSED

                else ->
                    DieselProtocolAdmission.FULL
            }

        if (admission != DieselProtocolAdmission.ACCEPTED) {
            completion.cancel(
                CancellationException(
                    when (admission) {
                        DieselProtocolAdmission.FULL ->
                            "Diesel protocol execution queue is full"

                        DieselProtocolAdmission.CLOSED ->
                            "Diesel protocol execution lane is closed"

                        DieselProtocolAdmission.ACCEPTED ->
                            error("Accepted work must not be cancelled")
                    },
                ),
            )
        }

        return DieselProtocolSubmission(
            admission = admission,
            completion = completion,
        )
    }

    private suspend fun execute(
        work: Work,
    ) {
        try {
            when (work) {
                is Work.Request ->
                    engine.handle(
                        work.request,
                    )

                is Work.Invalid ->
                    engine.handleInvalid(
                        work.failure,
                    )
            }

            work.completion.complete(
                Unit,
            )
        } catch (error: CancellationException) {
            work.completion.cancel(
                error,
            )

            throw error
        } catch (error: Exception) {
            /*
             * DieselProtocolEngine normally converts handler/transport
             * exceptions into dispatch results itself. Keep the worker alive
             * if an unexpected ordinary exception escapes that boundary.
             */
            work.completion.completeExceptionally(
                error,
            )
        }
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY =
            4
    }

    private fun cancellationException(
        cause: Throwable?,
    ): CancellationException {
        if (cause is CancellationException) {
            return cause
        }

        return CancellationException(
            "Diesel protocol execution lane stopped",
        ).also {
            if (cause != null) {
                it.initCause(
                    cause,
                )
            }
        }
    }
}
