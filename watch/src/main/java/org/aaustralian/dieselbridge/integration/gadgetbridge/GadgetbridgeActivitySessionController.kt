// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.integration.gadgetbridge

import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.aaustralian.dieselbridge.platform.event.DieselEvent
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.event.SensorObservationSampleEvent
import org.aaustralian.dieselbridge.platform.event.SensorObservationStateEvent
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorBufferPolicy
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationClient
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventBridge
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationEventRegistration
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationManager
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscription
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionRequest
import org.aaustralian.dieselbridge.protocol.GbMessage

/**
 * BLE-session owner for Gadgetbridge realtime activity observation.
 *
 * SensorObservationManager remains the hardware/provider owner. This
 * controller owns only its logical subscriptions, EventBus forwarding and
 * outbound reporting lifecycle.
 *
 * M5.1b reports HR. Step subscriptions may already exist, but cumulative
 * counter delta semantics are deliberately deferred to M5.1c.
 */
class GadgetbridgeActivitySessionController(
    private val manager: SensorObservationManager,
    private val bridge: SensorObservationEventBridge,
    private val eventBus: DieselEventBus,
    private val transport: BangleLineTransport,
    scope: CoroutineScope,
    private val wallClockMs: () -> Long = {
        System.currentTimeMillis()
    },
) : AutoCloseable {

    data class Snapshot(
        val enabled: Boolean = false,
        val heartRateRequested: Boolean = false,
        val stepsRequested: Boolean = false,
        val intervalSeconds: Int = 10,
        val heartRateSubscriptionId: Long? = null,
        val stepSubscriptionId: Long? = null,
        val latestHeartRateBpm: Int? = null,
        val heartRateProviderId: String? = null,
        val lastHeartRateSensorTimestampNs: Long? = null,
        val reportsAttempted: Long = 0L,
        val reportsQueued: Long = 0L,
        val reportsRejected: Long = 0L,
        val lastReportAtMs: Long? = null,
        val lastReportLine: String? = null,
        val lastStopReason: String? = null,
        val lastError: String? = null,
    )

    private class Owned(
        val subscription: SensorSubscription,
        val registration: SensorObservationEventRegistration,
    ) {
        fun release() {
            registration.close()
            subscription.close()
        }
    }

    private val lock =
        Any()

    private val controllerJob =
        SupervisorJob(
            scope.coroutineContext[
                Job
            ],
        )

    private val controllerScope =
        CoroutineScope(
            scope.coroutineContext +
                controllerJob,
        )

    private val mutableState =
        MutableStateFlow(
            Snapshot(),
        )

    val state: StateFlow<Snapshot> =
        mutableState.asStateFlow()

    private var client:
        SensorObservationClient? =
        null

    private var hr:
        Owned? =
        null

    private var steps:
        Owned? =
        null

    private var reportJob:
        Job? =
        null

    private var closed =
        false

    /*
     * DieselEventBus has replay=0. Subscribe immediately, before any later
     * phone request can attach its observation bridge.
     */
    private val eventJob =
        controllerScope.launch(
            start =
                CoroutineStart.UNDISPATCHED,
        ) {
            eventBus
                .events
                .collect {
                    event ->
                    onEvent(
                        event,
                    )
                }
        }

    /**
     * Repeated controls and interval-only changes never resubscribe.
     * The phone interval controls only outbound reporting.
     */
    fun apply(
        control: GbMessage.ActivityControl,
    ): Boolean =
        synchronized(lock) {
            if (closed) {
                return@synchronized false
            }

            if (!control.enabled) {
                disableLocked(
                    reason =
                        "phone_disabled",
                    intervalSeconds =
                        control.intervalSeconds,
                )

                return@synchronized true
            }

            val previous =
                mutableState.value

            val newSession =
                !previous.enabled

            val previousHeartRateId =
                hr
                    ?.subscription
                    ?.id

            try {
                val owner =
                    client
                        ?: manager
                            .openClient(
                                "gadgetbridge-activity",
                            )
                            .also {
                                client =
                                    it
                            }

                if (!control.heartRate) {
                    hr?.release()
                    hr =
                        null
                }

                if (!control.steps) {
                    steps?.release()
                    steps =
                        null
                }

                if (
                    control.heartRate &&
                    hr == null
                ) {
                    hr =
                        open(
                            owner,
                            "heart_rate",
                        )
                }

                if (
                    control.steps &&
                    steps == null
                ) {
                    steps =
                        open(
                            owner,
                            "step_counter",
                        )
                }

                val currentHeartRateId =
                    hr
                        ?.subscription
                        ?.id

                val heartRateWasReopened =
                    currentHeartRateId !=
                        previousHeartRateId

                val base =
                    if (newSession) {
                        Snapshot(
                            intervalSeconds =
                                control.intervalSeconds,
                        )
                    } else {
                        previous
                    }

                mutableState.value =
                    base.copy(
                        enabled =
                            true,
                        heartRateRequested =
                            control.heartRate,
                        stepsRequested =
                            control.steps,
                        intervalSeconds =
                            control.intervalSeconds,
                        heartRateSubscriptionId =
                            currentHeartRateId,
                        stepSubscriptionId =
                            steps
                                ?.subscription
                                ?.id,
                        latestHeartRateBpm =
                            if (
                                control.heartRate &&
                                !heartRateWasReopened
                            ) {
                                base.latestHeartRateBpm
                            } else {
                                null
                            },
                        heartRateProviderId =
                            if (
                                control.heartRate &&
                                !heartRateWasReopened
                            ) {
                                base.heartRateProviderId
                            } else {
                                null
                            },
                        lastHeartRateSensorTimestampNs =
                            if (
                                control.heartRate &&
                                !heartRateWasReopened
                            ) {
                                base.lastHeartRateSensorTimestampNs
                            } else {
                                null
                            },
                        lastStopReason =
                            null,
                        lastError =
                            null,
                    )

                val restartReporting =
                    control.heartRate &&
                        (
                            reportJob?.isActive !=
                                true ||
                                !previous.heartRateRequested ||
                                previous.intervalSeconds !=
                                control.intervalSeconds
                        )

                when {
                    !control.heartRate ->
                        stopReportingLocked()

                    restartReporting ->
                        startReportingLocked(
                            control.intervalSeconds,
                        )
                }

                true
            } catch (error: Exception) {
                stopReportingLocked()
                releaseAllLocked()

                mutableState.value =
                    Snapshot(
                        lastStopReason =
                            "start_failed",
                        lastError =
                            error.javaClass.simpleName +
                                (
                                    error.message
                                        ?.let {
                                            ": $it"
                                        }
                                        ?: ""
                                ),
                    )

                false
            }
        }

    fun disable(
        reason: String,
    ) {
        synchronized(lock) {
            if (!closed) {
                disableLocked(
                    reason =
                        reason,
                )
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                return
            }

            closed =
                true

            disableLocked(
                reason =
                    "controller_closed",
            )

            controllerJob.cancel()
        }
    }

    private fun onEvent(
        event: DieselEvent,
    ) {
        synchronized(lock) {
            if (
                closed ||
                !mutableState.value.enabled
            ) {
                return
            }

            val heartRateId =
                hr
                    ?.subscription
                    ?.id
                    ?: return

            when (event) {
                is SensorObservationStateEvent -> {
                    if (
                        event.subscriptionId !=
                        heartRateId
                    ) {
                        return
                    }

                    event
                        .state
                        .providerId
                        ?.let {
                            providerId ->
                            mutableState.value =
                                mutableState.value.copy(
                                    heartRateProviderId =
                                        providerId,
                                )
                        }
                }

                is SensorObservationSampleEvent -> {
                    if (
                        event.subscriptionId !=
                            heartRateId ||
                        event.logicalId !=
                            "heart_rate"
                    ) {
                        return
                    }

                    val raw =
                        event
                            .sample
                            .reading
                            .values
                            .firstOrNull()
                            ?: return

                    if (
                        !raw.isFinite() ||
                        raw <= 0F
                    ) {
                        return
                    }

                    val bpm =
                        raw
                            .roundToInt()

                    if (
                        bpm <= 0
                    ) {
                        return
                    }

                    mutableState.value =
                        mutableState.value.copy(
                            latestHeartRateBpm =
                                bpm,
                            heartRateProviderId =
                                event
                                    .sample
                                    .reading
                                    .providerId,
                            lastHeartRateSensorTimestampNs =
                                event
                                    .sample
                                    .reading
                                    .timestampNanos,
                        )
                }

                else ->
                    Unit
            }
        }
    }

    private fun startReportingLocked(
        intervalSeconds: Int,
    ) {
        stopReportingLocked()

        reportJob =
            controllerScope.launch {
                val delayMs =
                    intervalSeconds
                        .toLong() *
                        1_000L

                while (isActive) {
                    delay(
                        delayMs,
                    )

                    emitReport()
                }
            }
    }

    private fun stopReportingLocked() {
        reportJob
            ?.cancel()

        reportJob =
            null
    }

    /**
     * NusGattServer.sendLine is a bounded, non-blocking admission call. Keep
     * it under the same lock as disable() so no old-session report can be
     * admitted after disable() returns.
     */
    private fun emitReport() {
        synchronized(lock) {
            if (
                closed ||
                !mutableState.value.enabled ||
                !mutableState.value.heartRateRequested
            ) {
                return
            }

            val current =
                mutableState.value

            val now =
                wallClockMs()

            val line =
                GadgetbridgeActivityCodec
                    .encodeRealtime(
                        timestampMs =
                            now,
                        heartRateBpm =
                            current.latestHeartRateBpm,
                    )

            val queued =
                transport.sendLine(
                    line,
                )

            mutableState.value =
                current.copy(
                    reportsAttempted =
                        current.reportsAttempted +
                            1L,
                    reportsQueued =
                        current.reportsQueued +
                            if (queued) {
                                1L
                            } else {
                                0L
                            },
                    reportsRejected =
                        current.reportsRejected +
                            if (queued) {
                                0L
                            } else {
                                1L
                            },
                    lastReportAtMs =
                        now,
                    lastReportLine =
                        line,
                )
        }
    }

    private fun open(
        owner: SensorObservationClient,
        logicalId: String,
    ): Owned {
        val subscription =
            owner.subscribe(
                SensorSubscriptionRequest(
                    logicalId =
                        logicalId,
                    periodMs =
                        OBSERVATION_PERIOD_MS,
                    bufferPolicy =
                        SensorBufferPolicy.Latest,
                ),
            )

        try {
            return Owned(
                subscription =
                    subscription,
                registration =
                    bridge.attach(
                        subscription,
                        controllerScope,
                    ),
            )
        } catch (error: Exception) {
            subscription.close()
            throw error
        }
    }

    private fun disableLocked(
        reason: String,
        intervalSeconds: Int =
            mutableState.value.intervalSeconds,
    ) {
        val current =
            mutableState.value

        stopReportingLocked()
        releaseAllLocked()

        mutableState.value =
            current.copy(
                enabled =
                    false,
                heartRateRequested =
                    false,
                stepsRequested =
                    false,
                intervalSeconds =
                    intervalSeconds,
                heartRateSubscriptionId =
                    null,
                stepSubscriptionId =
                    null,
                lastStopReason =
                    reason,
            )
    }

    private fun releaseAllLocked() {
        hr?.release()
        hr =
            null

        steps?.release()
        steps =
            null

        client?.close()
        client =
            null
    }

    private companion object {
        const val OBSERVATION_PERIOD_MS =
            1_000L
    }
}
