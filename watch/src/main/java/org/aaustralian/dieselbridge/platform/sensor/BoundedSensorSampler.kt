// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android registration mechanism used for one bounded sample.
 *
 * ONE_SHOT Android sensors use TRIGGER. All other reporting modes initially
 * use LISTENER.
 */
internal enum class SensorRegistrationKind {
    LISTENER,
    TRIGGER,
}

/**
 * Immutable copy of one framework sensor event.
 *
 * Values are copied before leaving the Android callback so callers never keep
 * a reference to SensorEvent.values or TriggerEvent.values.
 *
 * TriggerEvent does not expose an accuracy field, hence nullable accuracy.
 */
internal data class SensorRawEvent(
    val timestampNanos: Long,
    val accuracy: Int?,
    val values: List<Float>,
)

/**
 * Synchronous result of attempting to activate a registration.
 */
internal sealed interface SensorRegistrationStart {

    object Started :
        SensorRegistrationStart

    data class PermissionDenied(
        val requiredPermission: String?,
    ) : SensorRegistrationStart

    data class Rejected(
        val reason: String?,
    ) : SensorRegistrationStart
}

/**
 * One disposable sensor registration.
 *
 * stop() must be safe to call after partial registration and should be
 * idempotent. The bounded coordinator still guards it so terminal races do not
 * cause multiple logical cleanups.
 */
internal interface SensorSampleRegistration {

    val kind:
        SensorRegistrationKind

    /**
     * Start one registration.
     *
     * stop() is allowed to race this method or even run before it. A concrete
     * implementation must never activate hardware after it has entered its
     * stopped state.
     */
    fun start(
        onEvent:
            (SensorRawEvent) -> Unit,
    ): SensorRegistrationStart

    fun stop()
}

/**
 * Framework-independent atomic registration state.
 *
 * The important property is that STOPPED is terminal. If cancellation calls
 * stop() before start() reaches SensorManager, a later begin() cannot revive
 * the registration.
 */
internal class SensorRegistrationLifecycle {

    private enum class State {
        NEW,
        STARTING,
        ACTIVE,
        STOPPED,
    }

    private val state =
        java.util.concurrent.atomic.AtomicReference(
            State.NEW,
        )

    fun begin(): Boolean =
        state.compareAndSet(
            State.NEW,
            State.STARTING,
        )

    fun isStarting(): Boolean =
        state.get() ==
            State.STARTING

    fun markAccepted(): Boolean =
        state.compareAndSet(
            State.STARTING,
            State.ACTIVE,
        )

    fun markRejected() {
        state.compareAndSet(
            State.STARTING,
            State.STOPPED,
        )
    }

    /**
     * Returns true when platform cleanup may be required.
     *
     * STARTING is included because stop() can race the synchronous Android
     * registration call. In that case cleanup runs both immediately and again
     * after the registration call if it eventually succeeds.
     */
    fun stop(): Boolean {
        val previous =
            state.getAndSet(
                State.STOPPED,
            )

        return previous ==
            State.STARTING ||
            previous ==
            State.ACTIVE
    }
}

/**
 * Terminal result of one bounded sampling attempt.
 *
 * Protocol status mapping deliberately does not live here. A future
 * debug.sensor.probe command can map timeout/permission/rejection to
 * status=ok experiment outcomes without coupling this reusable sampler to the
 * Diesel wire protocol.
 */
internal sealed interface BoundedSensorSampleOutcome {

    val registrationKind:
        SensorRegistrationKind

    val elapsedMs:
        Long

    data class Event(
        override val registrationKind:
            SensorRegistrationKind,
        override val elapsedMs:
            Long,
        val event:
            SensorRawEvent,
    ) : BoundedSensorSampleOutcome

    data class Timeout(
        override val registrationKind:
            SensorRegistrationKind,
        override val elapsedMs:
            Long,
    ) : BoundedSensorSampleOutcome

    data class PermissionDenied(
        override val registrationKind:
            SensorRegistrationKind,
        override val elapsedMs:
            Long,
        val requiredPermission:
            String?,
    ) : BoundedSensorSampleOutcome

    data class RegistrationRejected(
        override val registrationKind:
            SensorRegistrationKind,
        override val elapsedMs:
            Long,
        val reason:
            String?,
    ) : BoundedSensorSampleOutcome
}

