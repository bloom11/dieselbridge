// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

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
 * FULL/CLOSED completions are cancelled immediately. FULL feedback is best effort
 * on a separate bounded lane and is not represented by this completion.
 */
data class DieselProtocolSubmission(
    val admission: DieselProtocolAdmission,
    val completion: Job,
)

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
    rejectionCapacity: Int = DEFAULT_REJECTION_CAPACITY,
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
        require(rejectionCapacity > 0) {
            "Diesel protocol rejection capacity must be positive"
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

    /*
     * One fixed worker and a finite feedback queue, never a coroutine per rejection.
     * Overload replies may overtake admitted work. If feedback is also full, drop
     * the newest feedback; never evict accepted command work or retry indefinitely.
     */
    private val rejections = Channel<Work>(capacity = rejectionCapacity)

    private val rejectionWorker = scope.launch {
        for (work in rejections) {
            coroutineContext.ensureActive()
            try {
                when (work) {
                    is Work.Request -> engine.handleOverloaded(work.request)
                    is Work.Invalid -> engine.handleInvalidOverloaded(work.failure)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A failed feedback attempt must not kill either worker.
            }
        }
    }

    private val worker =
        scope.launch {
            for (work in queue) {
                execute(
                    work,
                )
            }
        }

    init {
        rejectionWorker.invokeOnCompletion { cause ->
            rejections.cancel(cancellationException(cause))
        }
        worker.invokeOnCompletion {
                cause,
            ->
            rejectionWorker.cancel(cancellationException(cause))
            rejections.cancel(cancellationException(cause))
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
        return submitWork(Work.Request(request, CompletableDeferred()))
    }

    /** Invalid requests consume exactly the same finite admission budget. */
    fun submitInvalid(
        failure: DieselInvalidRequest,
    ): DieselProtocolSubmission =
        submitWork(Work.Invalid(failure, CompletableDeferred()))

    private fun submitWork(work: Work): DieselProtocolSubmission {
        // Scope cancellation marks the worker inactive before its cleanup callback runs.
        val result = if (worker.isActive) queue.trySend(work) else null
        val admission = when {
            result == null || result.isClosed -> DieselProtocolAdmission.CLOSED
            result.isSuccess -> DieselProtocolAdmission.ACCEPTED
            else -> DieselProtocolAdmission.FULL
        }

        if (admission != DieselProtocolAdmission.ACCEPTED) {
            work.completion.cancel(
                CancellationException(
                    if (admission == DieselProtocolAdmission.FULL) {
                        "Diesel protocol execution queue is full"
                    } else {
                        "Diesel protocol execution lane is closed"
                    },
                ),
            )
        }
        if (admission == DieselProtocolAdmission.FULL && worker.isActive) {
            rejections.trySend(work)
        }
        return DieselProtocolSubmission(admission, work.completion)
    }

    private suspend fun execute(
        work: Work,
    ) {
        try {
            coroutineContext.ensureActive()
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
        const val DEFAULT_REJECTION_CAPACITY =
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
