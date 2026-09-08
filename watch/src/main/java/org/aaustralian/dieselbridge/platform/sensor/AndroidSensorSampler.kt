// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded first-event sampler for exact Android SensorManager routes.
 *
 * This class activates only the Sensor carried by the supplied
 * [AndroidSensorHandle]. Provider selection and logical sensor routing do not
 * happen here.
 *
 * Listener callbacks use the main looper explicitly. At the current probe/read
 * rate they only copy one event and resume a coroutine; no interpretation or
 * protocol encoding runs on the main thread.
 */
internal class AndroidSensorSampler(
    context: Context,
    private val callbackHandler:
        Handler =
        Handler(
            Looper.getMainLooper(),
        ),
    private val boundedSampler:
        BoundedSensorSampler =
        BoundedSensorSampler {
            SystemClock
                .elapsedRealtimeNanos()
        },
) {

    private val sensorManager =
        context.applicationContext
            .getSystemService(
                SensorManager::class.java,
            )

    suspend fun sample(
        handle:
            AndroidSensorHandle,
        timeoutMs: Long =
            DEFAULT_TIMEOUT_MS,
        samplePeriodUs: Int =
            DEFAULT_SAMPLE_PERIOD_US,
    ): BoundedSensorSampleOutcome {
        require(
            timeoutMs in
                MIN_TIMEOUT_MS..
                MAX_TIMEOUT_MS,
        ) {
            "Sensor sample timeout out of bounds"
        }

        require(
            samplePeriodUs > 0,
        ) {
            "Sensor sample period must be positive"
        }

        val kind =
            registrationKind(
                handle.sensor,
            )

        val manager =
            sensorManager
                ?: return BoundedSensorSampleOutcome
                    .RegistrationRejected(
                        registrationKind =
                            kind,
                        elapsedMs =
                            0L,
                        reason =
                            REASON_SENSOR_MANAGER_UNAVAILABLE,
                    )

        return boundedSampler
            .sample(
                registration =
                    SensorManagerSampleRegistration(
                        sensorManager =
                            manager,
                        sensor =
                            handle.sensor,
                        callbackHandler =
                            callbackHandler,
                        samplePeriodUs =
                            samplePeriodUs,
                        kind =
                            kind,
                    ),
                timeoutMs =
                    timeoutMs,
            )
    }

    private fun registrationKind(
        sensor: Sensor,
    ): SensorRegistrationKind =
        if (
            sensor.reportingMode ==
            Sensor.REPORTING_MODE_ONE_SHOT
        ) {
            SensorRegistrationKind.TRIGGER
        } else {
            SensorRegistrationKind.LISTENER
        }

    companion object {
        const val DEFAULT_TIMEOUT_MS =
            5_000L

        const val MIN_TIMEOUT_MS =
            500L

        const val MAX_TIMEOUT_MS =
            15_000L

        const val DEFAULT_SAMPLE_PERIOD_US =
            200_000

        private const val REASON_SENSOR_MANAGER_UNAVAILABLE =
            "sensor_manager_unavailable"
    }
}

/**
 * One framework registration corresponding to one bounded sample attempt.
 *
 * active is set before the framework registration call. If cancellation races
 * the synchronous registration call, start() performs a second cleanup after
 * that call returns so an accepted late registration cannot leak.
 */
