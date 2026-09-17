// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorBufferPolicy
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationManager
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscription
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionPhase
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionRequest
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionSample
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionState

internal enum class SensorObservationSmokeProfile(
    val wireName: String,
) {
    BASIC("basic"),
    CADENCE("cadence"),
    SCREEN_OFF("screen_off"),
    STEP_COUNTER("step_counter"),
    HEALTH_SERVICES_HR("health_services_hr"),
    SHARING("sharing"),
    LIFECYCLE("lifecycle"),
    ;

    companion object {
        fun fromWireName(value: String): SensorObservationSmokeProfile? =
            values().firstOrNull { it.wireName == value }
    }
}

internal enum class SensorObservationSmokeStatus {
    RUNNING,
    PASSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

internal data class SensorObservationSmokeRoute(
    val routeId: String,
    val wakeUp: Boolean,
    val minDelayUs: Int,
    val reportingMode: Int,
    val powerMilliAmps: Float,
)

internal fun interface SensorObservationSmokeRouteInspector {
    fun inspect(
        logicalId: String,
        requestedPeriodMs: Long,
    ): SensorObservationSmokeRoute?
}

internal data class SensorObservationSmokeSnapshot(
    val runId: String,
    val profile: SensorObservationSmokeProfile,
    val status: SensorObservationSmokeStatus,
    val logicalId: String?,
    val requestedPeriodMs: Long?,
    val providerId: String? = null,
    val acquisitionPeriodMs: Long? = null,
    val providerConfiguredPeriodMs: Long? = null,
    val providerEffectivePeriodMs: Long? = null,
    val sensorManagerRouteId: String? = null,
    val sensorManagerRouteWakeUp: Boolean? = null,
    val sensorManagerRouteMinDelayUs: Int? = null,
    val sensorManagerRouteReportingMode: Int? = null,
    val sensorManagerRoutePowerMilliAmps: Float? = null,
    val sampleCount: Int = 0,
    val firstSequence: Long? = null,
    val lastSequence: Long? = null,
    val firstSensorTimestampNs: Long? = null,
    val lastSensorTimestampNs: Long? = null,
    val nonAdvancingSequenceCount: Int = 0,
    val nonAdvancingTimestampCount: Int = 0,
    val observedMinIntervalMs: Long? = null,
    val observedMedianIntervalMs: Long? = null,
    val observedMaxIntervalMs: Long? = null,
    val providerDroppedTotal: Long = 0L,
    val subscriptionDroppedTotal: Long = 0L,
    val phaseTransitions: List<String> = emptyList(),
    val lastValues: List<Float> = emptyList(),
    val completedCycles: Int = 0,
    val sharingInitialAcquisitionPeriodMs: Long? = null,
    val sharingCombinedAcquisitionPeriodMs: Long? = null,
    val sharingRestoredAcquisitionPeriodMs: Long? = null,
    val detail: String? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
)

/**
 * Service-owned hardware smoke runner for the provider-neutral continuous
 * sensor observation runtime.
 *
 * It deliberately uses SensorObservationManager directly instead of the
 * public Diesel subscription controller. Developer validation can therefore
 * exercise internal cadences such as 20 ms while the public BLE API retains
 * its stricter remote limits.
 */
internal class SensorObservationSmokeRunner(
    private val observationManager: SensorObservationManager,
    scope: CoroutineScope,
    private val routeInspector: SensorObservationSmokeRouteInspector =
        SensorObservationSmokeRouteInspector { _, _ -> null },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) : AutoCloseable {

    private val lock = Any()

    private val runnerJob =
        SupervisorJob(scope.coroutineContext[Job])

    private val runnerScope =
        CoroutineScope(scope.coroutineContext + runnerJob)

    private val mutableState =
        kotlinx.coroutines.flow.MutableStateFlow<SensorObservationSmokeSnapshot?>(null)

    val state: kotlinx.coroutines.flow.StateFlow<SensorObservationSmokeSnapshot?> =
        mutableState

    private var closed = false
    private var nextRunId = 1L
    private var activeRunId: String? = null
    private var activeJob: Job? = null

    fun start(profile: SensorObservationSmokeProfile): String? {
        val runId: String
        val job: Job

        synchronized(lock) {
            check(!closed) { "Sensor observation smoke runner is closed" }

            if (activeJob?.isActive == true) {
                return null
            }

            runId = "obs-${nextRunId++}"

            mutableState.value =
                SensorObservationSmokeSnapshot(
                    runId = runId,
                    profile = profile,
                    status = SensorObservationSmokeStatus.RUNNING,
                    logicalId = primaryLogicalId(profile),
                    requestedPeriodMs = primaryPeriodMs(profile),
                    detail = initialDetail(profile),
                    startedAtMs = wallClockMs(),
                )

            job =
                runnerScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        when (profile) {
                            SensorObservationSmokeProfile.BASIC ->
                                runSingle(runId, "accelerometer", 250L, 12, 15_000L, false)
                            SensorObservationSmokeProfile.CADENCE ->
                                runSingle(runId, "accelerometer", 20L, 12, 15_000L, false)
                            SensorObservationSmokeProfile.SCREEN_OFF ->
                                runSingle(runId, "accelerometer", 250L, 1, 30_000L, true)
                            SensorObservationSmokeProfile.STEP_COUNTER ->
                                runSingle(runId, "step_counter", 1_000L, 1, 30_000L, false)
                            SensorObservationSmokeProfile.HEALTH_SERVICES_HR ->
                                runHealthServicesHeartRate(runId)
                            SensorObservationSmokeProfile.SHARING ->
                                runSharing(runId)
                            SensorObservationSmokeProfile.LIFECYCLE ->
                                runLifecycle(runId)
                        }
                    } catch (cancellation: CancellationException) {
                        finish(runId, SensorObservationSmokeStatus.CANCELLED, "Smoke run cancelled")
                        throw cancellation
                    } catch (error: Throwable) {
                        finish(
                            runId,
                            SensorObservationSmokeStatus.FAILED,
                            "Runner exception: ${error.javaClass.simpleName}" +
                                (error.message?.let { ": $it" } ?: ""),
                        )
                    } finally {
                        synchronized(lock) {
                            if (activeRunId == runId) {
                                activeRunId = null
                                activeJob = null
                            }
                        }
                    }
                }

            activeRunId = runId
            activeJob = job
        }

