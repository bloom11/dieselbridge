// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor.observation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.event.SensorObservationSampleEvent
import org.aaustralian.dieselbridge.platform.event.SensorObservationStateEvent

/**
 * Publishes an already-owned [SensorSubscription] onto the process-local
 * [DieselEventBus].
 *
 * This bridge deliberately does not create a SensorObservationClient, open a
 * subscription, select a provider, or close the supplied subscription.
 *
 * The caller owns:
 * - the sensor client/subscription;
 * - the CoroutineScope used for forwarding;
 * - the decision about when observation should exist.
 *
 * Closing the returned registration only stops EventBus forwarding.
 */
class SensorObservationEventBridge(
    private val events: DieselEventBus,
    private val wallClockMs: () -> Long = {
        System.currentTimeMillis()
    },
) {

    fun attach(
        subscription: SensorSubscription,
        scope: CoroutineScope,
    ): SensorObservationEventRegistration {
        val registrationJob =
            SupervisorJob(
                scope.coroutineContext[
                    Job
                ],
            )

        val registrationScope =
            CoroutineScope(
                scope.coroutineContext +
                    registrationJob,
            )

        /*
         * State and samples are distinct upstream streams, but EventBus is one
         * ordered publication stream. Serialize publication without adding
         * another sensor-sample queue.
         *
         * Before a sample is published, publish the subscription's latest
         * state if it has not reached EventBus yet. This prevents a first
         * sample from overtaking the ACTIVE state that admitted it.
         */
        val publicationMutex =
            Mutex()

        var lastPublishedState:
            SensorSubscriptionState? =
            null

        suspend fun publishStateIfNeeded(
            state: SensorSubscriptionState,
        ) {
            publicationMutex.withLock {
                if (
                    lastPublishedState ==
                        state
                ) {
                    return@withLock
                }

                events.emit(
                    SensorObservationStateEvent(
                        timestampMs =
                            wallClockMs(),
                        state =
                            state,
                    ),
                )

                lastPublishedState =
                    state
            }
        }

        suspend fun publishSample(
            sample: SensorSubscriptionSample,
        ) {
            publicationMutex.withLock {
                val currentState =
                    subscription
                        .state
                        .value

                if (
                    lastPublishedState !=
                        currentState
                ) {
                    events.emit(
                        SensorObservationStateEvent(
                            timestampMs =
                                wallClockMs(),
                            state =
                                currentState,
                        ),
                    )

                    lastPublishedState =
                        currentState
                }

                /*
                 * CLOSED is terminal on the EventBus. SensorSubscription closes
                 * its channel after publishing CLOSED, so already-buffered
                 * samples may still be drainable. Never publish one after the
                 * terminal state.
                 */
                if (
                    currentState.phase ==
                        SensorSubscriptionPhase.CLOSED
                ) {
                    return@withLock
                }

                events.emit(
                    SensorObservationSampleEvent(
                        timestampMs =
                            wallClockMs(),
                        subscriptionId =
                            subscription.id,
                        logicalId =
                            subscription.logicalId,
                        sample =
                            sample,
                    ),
                )
            }
        }

        val lifecycleJob =
            registrationScope.launch {
                coroutineScope {
                    /*
                     * State and samples remain separate upstream streams.
                     * We do not add another sample queue here.
                     */
                    val sampleJob =
                        launch {
                            subscription
                                .samples
                                .collect {
                                        sample,
                                    ->
                                    publishSample(
                                        sample,
                                    )
                                }
                        }

                    try {
                        /*
                         * StateFlow immediately publishes the current state.
                         * Publish CLOSED as the terminal state, then stop this
                         * forwarding registration.
                         */
                        subscription
                            .state
                            .onEach {
                                    state,
                                ->
                                publishStateIfNeeded(
                                    state,
                                )
                            }
                            .takeWhile {
                                    state,
                                ->
                                state.phase !=
                                    SensorSubscriptionPhase.CLOSED
                            }
                            .collect {
                                // Publication happens in onEach above.
                            }
                    } finally {
                        sampleJob
                            .cancelAndJoin()
                    }
                }
            }

        /*
         * Retire the supervisor when either upstream stream terminates the
         * lifecycle job. This keeps one completed attachment from leaving a
         * parent Job behind.
         */
        lifecycleJob.invokeOnCompletion {
            registrationJob.cancel()
        }

        return SensorObservationEventRegistration(
            job =
                registrationJob,
        )
    }
}

/**
 * Lifecycle handle for EventBus forwarding only.
 *
 * It never closes the underlying [SensorSubscription].
 */
class SensorObservationEventRegistration internal constructor(
    private val job: Job,
) : AutoCloseable {

    val isClosed: Boolean
        get() =
            !job.isActive

    override fun close() {
        job.cancel()
    }

    suspend fun join() {
        job.join()
    }
}
