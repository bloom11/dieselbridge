// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service-owned recovery loop for Health Services passive observation.
 *
 * CapabilityRegistry remains the only provider arbiter. This controller only
 * decides when an unavailable Health Services observation binding is safe to
 * probe and re-advertise as AVAILABLE. Once that happens the registry's normal
 * priority rules re-promote Health Services and SensorObservationManager
 * switches the existing logical subscription without consumer resubscription.
 *
 * Backoff is retained across failed re-promotions and is reset only after the
 * provider actually reaches a healthy observation session.
 */
internal class HealthServicesObservationRecoveryController(
    scope: CoroutineScope,
    private val probeAvailable:
        suspend (logicalId: String) -> Boolean,
    private val promoteAvailable:
        (logicalId: String) -> Unit,
    retryDelaysMs:
        List<Long> =
        DEFAULT_RETRY_DELAYS_MS,
) : AutoCloseable {

    init {
        require(retryDelaysMs.isNotEmpty()) {
            "Health Services recovery requires at least one retry delay"
        }
        require(
            retryDelaysMs.all {
                it > 0L
            },
        ) {
            "Health Services recovery delays must be positive"
        }
    }

    private enum class RecoveryPhase {
        IDLE,
        WAITING,
        PROBING,
    }

    private data class RecoveryState(
        var generation: Long = 0L,
        var nextDelayIndex: Int = 0,
        var phase:
            RecoveryPhase =
            RecoveryPhase.IDLE,
        var job: Job? = null,
    )

    private val retryDelays =
        retryDelaysMs.toList()

    private val lock =
        Any()

    private val controllerJob =
        SupervisorJob(
            scope.coroutineContext[Job],
        )

    private val controllerScope =
        CoroutineScope(
            scope.coroutineContext +
                controllerJob,
        )

    private val recoveries =
        mutableMapOf<
            String,
            RecoveryState,
        >()

    private var closed =
        false

    /**
     * Records a provider-health failure and ensures one recovery job exists.
     *
     * Duplicate failure signals while we are merely waiting for the next probe
     * do not create another job or consume another backoff step. A failure that
     * races an in-flight probe invalidates that probe's attempted promotion.
     */
    fun providerFailed(
        logicalId: String,
    ) {
        require(logicalId.isNotBlank()) {
            "Logical sensor id must not be blank"
        }

        synchronized(lock) {
            if (closed) {
                return
            }

            val state =
                recoveries
                    .getOrPut(
                        logicalId,
                    ) {
                        RecoveryState()
                    }

            val activeJob =
                state.job
                    ?.isActive ==
                    true

            if (activeJob) {
                if (
                    state.phase ==
                    RecoveryPhase.PROBING
                ) {
                    state.generation =
                        nextGeneration(
                            state.generation,
                        )
                }

                return
            }

            state.generation =
                nextGeneration(
                    state.generation,
                )

            launchRecoveryLocked(
                logicalId =
                    logicalId,
                state =
                    state,
            )
        }
    }

    /**
     * Confirms that an actual observation session registered successfully.
     *
     * This is stronger than a capabilities/permission preflight and therefore
     * is the point at which exponential backoff is reset.
     */
    fun providerHealthy(
        logicalId: String,
    ) {
        require(logicalId.isNotBlank()) {
            "Logical sensor id must not be blank"
        }

        val job =
            synchronized(lock) {
                recoveries
                    .remove(
                        logicalId,
                    )
                    ?.job
            }

        job?.cancel()
    }

    private fun launchRecoveryLocked(
        logicalId: String,
        state: RecoveryState,
    ) {
        if (
            closed ||
            !controllerJob.isActive ||
            state.job
                ?.isActive ==
            true
        ) {
            return
        }

        val delayIndex =
            state.nextDelayIndex
                .coerceAtMost(
                    retryDelays.lastIndex,
                )

        val retryDelayMs =
            retryDelays[
                delayIndex
            ]

        if (
            state.nextDelayIndex <
            retryDelays.lastIndex
        ) {
            state.nextDelayIndex++
        }

        state.phase =
            RecoveryPhase.WAITING

        state.job =
            controllerScope.launch {
                runRecoveryAttempt(
                    logicalId =
                        logicalId,
                    state =
                        state,
                    retryDelayMs =
                        retryDelayMs,
                )
            }
    }

    private suspend fun runRecoveryAttempt(
        logicalId: String,
        state: RecoveryState,
        retryDelayMs: Long,
    ) {
        var promoted =
            false

        var attemptGeneration:
            Long? =
            null

        try {
            delay(
                retryDelayMs,
            )

            attemptGeneration =
                synchronized(lock) {
                    if (
                        closed ||
                        recoveries[
                            logicalId
                        ] !==
                        state
                    ) {
                        null
                    } else {
                        state.phase =
                            RecoveryPhase.PROBING

                        state.generation
                    }
                }

            val generation =
                attemptGeneration
                    ?: return

            val available =
                try {
                    probeAvailable(
                        logicalId,
                    )
                } catch (
                    cancellation:
                        CancellationException,
                ) {
                    throw cancellation
                } catch (
                    _: Throwable,
                ) {
                    false
                }

            if (
                available
            ) {
                /*
                 * Keep the generation check and non-suspending registry
                 * promotion serialized with providerFailed(). Service wiring
                 * records a new failure with this controller before marking
                 * the registry unavailable, so a stale recovery attempt cannot
                 * win the final availability state.
                 */
                synchronized(lock) {
                    if (
                        !closed &&
                        recoveries[
                            logicalId
                        ] ===
                        state &&
                        state.generation ==
                        generation
                    ) {
                        promoted =
                            runCatching {
                                promoteAvailable(
                                    logicalId,
                                )
                            }
                                .isSuccess
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (
                    recoveries[
                        logicalId
                    ] !==
                    state
                ) {
                    return@synchronized
                }

                state.job =
                    null

                state.phase =
                    RecoveryPhase.IDLE

                val generationChanged =
                    attemptGeneration !=
                        null &&
                        state.generation !=
                        attemptGeneration

                if (
                    !closed &&
                    controllerJob.isActive &&
                    (
                        !promoted ||
                            generationChanged
                    )
                ) {
                    launchRecoveryLocked(
                        logicalId =
                            logicalId,
                        state =
                            state,
                    )
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (
                closed
            ) {
                return
            }

            closed =
                true

            recoveries.clear()
        }

        controllerScope.cancel()
    }

    private fun nextGeneration(
        current: Long,
    ): Long =
        if (
            current ==
            Long.MAX_VALUE
        ) {
            1L
        } else {
            current +
                1L
        }

    private companion object {
        val DEFAULT_RETRY_DELAYS_MS =
            listOf(
                5_000L,
                15_000L,
                30_000L,
                60_000L,
                120_000L,
            )
    }
}