/**
 * Coroutine-side exactly-once lifecycle for one sensor sample.
 *
 * The registration itself may callback from another thread. Event completion,
 * timeout and external cancellation therefore race through one AtomicBoolean.
 *
 * External coroutine cancellation propagates normally. Only this sampler's
 * own timeout is converted to [BoundedSensorSampleOutcome.Timeout].
 */
internal class BoundedSensorSampler(
    private val monotonicNanos:
        () -> Long = System::nanoTime,
) {

    suspend fun sample(
        registration:
            SensorSampleRegistration,
        timeoutMs: Long,
    ): BoundedSensorSampleOutcome {
        require(
            timeoutMs > 0,
        ) {
            "Sensor sample timeout must be positive"
        }

        val startedAt =
            monotonicNanos()

        val completed =
            withTimeoutOrNull(
                timeoutMs,
            ) {
                suspendCancellableCoroutine<
                    BoundedSensorSampleOutcome
                > { continuation ->
                    val finished =
                        AtomicBoolean(
                            false,
                        )

                    fun stopQuietly() {
                        runCatching {
                            registration.stop()
                        }
                    }

                    fun finish(
                        outcome:
                            BoundedSensorSampleOutcome,
                    ) {
                        if (
                            !finished.compareAndSet(
                                false,
                                true,
                            )
                        ) {
                            return
                        }

                        stopQuietly()

                        if (
                            continuation.isActive
                        ) {
                            continuation.resume(
                                outcome,
                            )
                        }
                    }

                    continuation
                        .invokeOnCancellation {
                            if (
                                finished.compareAndSet(
                                    false,
                                    true,
                                )
                            ) {
                                stopQuietly()
                            }
                        }

                    /*
                     * Cancellation can arrive immediately after the handler is
                     * installed. Avoid entering start() when it has already
                     * won. SensorSampleRegistration must still independently
                     * handle the remaining check-to-start race.
                     */
                    if (
                        finished.get() ||
                        !continuation.isActive
                    ) {
                        return@suspendCancellableCoroutine
                    }

                    val startResult =
                        try {
                            registration.start { event ->
                                finish(
                                    BoundedSensorSampleOutcome
                                        .Event(
                                            registrationKind =
                                                registration.kind,
                                            elapsedMs =
                                                elapsedMs(
                                                    startedAt,
                                                ),
                                            event =
                                                event,
                                        ),
                                )
                            }
                        } catch (
                            error: Throwable,
                        ) {
                            if (
                                finished.compareAndSet(
                                    false,
                                    true,
                                )
                            ) {
                                stopQuietly()

                                if (
                                    continuation.isActive
                                ) {
                                    continuation
                                        .resumeWithException(
                                            error,
                                        )
                                }
                            }

                            return@suspendCancellableCoroutine
                        }

                    /*
                     * A test/future backend may synchronously produce an event
                     * from start(). If that happened, the event already won
                     * the terminal race and no start result should overwrite it.
                     */
                    if (
                        finished.get()
                    ) {
                        return@suspendCancellableCoroutine
                    }

                    when (
                        startResult
                    ) {
                        SensorRegistrationStart.Started ->
                            Unit

                        is SensorRegistrationStart.PermissionDenied ->
                            finish(
                                BoundedSensorSampleOutcome
                                    .PermissionDenied(
                                        registrationKind =
                                            registration.kind,
                                        elapsedMs =
                                            elapsedMs(
                                                startedAt,
                                            ),
                                        requiredPermission =
                                            startResult
                                                .requiredPermission,
                                    ),
                            )

                        is SensorRegistrationStart.Rejected ->
                            finish(
                                BoundedSensorSampleOutcome
                                    .RegistrationRejected(
                                        registrationKind =
                                            registration.kind,
                                        elapsedMs =
                                            elapsedMs(
                                                startedAt,
                                            ),
                                        reason =
                                            startResult.reason,
                                    ),
                            )
                    }
                }
            }

        return completed
            ?: BoundedSensorSampleOutcome
                .Timeout(
                    registrationKind =
                        registration.kind,
                    elapsedMs =
                        elapsedMs(
                            startedAt,
                        ),
                )
    }

    private fun elapsedMs(
        startedAtNanos: Long,
    ): Long =
        (
            monotonicNanos() -
                startedAtNanos
        )
            .coerceAtLeast(
                0L,
            ) /
            NANOS_PER_MILLISECOND

    private companion object {
        const val NANOS_PER_MILLISECOND =
            1_000_000L
    }
}
