// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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

    /*
     * Submission itself must never block the GATT callback.
     *
     * BLE is already bandwidth-limited and Diesel requests are bounded by the
     * protocol decoder. Admission/back-pressure policy can be added as a
     * separate concern if another higher-throughput transport needs it.
     */
    private val queue =
        Channel<Work>(
            capacity = Channel.UNLIMITED,
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
    ): Job {
        val completion =
            CompletableDeferred<Unit>()

        val accepted =
            queue.trySend(
                Work.Request(
                    request = request,
                    completion = completion,
                ),
            )

        if (accepted.isFailure) {
            completion.cancel(
                CancellationException(
                    "Diesel protocol execution lane is closed",
                ),
            )
        }

        return completion
    }

    /**
     * Invalid decoded requests use the same FIFO worker. They therefore cannot
     * overtake valid requests already accepted by the protocol lane.
     */
    fun submitInvalid(
        failure: DieselInvalidRequest,
    ): Job {
        val completion =
            CompletableDeferred<Unit>()

        val accepted =
            queue.trySend(
                Work.Invalid(
                    failure = failure,
                    completion = completion,
                ),
            )

        if (accepted.isFailure) {
            completion.cancel(
                CancellationException(
                    "Diesel protocol execution lane is closed",
                ),
            )
        }

        return completion
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