        job.start()
        return runId
    }

    fun cancel(runId: String): Boolean {
        val job =
            synchronized(lock) {
                if (activeRunId == runId) activeJob else null
            } ?: return false

        job.cancel()
        return true
    }

    fun latest(): SensorObservationSmokeSnapshot? = mutableState.value

    override fun close() {
        val shouldClose =
            synchronized(lock) {
                if (closed) false else {
                    closed = true
                    true
                }
            }

        if (!shouldClose) return

        activeJob?.cancel()
        runnerScope.cancel()
    }

    private suspend fun runSingle(
        runId: String,
        logicalId: String,
        requestedPeriodMs: Long,
        targetSamples: Int,
        maxDurationMs: Long,
        timedCapture: Boolean,
    ) {
        recordRoute(runId, logicalId, requestedPeriodMs)
        val accumulator =
            SampleAccumulator(
                runId = runId,
                trackMonotonicity = true,
            )
        val client = observationManager.openClient("developer-observation-smoke-$runId")

        try {
            val subscription =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId = logicalId,
                        periodMs = requestedPeriodMs,
                        bufferPolicy = SensorBufferPolicy.Bounded(64),
                    ),
                )

            coroutineScope {
                val stateJob =
                    launch {
                        subscription.state.collect {
                            accumulator.recordState(it)
                        }
                    }

                try {
                    if (timedCapture) {
                        withTimeoutOrNull(maxDurationMs) {
                            subscription.samples.collect {
                                accumulator.recordSample(it)
                            }
                        }
                    } else {
                        withTimeoutOrNull(maxDurationMs) {
                            subscription.samples
                                .take(targetSamples)
                                .collect {
                                    accumulator.recordSample(it)
                                }
                        }
                    }
                } finally {
                    subscription.close()
                    accumulator.recordState(subscription.state.value)
                    stateJob.cancel()
                }
            }
        } finally {
            client.close()
        }

        val snapshot = requireNotNull(snapshotFor(runId))

        when {
            !timedCapture && snapshot.sampleCount < targetSamples ->
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "Expected $targetSamples samples; received ${snapshot.sampleCount}. " +
                        terminalStateDetail(snapshot),
                )

            timedCapture && snapshot.sampleCount == 0 ->
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "30 s timed capture produced no samples. ${terminalStateDetail(snapshot)}",
                )

            snapshot.sampleCount >= 2 &&
                (
                    snapshot.nonAdvancingSequenceCount > 0 ||
                        snapshot.nonAdvancingTimestampCount > 0 ||
                        snapshot.firstSequence == snapshot.lastSequence ||
                        snapshot.firstSensorTimestampNs == snapshot.lastSensorTimestampNs
                ) ->
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "Samples were delivered but sequence/timestamp did not advance cleanly: " +
                        "sequenceErrors=${snapshot.nonAdvancingSequenceCount}, " +
                        "timestampErrors=${snapshot.nonAdvancingTimestampCount}",
                )

            snapshot.phaseTransitions.none {
                it == SensorSubscriptionPhase.ACTIVE.name.lowercase()
            } ->
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "Subscription never reached ACTIVE. ${terminalStateDetail(snapshot)}",
                )

            snapshot.phaseTransitions.lastOrNull() !=
                SensorSubscriptionPhase.CLOSED.name.lowercase() ->
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "Subscription did not reach CLOSED",
                )

            timedCapture ->
                finish(
                    runId,
                    SensorObservationSmokeStatus.COMPLETED,
                    "Timed capture complete. Turn the display off during the run; " +
                        "interpret continuity using route wake-up metadata and observed max interval.",
                )

            snapshot.profile == SensorObservationSmokeProfile.CADENCE &&
                snapshot.providerId == SENSOR_MANAGER_PROVIDER_ID &&
                !cadenceConfigurationMatches(snapshot) ->
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "SensorManager configured cadence does not match requested/minDelay policy",
                )

            else ->
                finish(
                    runId,
                    SensorObservationSmokeStatus.PASSED,
                    "Observation produced real samples and closed cleanly",
                )
        }
    }


    private suspend fun runHealthServicesHeartRate(
        runId: String,
    ) {
        val logicalId =
            "heart_rate"

        val requestedPeriodMs =
            1_000L

        val accumulator =
            SampleAccumulator(
                runId = runId,
                trackMonotonicity = true,
            )

        val client =
            observationManager.openClient(
                "developer-observation-health-services-hr-$runId",
            )

        var terminalStatus =
            SensorObservationSmokeStatus.FAILED

        var terminalDetail =
            "Health Services HR smoke did not reach a terminal decision"

        try {
            val subscription =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId = logicalId,
                        periodMs = requestedPeriodMs,
                        bufferPolicy = SensorBufferPolicy.Bounded(16),
                    ),
                )

            coroutineScope {
                val firstSample =
                    CompletableDeferred<
                        SensorSubscriptionSample
                    >()

                val stateJob =
                    launch {
                        subscription.state.collect {
                            accumulator.recordState(it)
                        }
                    }

                val sampleJob =
                    launch {
                        subscription.samples.collect { sample ->
                            accumulator.recordSample(sample)
                            firstSample.complete(sample)
                        }
                    }

                try {
                    val active =
                        withTimeoutOrNull(
                            HEALTH_SERVICES_ACTIVE_TIMEOUT_MS,
                        ) {
                            subscription.state.first {
                                it.phase ==
                                    SensorSubscriptionPhase.ACTIVE
                            }
                        }

                    if (active == null) {
                        accumulator.recordState(
                            subscription.state.value,
                        )

                        terminalStatus =
                            SensorObservationSmokeStatus.FAILED

                        terminalDetail =
                            "Health Services HR never reached ACTIVE. " +
                            terminalStateDetail(
                                requireNotNull(
                                    snapshotFor(runId),
                                ),
                            )
                    } else {
                        accumulator.recordState(active)

                        if (
                            active.providerId !=
                            HEALTH_SERVICES_PROVIDER_ID
                        ) {
                            terminalStatus =
                                SensorObservationSmokeStatus.FAILED

                            terminalDetail =
                                "Expected provider " +
                                "$HEALTH_SERVICES_PROVIDER_ID; active provider was " +
                                "${active.providerId ?: "none"}"
                        } else {
                            val sample =
                                withTimeoutOrNull(
                                    HEALTH_SERVICES_SAMPLE_WINDOW_MS,
                                ) {
                                    firstSample.await()
                                }

                            if (sample == null) {
                                val currentState =
                                    subscription.state.value

                                if (
                                    currentState.phase !=
                                    SensorSubscriptionPhase.ACTIVE ||
                                    currentState.providerId !=
                                    HEALTH_SERVICES_PROVIDER_ID
                                ) {
                                    terminalStatus =
                                        SensorObservationSmokeStatus.FAILED

                                    terminalDetail =
                                        "Health Services HR left ACTIVE before a sample: " +
                                        "phase=${currentState.phase.name.lowercase()}, " +
                                        "provider=${currentState.providerId ?: "none"}, " +
                                        "reason=${currentState.reason ?: "none"}"
                                } else {
                                    terminalStatus =
                                        SensorObservationSmokeStatus.COMPLETED

                                    terminalDetail =
                                        "Health Services HR remained ACTIVE but emitted no " +
                                        "heart-rate sample during the bounded passive window; " +
                                        "registration is validated, sample delivery is not"
                                }
                            } else if (
                                sample.reading.providerId !=
                                HEALTH_SERVICES_PROVIDER_ID
                            ) {
                                terminalStatus =
                                    SensorObservationSmokeStatus.FAILED

                                terminalDetail =
                                    "Heart-rate sample came from " +
                                    "${sample.reading.providerId}, not " +
                                    HEALTH_SERVICES_PROVIDER_ID
                            } else {
                                terminalStatus =
                                    SensorObservationSmokeStatus.PASSED

                                terminalDetail =
                                    "Health Services passive HR reached ACTIVE and " +
                                    "delivered a real heart-rate sample"
                            }
                        }
                    }
                } finally {
                    subscription.close()
                    accumulator.recordState(
                        subscription.state.value,
                    )
                    sampleJob.cancel()
                    stateJob.cancel()
                }
            }
        } finally {
            client.close()
        }

        val snapshot =
            requireNotNull(
                snapshotFor(runId),
            )

        if (
            snapshot.phaseTransitions.lastOrNull() !=
            SensorSubscriptionPhase.CLOSED.name.lowercase()
        ) {
            finish(
                runId,
                SensorObservationSmokeStatus.FAILED,
                "Health Services HR subscription did not reach CLOSED",
            )
            return
        }

        finish(
            runId,
            terminalStatus,
            terminalDetail,
        )
    }

    private suspend fun runSharing(runId: String) {
        val logicalId = "accelerometer"
        recordRoute(runId, logicalId, 250L)
        val accumulator =
            SampleAccumulator(
                runId = runId,
                trackMonotonicity = false,
            )
        val client = observationManager.openClient("developer-observation-sharing-$runId")

        var slowSubscription: SensorSubscription? = null
        var fastSubscription: SensorSubscription? = null

        try {
            val slow =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId = logicalId,
                        periodMs = 1_000L,
                        bufferPolicy = SensorBufferPolicy.Bounded(16),
                    ),
                )

            slowSubscription = slow

            val initial =
                withTimeoutOrNull(7_500L) {
                    slow.state.first {
                        it.phase == SensorSubscriptionPhase.ACTIVE &&
                            it.acquisitionPeriodMs == 1_000L
                    }
                }

            if (initial == null) {
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "Slow sharing subscription did not become ACTIVE at 1000 ms",
                )
                return
            }

            accumulator.recordState(initial)
            updateSnapshot(runId) {
                it.copy(
                    sharingInitialAcquisitionPeriodMs =
                        initial.acquisitionPeriodMs,
                )
            }

            val fast =
                client.subscribe(
                    SensorSubscriptionRequest(
                        logicalId = logicalId,
                        periodMs = 250L,
                        bufferPolicy = SensorBufferPolicy.Bounded(16),
                    ),
                )

            fastSubscription = fast

            val combined =
                withTimeoutOrNull(7_500L) {
                    slow.state.first {
                        it.phase == SensorSubscriptionPhase.ACTIVE &&
                            it.acquisitionPeriodMs == 250L
                    }
                }

            if (combined == null) {
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "Shared runtime did not adopt fastest 250 ms request",
                )
                return
            }

            accumulator.recordState(combined)
            updateSnapshot(runId) {
                it.copy(
                    sharingCombinedAcquisitionPeriodMs =
                        combined.acquisitionPeriodMs,
                )
            }

            var fastSamples = 0

            withTimeoutOrNull(10_000L) {
                fast.samples
                    .take(4)
                    .collect {
                        fastSamples++
                        accumulator.recordSample(it)
                    }
            }

            if (fastSamples < 4) {
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "Fast sharing consumer received only $fastSamples / 4 samples",
                )
                return
            }

            fast.close()
            fastSubscription = null

            val restored =
                withTimeoutOrNull(7_500L) {
                    slow.state.first {
                        it.phase == SensorSubscriptionPhase.ACTIVE &&
                            it.acquisitionPeriodMs == 1_000L
                    }
                }

            if (restored == null) {
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "Shared runtime did not restore 1000 ms after fast consumer closed",
                )
                return
            }

            accumulator.recordState(restored)
            updateSnapshot(runId) {
                it.copy(
                    sharingRestoredAcquisitionPeriodMs =
                        restored.acquisitionPeriodMs,
                )
            }

            var slowSamples = 0

            withTimeoutOrNull(7_500L) {
                slow.samples
                    .take(2)
                    .collect {
                        slowSamples++
                        accumulator.recordSample(it)
                    }
            }

            slow.close()
            slowSubscription = null
            accumulator.recordState(slow.state.value)

            val final = requireNotNull(snapshotFor(runId))

            if (
                final.sharingInitialAcquisitionPeriodMs == 1_000L &&
                final.sharingCombinedAcquisitionPeriodMs == 250L &&
                final.sharingRestoredAcquisitionPeriodMs == 1_000L &&
                fastSamples == 4 &&
                slowSamples == 2 &&
                slow.state.value.phase == SensorSubscriptionPhase.CLOSED
            ) {
                finish(
                    runId,
                    SensorObservationSmokeStatus.PASSED,
                    "One shared runtime followed 1000 -> 250 -> 1000 ms consumer demand",
                )
            } else {
                finish(
                    runId,
                    SensorObservationSmokeStatus.FAILED,
                    "Sharing checkpoints were incomplete",
                )
            }
        } finally {
            fastSubscription?.close()
            slowSubscription?.close()
            client.close()
        }
    }

    private suspend fun runLifecycle(runId: String) {
        val logicalId = "accelerometer"
        val requestedPeriodMs = 250L
        val targetCycles = 5
        recordRoute(runId, logicalId, requestedPeriodMs)
        val accumulator =
            SampleAccumulator(
                runId = runId,
                trackMonotonicity = false,
            )
        val client = observationManager.openClient("developer-observation-lifecycle-$runId")

        try {
            for (cycle in 1..targetCycles) {
                val subscription =
                    client.subscribe(
                        SensorSubscriptionRequest(
                            logicalId = logicalId,
                            periodMs = requestedPeriodMs,
                            bufferPolicy = SensorBufferPolicy.Bounded(8),
                        ),
                    )

                val active =
                    withTimeoutOrNull(7_500L) {
                        subscription.state.first {
                            it.phase == SensorSubscriptionPhase.ACTIVE
                        }
                    }

                if (active == null) {
                    subscription.close()
                    finish(
                        runId,
                        SensorObservationSmokeStatus.FAILED,
                        "Lifecycle cycle $cycle never reached ACTIVE",
                    )
                    return
                }

                accumulator.recordState(active)
                var cycleSamples = 0

                withTimeoutOrNull(7_500L) {
                    subscription.samples.take(2).collect {
                        cycleSamples++
                        accumulator.recordSample(it)
                    }
                }

                subscription.close()
                accumulator.recordState(subscription.state.value)

                if (
                    cycleSamples < 2 ||
                    subscription.state.value.phase != SensorSubscriptionPhase.CLOSED
                ) {
                    finish(
                        runId,
                        SensorObservationSmokeStatus.FAILED,
                        "Lifecycle cycle $cycle did not sample and close cleanly",
                    )
                    return
                }

                updateSnapshot(runId) { it.copy(completedCycles = cycle) }
            }

            finish(
                runId,
                SensorObservationSmokeStatus.PASSED,
                "$targetCycles repeated observation lifecycles completed cleanly",
            )
        } finally {
            client.close()
        }
    }

    private fun recordRoute(
        runId: String,
        logicalId: String,
        requestedPeriodMs: Long,
    ) {
        val route =
            runCatching {
                routeInspector.inspect(logicalId, requestedPeriodMs)
            }.getOrNull() ?: return

        updateSnapshot(runId) {
            it.copy(
                sensorManagerRouteId = route.routeId,
                sensorManagerRouteWakeUp = route.wakeUp,
                sensorManagerRouteMinDelayUs = route.minDelayUs,
                sensorManagerRouteReportingMode = route.reportingMode,
                sensorManagerRoutePowerMilliAmps = route.powerMilliAmps,
            )
        }
    }

    private fun cadenceConfigurationMatches(
        snapshot: SensorObservationSmokeSnapshot,
    ): Boolean {
        val minDelayUs = snapshot.sensorManagerRouteMinDelayUs ?: return true
        val configuredMs = snapshot.providerConfiguredPeriodMs ?: return false
        val requestedMs = snapshot.requestedPeriodMs ?: return false

        val requestedUs =
            requestedMs
                .coerceAtMost(Int.MAX_VALUE / 1_000L)
                .times(1_000L)

        val registrationUs =
            maxOf(requestedUs, minDelayUs.coerceAtLeast(0).toLong())

        val expectedConfiguredMs = (registrationUs + 999L) / 1_000L

        return configuredMs == expectedConfiguredMs
    }

    private fun finish(
        runId: String,
        status: SensorObservationSmokeStatus,
        detail: String,
    ) {
        updateSnapshot(runId) {
            it.copy(
                status = status,
                detail = detail,
                finishedAtMs = wallClockMs(),
            )
        }
    }

    private fun terminalStateDetail(snapshot: SensorObservationSmokeSnapshot): String =
        "phases=${snapshot.phaseTransitions.joinToString("->")}, " +
            "provider=${snapshot.providerId ?: "none"}"

    private fun snapshotFor(runId: String): SensorObservationSmokeSnapshot? =
        mutableState.value?.takeIf { it.runId == runId }

    private fun updateSnapshot(
        runId: String,
        transform: (SensorObservationSmokeSnapshot) -> SensorObservationSmokeSnapshot,
    ) {
        synchronized(lock) {
            val current = mutableState.value ?: return
            if (current.runId != runId) return
            mutableState.value = transform(current)
        }
    }

    private inner class SampleAccumulator(
        private val runId: String,
        private val trackMonotonicity: Boolean,
    ) {
        private val timestamps = mutableListOf<Long>()
        private var previousSequence: Long? = null
        private var previousTimestampNs: Long? = null

        fun recordState(state: SensorSubscriptionState) {
            updateSnapshot(runId) { current ->
                val phase = state.phase.name.lowercase()
                val transitions =
                    if (current.phaseTransitions.lastOrNull() == phase) {
                        current.phaseTransitions
                    } else {
                        (current.phaseTransitions + phase).takeLast(MAX_PHASE_TRANSITIONS)
                    }

                current.copy(
                    providerId = state.providerId ?: current.providerId,
                    acquisitionPeriodMs =
                        state.acquisitionPeriodMs ?: current.acquisitionPeriodMs,
                    providerConfiguredPeriodMs =
                        state.providerConfiguredPeriodMs ?: current.providerConfiguredPeriodMs,
                    providerEffectivePeriodMs =
                        state.providerEffectivePeriodMs ?: current.providerEffectivePeriodMs,
                    providerDroppedTotal =
                        maxOf(current.providerDroppedTotal, state.sourceDroppedTotal),
                    subscriptionDroppedTotal =
                        maxOf(
                            current.subscriptionDroppedTotal,
                            state.subscriptionDroppedTotal,
                        ),
                    phaseTransitions = transitions,
                )
            }
        }

        fun recordSample(sample: SensorSubscriptionSample) {
            val timestamp = sample.reading.timestampNanos

            if (timestamp > 0L) {
                timestamps += timestamp
                if (timestamps.size > MAX_RECORDED_TIMESTAMPS) {
                    timestamps.removeAt(0)
                }
            }

            val intervalsMs =
                timestamps
                    .zipWithNext { first, second -> second - first }
                    .filter { it > 0L }
                    .map { (it + 500_000L) / 1_000_000L }

            val sortedIntervals = intervalsMs.sorted()
            val median =
                if (sortedIntervals.isEmpty()) null
                else sortedIntervals[sortedIntervals.size / 2]

            val previousSequenceValue = previousSequence
            val previousTimestampValue = previousTimestampNs
            val sequenceDidNotAdvance =
                trackMonotonicity &&
                    previousSequenceValue != null &&
                    sample.sequence <= previousSequenceValue
            val timestampDidNotAdvance =
                trackMonotonicity &&
                    previousTimestampValue != null &&
                    timestamp <= previousTimestampValue

            if (trackMonotonicity) {
                previousSequence = sample.sequence
                previousTimestampNs = timestamp
            }

            updateSnapshot(runId) { current ->
                current.copy(
                    providerId = sample.reading.providerId,
                    sampleCount = current.sampleCount + 1,
                    firstSequence =
                        if (trackMonotonicity) {
                            current.firstSequence ?: sample.sequence
                        } else {
                            current.firstSequence
                        },
                    lastSequence =
                        if (trackMonotonicity) sample.sequence else current.lastSequence,
                    firstSensorTimestampNs = current.firstSensorTimestampNs ?: timestamp,
                    lastSensorTimestampNs = timestamp,
                    nonAdvancingSequenceCount =
                        current.nonAdvancingSequenceCount +
                            if (sequenceDidNotAdvance) 1 else 0,
                    nonAdvancingTimestampCount =
                        current.nonAdvancingTimestampCount +
                            if (timestampDidNotAdvance) 1 else 0,
                    observedMinIntervalMs = intervalsMs.minOrNull(),
                    observedMedianIntervalMs = median,
                    observedMaxIntervalMs = intervalsMs.maxOrNull(),
                    providerDroppedTotal =
                        maxOf(current.providerDroppedTotal, sample.sourceDroppedTotal),
                    subscriptionDroppedTotal =
                        maxOf(current.subscriptionDroppedTotal, sample.droppedTotal),
                    lastValues = sample.reading.values.take(4),
                )
            }
        }
    }

    private fun primaryLogicalId(profile: SensorObservationSmokeProfile): String =
        when (profile) {
            SensorObservationSmokeProfile.STEP_COUNTER -> "step_counter"
            SensorObservationSmokeProfile.HEALTH_SERVICES_HR -> "heart_rate"
            else -> "accelerometer"
        }

    private fun primaryPeriodMs(profile: SensorObservationSmokeProfile): Long =
        when (profile) {
            SensorObservationSmokeProfile.CADENCE -> 20L
            SensorObservationSmokeProfile.STEP_COUNTER -> 1_000L
            SensorObservationSmokeProfile.HEALTH_SERVICES_HR -> 1_000L
            SensorObservationSmokeProfile.SHARING -> 250L
            else -> 250L
        }

    private fun initialDetail(profile: SensorObservationSmokeProfile): String =
        when (profile) {
            SensorObservationSmokeProfile.SCREEN_OFF ->
                "30 s timed capture: turn the watch display off after starting"
            SensorObservationSmokeProfile.STEP_COUNTER ->
                "Walk while the test is running; TYPE_STEP_COUNTER is on-change/cumulative"
            SensorObservationSmokeProfile.HEALTH_SERVICES_HR ->
                "Require wear.health_services; wait for one passive HR sample"
            SensorObservationSmokeProfile.SHARING ->
                "Validate one shared runtime across 1000 ms and 250 ms consumers"
            SensorObservationSmokeProfile.LIFECYCLE ->
                "Run five subscribe/sample/close cycles"
            SensorObservationSmokeProfile.CADENCE ->
                "Request 20 ms and verify SensorManager physical minDelay clamping"
            SensorObservationSmokeProfile.BASIC ->
                "Collect 12 accelerometer samples and close cleanly"
        }

    private companion object {
        const val SENSOR_MANAGER_PROVIDER_ID =
            "android.sensor_manager"

        const val HEALTH_SERVICES_PROVIDER_ID =
            "wear.health_services"

        const val HEALTH_SERVICES_ACTIVE_TIMEOUT_MS =
            15_000L

        const val HEALTH_SERVICES_SAMPLE_WINDOW_MS =
            90_000L

        const val MAX_PHASE_TRANSITIONS = 16
        const val MAX_RECORDED_TIMESTAMPS = 256
    }
}
