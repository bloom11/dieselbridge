// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedSensorSamplerTest {

    @Test
    fun eventStopsRegistrationAndPreservesRawEvent(): Unit = runBlocking {
        var nowNanos =
            1_000_000_000L

        val sampler =
            BoundedSensorSampler {
                nowNanos
            }

        val registration =
            FakeRegistration()

        val pending =
            async {
                sampler.sample(
                    registration =
                        registration,
                    timeoutMs =
                        5_000L,
                )
            }

        registration
            .started
            .await()

        nowNanos +=
            123_000_000L

        registration.emit(
            SensorRawEvent(
                timestampNanos =
                    42L,
                accuracy =
                    3,
                values =
                    listOf(
                        1.5f,
                        Float.NaN,
                        Float.POSITIVE_INFINITY,
                    ),
            ),
        )

        val outcome =
            pending.await()

        assertTrue(
            outcome is
                BoundedSensorSampleOutcome.Event,
        )

        outcome as
            BoundedSensorSampleOutcome.Event

        assertEquals(
            SensorRegistrationKind.LISTENER,
            outcome.registrationKind,
        )

        assertEquals(
            123L,
            outcome.elapsedMs,
        )

        assertEquals(
            42L,
            outcome.event.timestampNanos,
        )

        assertEquals(
            3,
            outcome.event.accuracy,
        )

        assertEquals(
            3,
            outcome.event.values.size,
        )

        assertTrue(
            outcome.event.values[1].isNaN(),
        )

        assertEquals(
            Float.POSITIVE_INFINITY,
            outcome.event.values[2],
        )

        assertEquals(
            1,
            registration.stopCalls,
        )
    }

    @Test
    fun synchronousEventDuringStartWinsExactlyOnce(): Unit = runBlocking {
        val registration =
            FakeRegistration(
                synchronousEvent =
                    SensorRawEvent(
                        timestampNanos =
                            100L,
                        accuracy =
                            null,
                        values =
                            listOf(
                                9.0f,
                            ),
                    ),
            )

        val outcome =
            BoundedSensorSampler()
                .sample(
                    registration =
                        registration,
                    timeoutMs =
                        5_000L,
                )

        assertTrue(
            outcome is
                BoundedSensorSampleOutcome.Event,
        )

        assertEquals(
            1,
            registration.stopCalls,
        )
    }

    @Test
    fun ownTimeoutStopsRegistration(): Unit = runBlocking {
        val registration =
            FakeRegistration()

        val outcome =
            BoundedSensorSampler()
                .sample(
                    registration =
                        registration,
                    timeoutMs =
                        50L,
                )

        assertTrue(
            outcome is
                BoundedSensorSampleOutcome.Timeout,
        )

        assertEquals(
            1,
            registration.stopCalls,
        )
    }

    @Test
    fun externalCancellationStopsWithoutReturningTimeout(): Unit = runBlocking {
        val registration =
            FakeRegistration()

        val job =
            launch {
                BoundedSensorSampler()
                    .sample(
                        registration =
                            registration,
                        timeoutMs =
                            10_000L,
                    )
            }

        registration
            .started
            .await()

        job.cancelAndJoin()

        assertTrue(
            job.isCancelled,
        )

        assertEquals(
            1,
            registration.stopCalls,
        )
    }

    @Test
    fun permissionFailureIsTerminalOutcome(): Unit = runBlocking {
        val registration =
            FakeRegistration(
                startResult =
                    SensorRegistrationStart
                        .PermissionDenied(
                            requiredPermission =
                                "android.permission.BODY_SENSORS",
                        ),
            )

        val outcome =
            BoundedSensorSampler()
                .sample(
                    registration =
                        registration,
                    timeoutMs =
                        5_000L,
                )

        assertTrue(
            outcome is
                BoundedSensorSampleOutcome
                    .PermissionDenied,
        )

        outcome as
            BoundedSensorSampleOutcome
                .PermissionDenied

        assertEquals(
            "android.permission.BODY_SENSORS",
            outcome.requiredPermission,
        )

        assertEquals(
            1,
            registration.stopCalls,
        )
    }

    @Test
    fun registrationRejectionIsTerminalOutcome(): Unit = runBlocking {
        val registration =
            FakeRegistration(
                kind =
                    SensorRegistrationKind.TRIGGER,
                startResult =
                    SensorRegistrationStart
                        .Rejected(
                            reason =
                                "registration_returned_false",
                        ),
            )

        val outcome =
            BoundedSensorSampler()
                .sample(
                    registration =
                        registration,
                    timeoutMs =
                        5_000L,
                )

        assertTrue(
            outcome is
                BoundedSensorSampleOutcome
                    .RegistrationRejected,
        )

        outcome as
            BoundedSensorSampleOutcome
                .RegistrationRejected

        assertEquals(
            SensorRegistrationKind.TRIGGER,
            outcome.registrationKind,
        )

        assertEquals(
            "registration_returned_false",
            outcome.reason,
        )

        assertEquals(
            1,
            registration.stopCalls,
        )
    }

    @Test
    fun registrationLifecycleCannotRestartAfterEarlyStop() {
        val lifecycle =
            SensorRegistrationLifecycle()

        assertTrue(
            !lifecycle.stop(),
        )

        assertTrue(
            !lifecycle.begin(),
        )

        assertTrue(
            !lifecycle.markAccepted(),
        )
    }

    @Test
    fun registrationLifecycleRejectsAcceptanceAfterStopDuringStart() {
        val lifecycle =
            SensorRegistrationLifecycle()

        assertTrue(
            lifecycle.begin(),
        )

        /*
         * STARTING may already be inside registerListener(), so stop reports
         * that platform cleanup is potentially required.
         */
        assertTrue(
            lifecycle.stop(),
        )

        /*
         * A framework registration that returns successfully after the stop
         * cannot transition the state back to ACTIVE.
         */
        assertTrue(
            !lifecycle.markAccepted(),
        )

        assertTrue(
            !lifecycle.begin(),
        )
    }

    @Test
    fun registrationLifecycleActiveStopIsIdempotent() {
        val lifecycle =
            SensorRegistrationLifecycle()

        assertTrue(
            lifecycle.begin(),
        )

        assertTrue(
            lifecycle.markAccepted(),
        )

        assertTrue(
            lifecycle.stop(),
        )

        assertTrue(
            !lifecycle.stop(),
        )

        assertTrue(
            !lifecycle.begin(),
        )
    }

    @Test
    fun unexpectedStartFailureStopsOnceAndPropagates(): Unit = runBlocking {
        var stops = 0
        val failure = IllegalStateException("start failure")
        val registration = object : SensorSampleRegistration {
            override val kind = SensorRegistrationKind.LISTENER
            override fun start(onEvent: (SensorRawEvent) -> Unit): SensorRegistrationStart = throw failure
            override fun stop() { stops++ }
        }
        try {
            BoundedSensorSampler().sample(registration, 500)
            org.junit.Assert.fail("Start failure must propagate")
        } catch (error: IllegalStateException) {
            // Coroutine stack-trace recovery may copy the exception across the suspension boundary.
            assertEquals(failure.javaClass, error.javaClass)
            assertEquals(failure.message, error.message)
        }
        assertEquals(1, stops)
    }

    @Test
    fun synchronousEventCannotBeOverwrittenBySubsequentRejection(): Unit = runBlocking {
        val raw = SensorRawEvent(100, null, listOf(9f))
        val registration = FakeRegistration(
            startResult = SensorRegistrationStart.Rejected("late rejection"),
            synchronousEvent = raw,
        )
        val result = BoundedSensorSampler().sample(registration, 500)
        assertEquals(raw, (result as BoundedSensorSampleOutcome.Event).event)
        assertEquals(1, registration.stopCalls)
    }

    @Test
    fun lateRejectionCannotReviveStoppedRegistration() {
        val lifecycle = SensorRegistrationLifecycle()
        assertTrue(lifecycle.begin())
        lifecycle.stop()
        lifecycle.markRejected()
        assertTrue(!lifecycle.begin())
        assertTrue(!lifecycle.markAccepted())
        assertTrue(!lifecycle.stop())
    }

    private class FakeRegistration(
        override val kind:
            SensorRegistrationKind =
            SensorRegistrationKind.LISTENER,
        private val startResult:
            SensorRegistrationStart =
            SensorRegistrationStart.Started,
        private val synchronousEvent:
            SensorRawEvent? =
            null,
    ) : SensorSampleRegistration {

        val started =
            CompletableDeferred<Unit>()

        var stopCalls:
            Int =
            0
            private set

        private var onEvent:
            ((SensorRawEvent) -> Unit)? =
            null

        override fun start(
            onEvent:
                (SensorRawEvent) -> Unit,
        ): SensorRegistrationStart {
            this.onEvent =
                onEvent

            started.complete(
                Unit,
            )

            synchronousEvent
                ?.let(
                    onEvent,
                )

            return startResult
        }

        override fun stop() {
            stopCalls +=
                1
        }

        fun emit(
            event:
                SensorRawEvent,
        ) {
            checkNotNull(
                onEvent,
            )(
                event,
            )
        }
    }
}