private class SensorManagerSampleRegistration(
    private val sensorManager:
        SensorManager,
    private val sensor:
        Sensor,
    private val callbackHandler:
        Handler,
    private val samplePeriodUs:
        Int,
    override val kind:
        SensorRegistrationKind,
) : SensorSampleRegistration {

    private val lifecycle =
        SensorRegistrationLifecycle()

    private val callback =
        AtomicReference<
            ((SensorRawEvent) -> Unit)?
        >(
            null,
        )

    private val listener =
        object :
            SensorEventListener {

            override fun onSensorChanged(
                event: SensorEvent,
            ) {
                callback
                    .get()
                    ?.invoke(
                        SensorRawEvent(
                            timestampNanos =
                                event.timestamp,
                            accuracy =
                                event.accuracy,
                            values =
                                event.values
                                    .toList(),
                        ),
                    )
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) {
                // The first SensorEvent carries the accuracy we export.
            }
        }

    private val triggerListener =
        object :
            TriggerEventListener() {

            override fun onTrigger(
                event: TriggerEvent,
            ) {
                callback
                    .get()
                    ?.invoke(
                        SensorRawEvent(
                            timestampNanos =
                                event.timestamp,
                            accuracy =
                                null,
                            values =
                                event.values
                                    .toList(),
                        ),
                    )
            }
        }

    override fun start(
        onEvent:
            (SensorRawEvent) -> Unit,
    ): SensorRegistrationStart {
        /*
         * begin() is the terminal-state barrier. If stop() won before start,
         * this registration can never become STARTING again.
         */
        if (
            !lifecycle.begin()
        ) {
            return SensorRegistrationStart
                .Rejected(
                    reason =
                        REASON_NOT_STARTABLE,
                )
        }

        if (
            !callback.compareAndSet(
                null,
                onEvent,
            )
        ) {
            lifecycle.markRejected()

            return SensorRegistrationStart
                .Rejected(
                    reason =
                        REASON_ALREADY_STARTED,
                )
        }

        /*
         * stop() may have raced begin() before the callback was installed.
         * Avoid SensorManager entirely when that race is already visible.
         */
        if (
            !lifecycle.isStarting()
        ) {
            callback.set(
                null,
            )

            return SensorRegistrationStart
                .Rejected(
                    reason =
                        REASON_STOPPED_BEFORE_REGISTRATION,
                )
        }

        val accepted =
            try {
                when (
                    kind
                ) {
                    SensorRegistrationKind.LISTENER ->
                        sensorManager
                            .registerListener(
                                listener,
                                sensor,
                                samplePeriodUs,
                                MAX_REPORT_LATENCY_US,
                                callbackHandler,
                            )

                    SensorRegistrationKind.TRIGGER ->
                        sensorManager
                            .requestTriggerSensor(
                                triggerListener,
                                sensor,
                            )
                }
            } catch (
                error: SecurityException,
            ) {
                lifecycle.markRejected()

                callback.set(
                    null,
                )

                return SensorRegistrationStart
                    .PermissionDenied(
                        requiredPermission =
                            sensor
                                .requiredPermission
                                .orEmpty()
                                .ifBlank {
                                    null
                                },
                    )
            } catch (
                error: IllegalArgumentException,
            ) {
                lifecycle.markRejected()

                callback.set(
                    null,
                )

                return SensorRegistrationStart
                    .Rejected(
                        reason =
                            REASON_ILLEGAL_ARGUMENT,
                    )
            }

        if (
            !accepted
        ) {
            lifecycle.markRejected()

            callback.set(
                null,
            )

            return SensorRegistrationStart
                .Rejected(
                    reason =
                        REASON_REGISTRATION_REJECTED,
                )
        }

        /*
         * If stop() raced the synchronous framework call, markAccepted() will
         * fail because STOPPED is terminal. The framework may nevertheless
         * have accepted the listener, so explicitly clean it up here.
         *
         * This also handles a synchronous first callback from registration:
         * the callback can finish the sample and stop the registration before
         * registerListener/requestTriggerSensor returns.
         */
        if (
            !lifecycle.markAccepted()
        ) {
            callback.set(
                null,
            )

            cleanupPlatformRegistration()
        }

        return SensorRegistrationStart
            .Started
    }

    override fun stop() {
        val cleanupRequired =
            lifecycle.stop()

        callback.set(
            null,
        )

        if (
            cleanupRequired
        ) {
            cleanupPlatformRegistration()
        }
    }

    private fun cleanupPlatformRegistration() {
        runCatching {
            when (
                kind
            ) {
                SensorRegistrationKind.LISTENER ->
                    sensorManager
                        .unregisterListener(
                            listener,
                            sensor,
                        )

                SensorRegistrationKind.TRIGGER ->
                    sensorManager
                        .cancelTriggerSensor(
                            triggerListener,
                            sensor,
                        )
            }
        }
    }

    private companion object {
        const val MAX_REPORT_LATENCY_US =
            0

        const val REASON_ALREADY_STARTED =
            "already_started"

        const val REASON_NOT_STARTABLE =
            "registration_not_startable"

        const val REASON_STOPPED_BEFORE_REGISTRATION =
            "stopped_before_registration"

        const val REASON_ILLEGAL_ARGUMENT =
            "illegal_argument"

        const val REASON_REGISTRATION_REJECTED =
            "registration_returned_false"
    }
}
