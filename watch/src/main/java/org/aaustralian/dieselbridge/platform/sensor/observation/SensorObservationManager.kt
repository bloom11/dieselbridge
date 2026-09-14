// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor.observation

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.sensor.SensorReading

/**
 * Shared process-local observation runtime.
 *
 * Multiple consumers of the same logical sensor share exactly one active
 * provider acquisition. The fastest consumer request determines provider
 * acquisition cadence; each consumer is independently rate-limited and owns a
 * finite sample queue.
 *
 * Provider selection remains entirely delegated to CapabilityRegistry.
 */
class SensorObservationManager(
    private val registry: CapabilityRegistry,
    scope: CoroutineScope,
    private val limits:
        SensorObservationLimits =
        SensorObservationLimits(),
    private val monotonicMs:
        () -> Long = {
            System.nanoTime() /
                1_000_000L
        },
    private val providerRetryDelayMs:
        Long = 1_000L,
) : AutoCloseable {

    init {
        require(providerRetryDelayMs > 0L)
    }

    private val lock =
        Any()

    private val managerJob =
        SupervisorJob(
            scope.coroutineContext[Job],
        )

    private val managerScope =
        CoroutineScope(
            scope.coroutineContext +
                managerJob,
        )

    private var closed =
        false

    private var nextClientId =
        1L

    private var nextSubscriptionId =
        1L

    private data class ClientRecord(
        val label: String,
        val subscriptionIds:
            MutableSet<Long> =
            linkedSetOf(),
    )

    private val clients =
        linkedMapOf<
            Long,
            ClientRecord,
        >()

    private val subscriptions =
        linkedMapOf<
            Long,
            ManagedSubscription,
        >()

    private val runtimes =
        linkedMapOf<
            String,
            SensorRuntime,
        >()

    fun openClient(
        label: String,
    ): SensorObservationClient {
        require(label.isNotBlank()) {
            "Client label must not be blank"
        }

        val clientId =
            synchronized(lock) {
                check(!closed) {
                    "Sensor observation manager is closed"
                }

                check(
                    clients.size <
                        limits.maxClients,
                ) {
                    "Sensor observation client limit reached"
                }

                val id =
                    nextClientId++

                clients[id] =
                    ClientRecord(
                        label = label,
                    )

                id
            }

        return SensorObservationClient(
            manager = this,
            clientId = clientId,
            label = label,
        )
    }

    internal fun subscribe(
        clientId: Long,
        request:
            SensorSubscriptionRequest,
    ): SensorSubscription {

        SensorObservationCapabilityId
            .forLogical(
                request.logicalId,
            )

        require(
            request.periodMs in
                limits.minPeriodMs..
                limits.maxPeriodMs,
        ) {
            "Requested observation period out of bounds"
        }

        val bufferCapacity =
            when (
                val policy =
                    request.bufferPolicy
            ) {
                SensorBufferPolicy.Latest ->
                    1

                is SensorBufferPolicy.Bounded -> {
                    require(
                        policy.capacity <=
                            limits.maxBufferCapacity,
                    ) {
                        "Requested observation buffer too large"
                    }

                    policy.capacity
                }
            }

        lateinit var runtime:
            SensorRuntime

        lateinit var subscription:
            ManagedSubscription

        var effectivePeriodMs =
            request.periodMs

        var startRuntime =
            false

        synchronized(lock) {
            check(!closed) {
                "Sensor observation manager is closed"
            }

            val client =
                clients[clientId]
                    ?: error(
                        "Unknown or closed sensor observation client",
                    )

            check(
                subscriptions.size <
                    limits.maxSubscriptions,
            ) {
                "Global sensor observation subscription limit reached"
            }

            check(
                client.subscriptionIds.size <
                    limits.maxSubscriptionsPerClient,
            ) {
                "Per-client sensor observation subscription limit reached"
            }

            val existingForSensor =
                subscriptions
                    .values
                    .count {
                        it.logicalId ==
                            request.logicalId
                    }

            check(
                existingForSensor <
                    limits.maxSubscriptionsPerSensor,
            ) {
                "Per-sensor observation subscription limit reached"
            }

            runtime =
                runtimes[
                    request.logicalId
                ]
                    ?: SensorRuntime(
                        logicalId =
                            request.logicalId,
                    ).also {
                        runtimes[
                            request.logicalId
                        ] = it

                        startRuntime =
                            true
                    }

            val subscriptionId =
                nextSubscriptionId++

            subscription =
                ManagedSubscription(
                    id =
                        subscriptionId,
                    clientId =
                        clientId,
                    request =
                        request,
                    bufferCapacity =
                        bufferCapacity,
                    initialRuntimeState =
                        runtime.currentState,
                    initialSourceDroppedTotal =
                        runtime.sourceDroppedTotal,
                    onClose =
                        ::closeSubscription,
                )

            subscriptions[
                subscriptionId
            ] = subscription

            client.subscriptionIds +=
                subscriptionId

            effectivePeriodMs =
                effectivePeriodLocked(
                    request.logicalId,
                )
        }

        if (
            startRuntime
        ) {
            runtime.start(
                effectivePeriodMs,
            )
        } else {
            runtime.updatePeriod(
                effectivePeriodMs,
            )
        }

        return subscription
    }

    internal fun closeClient(
        clientId: Long,
    ) {
        val subscriptionIds =
            synchronized(lock) {
                clients
                    .remove(
                        clientId,
                    )
                    ?.subscriptionIds
                    ?.toList()
                    .orEmpty()
            }

        subscriptionIds.forEach(
            ::closeSubscription,
        )
    }

    private fun closeSubscription(
        subscriptionId: Long,
    ) {
        var runtime:
            SensorRuntime? =
            null

        var newPeriodMs:
            Long? =
            null

        val subscription =
            synchronized(lock) {
                val removed =
                    subscriptions
                        .remove(
                            subscriptionId,
                        )
                        ?: return

                clients[
                    removed.clientId
                ]
                    ?.subscriptionIds
                    ?.remove(
                        subscriptionId,
                    )

                runtime =
                    runtimes[
                        removed.logicalId
                    ]

                val hasRemaining =
                    subscriptions
                        .values
                        .any {
                            it.logicalId ==
                                removed.logicalId
                        }

                if (
                    hasRemaining
                ) {
                    newPeriodMs =
                        effectivePeriodLocked(
                            removed.logicalId,
                        )
                } else {
                    runtimes.remove(
                        removed.logicalId,
                    )
                }

                removed
            }

        subscription
            .closeFromManager()

        if (
            newPeriodMs == null
        ) {
            runtime?.stop()
        } else {
            runtime?.updatePeriod(
                requireNotNull(
                    newPeriodMs,
                ),
            )
        }
    }

    private fun effectivePeriodLocked(
        logicalId: String,
    ): Long =
        subscriptions
            .values
            .asSequence()
            .filter {
                it.logicalId ==
                    logicalId
            }
            .minOf {
                it.request.periodMs
            }

    private fun subscriptionsFor(
        logicalId: String,
    ): List<ManagedSubscription> =
        synchronized(lock) {
            subscriptions
                .values
                .filter {
                    it.logicalId ==
                        logicalId
                }
        }

    private fun publishRuntimeState(
        logicalId: String,
        state: RuntimeState,
    ) {
        subscriptionsFor(
            logicalId,
        ).forEach {
            it.updateRuntimeState(
                state,
            )
        }
    }

    private fun dispatchSample(
        logicalId: String,
        reading: SensorReading,
        sourceDroppedTotal: Long,
    ) {
        val now =
            monotonicMs()

        subscriptionsFor(
            logicalId,
        ).forEach {
            it.offer(
                reading = reading,
                sourceDroppedTotal =
                    sourceDroppedTotal,
                nowMs = now,
            )
        }
    }

    private fun resetDeliveryCadence(
        logicalId: String,
    ) {
        subscriptionsFor(
            logicalId,
        ).forEach {
            it.resetDeliveryCadence()
        }
    }

    override fun close() {
        val clientIds =
            synchronized(lock) {
                if (
                    closed
                ) {
                    return
                }

                closed =
                    true

                clients
                    .keys
                    .toList()
            }

        clientIds.forEach(
            ::closeClient,
        )

        managerJob.cancel()
    }

    private data class RuntimeState(
        val phase:
            SensorSubscriptionPhase =
            SensorSubscriptionPhase
                .WAITING_FOR_PROVIDER,
        val providerId:
            String? =
            null,
        val acquisitionPeriodMs:
            Long? =
            null,
        val providerEffectivePeriodMs:
            Long? =
            null,
        val reason:
            String? =
            null,
    )

    private inner class SensorRuntime(
        private val logicalId:
            String,
    ) {
        private val capabilityId =
            SensorObservationCapabilityId
                .forLogical(
                    logicalId,
                )
                .value

        private val acquisitionPeriod =
            MutableStateFlow<Long?>(
                null,
            )

        @Volatile
        var currentState =
            RuntimeState()
            private set

        /**
         * Monotonic over the lifetime of this shared logical-sensor runtime,
         * including provider retries/failover.
         */
        @Volatile
        var sourceDroppedTotal =
            0L
            private set

        /*
         * Provider Sample.sourceDroppedTotal is session-local. This tracks the
         * last value in the currently collected provider session.
         */
        private var lastProviderSourceDroppedTotal =
            0L

        private var started =
            false

        private var job:
            Job? =
            null

        private var lastProviderId:
            String? =
            null

        fun start(
            periodMs: Long,
        ) {
            if (
                started
            ) {
                updatePeriod(
                    periodMs,
                )
                return
            }

            started =
                true

            updatePeriod(
                periodMs,
            )

            job =
                managerScope.launch {
                    runRuntime()
                }
        }

        fun updatePeriod(
            periodMs: Long,
        ) {
            if (
                acquisitionPeriod.value ==
                    periodMs
            ) {
                return
            }

            setState(
                currentState.copy(
                    acquisitionPeriodMs =
                        periodMs,
                ),
            )

            acquisitionPeriod.value =
                periodMs
        }

        fun stop() {
            job?.cancel()
            job =
                null
        }

        private suspend fun runRuntime() {
            combine(
                registry.observeActive(
                    capabilityId,
                ),
                acquisitionPeriod
                    .filterNotNull(),
            ) { selection, periodMs ->
                selection to
                    periodMs
            }
                .distinctUntilChanged {
                        old,
                        new,
                    ->
                    old.first
                        ?.providerId ==
                        new.first
                            ?.providerId &&
                        old.first
                            ?.capability ===
                        new.first
                            ?.capability &&
                        old.second ==
                        new.second
                }
                .collectLatest {
                        pair ->

                    val selection =
                        pair.first

                    val periodMs =
                        pair.second

                    if (
                        selection == null
                    ) {
                        lastProviderId =
                            null

                        setState(
                            RuntimeState(
                                phase =
                                    SensorSubscriptionPhase
                                        .WAITING_FOR_PROVIDER,
                                acquisitionPeriodMs =
                                    periodMs,
                            ),
                        )

                        return@collectLatest
                    }

                    val capability =
                        selection.capability
                            as?
                            SensorObservationCapability

                    if (
                        capability == null
                    ) {
                        lastProviderId =
                            null

                        setState(
                            RuntimeState(
                                phase =
                                    SensorSubscriptionPhase
                                        .DEGRADED,
                                providerId =
                                    selection.providerId,
                                acquisitionPeriodMs =
                                    periodMs,
                                reason =
                                    "selected_capability_not_observable",
                            ),
                        )

                        return@collectLatest
                    }

                    if (
                        lastProviderId !=
                            selection.providerId
                    ) {
                        resetDeliveryCadence(
                            logicalId,
                        )

                        lastProviderId =
                            selection.providerId
                    }

                    observeSelectedProvider(
                        capability =
                            capability,
                        providerId =
                            selection.providerId,
                        periodMs =
                            periodMs,
                    )
                }
        }

        private suspend fun observeSelectedProvider(
            capability:
                SensorObservationCapability,
            providerId: String,
            periodMs: Long,
        ) {
            setState(
                RuntimeState(
                    phase =
                        SensorSubscriptionPhase
                            .STARTING,
                    providerId =
                        providerId,
                    acquisitionPeriodMs =
                        periodMs,
                ),
            )

            while (
                currentCoroutineContext()
                    .isActive
            ) {
                /*
                 * Every capability.observe() collection is a new provider
                 * session and therefore starts a fresh provider-local counter.
                 */
                lastProviderSourceDroppedTotal =
                    0L

                try {
                    capability
                        .observe(
                            SensorObservationOptions(
                                preferredSamplePeriodMs =
                                    periodMs,
                            ),
                        )
                        .collect { update ->
                            handleProviderUpdate(
                                update =
                                    update,
                                providerId =
                                    providerId,
                                periodMs =
                                    periodMs,
                            )
                        }

                    setState(
                        RuntimeState(
                            phase =
                                SensorSubscriptionPhase
                                    .DEGRADED,
                            providerId =
                                providerId,
                            acquisitionPeriodMs =
                                periodMs,
                            reason =
                                "provider_stream_completed",
                        ),
                    )
                } catch (
                    cancellation:
                        CancellationException,
                ) {
                    throw cancellation
                } catch (
                    error: Throwable,
                ) {
                    setState(
                        RuntimeState(
                            phase =
                                SensorSubscriptionPhase
                                    .DEGRADED,
                            providerId =
                                providerId,
                            acquisitionPeriodMs =
                                periodMs,
                            reason =
                                "provider_exception:" +
                                    error.javaClass
                                        .simpleName,
                        ),
                    )
                }

                delay(
                    providerRetryDelayMs,
                )

                setState(
                    RuntimeState(
                        phase =
                            SensorSubscriptionPhase
                                .STARTING,
                        providerId =
                            providerId,
                        acquisitionPeriodMs =
                            periodMs,
                    ),
                )
            }
        }

        private fun handleProviderUpdate(
            update:
                SensorObservationUpdate,
            providerId: String,
            periodMs: Long,
        ) {
            when (
                update
            ) {
                is SensorObservationUpdate.Started -> {
                    setState(
                        RuntimeState(
                            phase =
                                SensorSubscriptionPhase
                                    .ACTIVE,
                            providerId =
                                providerId,
                            acquisitionPeriodMs =
                                periodMs,
                            providerEffectivePeriodMs =
                                update
                                    .effectiveSamplePeriodMs,
                        ),
                    )
                }

                is SensorObservationUpdate.Sample -> {
                    val expectedCapability =
                        "sensor.$logicalId"

                    if (
                        update.reading
                            .capabilityId
                            .value !=
                        expectedCapability
                    ) {
                        setState(
                            RuntimeState(
                                phase =
                                    SensorSubscriptionPhase
                                        .DEGRADED,
                                providerId =
                                    providerId,
                                acquisitionPeriodMs =
                                    periodMs,
                                reason =
                                    "provider_sample_capability_mismatch",
                            ),
                        )

                        return
                    }

                    if (
                        currentState.phase !=
                            SensorSubscriptionPhase
                                .ACTIVE
                    ) {
                        setState(
                            RuntimeState(
                                phase =
                                    SensorSubscriptionPhase
                                        .ACTIVE,
                                providerId =
                                    providerId,
                                acquisitionPeriodMs =
                                    periodMs,
                            ),
                        )
                    }

                    dispatchSample(
                        logicalId =
                            logicalId,
                        reading =
                            update.reading,
                        sourceDroppedTotal =
                            recordProviderSourceDroppedTotal(
                                update.sourceDroppedTotal,
                            ),
                    )
                }

                is SensorObservationUpdate.PermissionDenied -> {
                    val permission =
                        update.requiredPermission
                            ?.takeIf {
                                it.isNotBlank()
                            }

                    setState(
                        RuntimeState(
                            phase =
                                SensorSubscriptionPhase
                                    .DEGRADED,
                            providerId =
                                providerId,
                            acquisitionPeriodMs =
                                periodMs,
                            reason =
                                if (
                                    permission == null
                                ) {
                                    "permission_denied"
                                } else {
                                    "permission_denied:$permission"
                                },
                        ),
                    )
                }

                is SensorObservationUpdate.Unavailable -> {
                    setState(
                        RuntimeState(
                            phase =
                                SensorSubscriptionPhase
                                    .DEGRADED,
                            providerId =
                                providerId,
                            acquisitionPeriodMs =
                                periodMs,
                            reason =
                                update.reason
                                    ?: "provider_unavailable",
                        ),
                    )
                }

                is SensorObservationUpdate.RegistrationRejected -> {
                    setState(
                        RuntimeState(
                            phase =
                                SensorSubscriptionPhase
                                    .DEGRADED,
                            providerId =
                                providerId,
                            acquisitionPeriodMs =
                                periodMs,
                            reason =
                                update.reason
                                    ?: "registration_rejected",
                        ),
                    )
                }
            }
        }

        private fun recordProviderSourceDroppedTotal(
            reportedTotal: Long,
        ): Long {
            val previous =
                lastProviderSourceDroppedTotal

            /*
             * A lower value means the provider restarted/reset its local
             * counter. Treat the new value as loss in the new session rather
             * than allowing our runtime total to move backwards.
             */
            val delta =
                if (
                    reportedTotal >=
                    previous
                ) {
                    reportedTotal -
                        previous
                } else {
                    reportedTotal
                }

            lastProviderSourceDroppedTotal =
                reportedTotal

            sourceDroppedTotal =
                if (
                    Long.MAX_VALUE -
                        sourceDroppedTotal <
                    delta
                ) {
                    Long.MAX_VALUE
                } else {
                    sourceDroppedTotal +
                        delta
                }

            return sourceDroppedTotal
        }

        private fun setState(
            state: RuntimeState,
        ) {
            currentState =
                state

            publishRuntimeState(
                logicalId =
                    logicalId,
                state =
                    state,
            )
        }
    }

    private class ManagedSubscription(
        override val id: Long,
        val clientId: Long,
        val request:
            SensorSubscriptionRequest,
        bufferCapacity: Int,
        initialRuntimeState:
            RuntimeState,
        initialSourceDroppedTotal:
            Long,
        private val onClose:
            (Long) -> Unit,
    ) : SensorSubscription {

        override val logicalId:
            String =
            request.logicalId

        private val closed =
            AtomicBoolean(false)

        private val sampleLock =
            Any()

        private val mutableState =
            MutableStateFlow(
                initialRuntimeState
                    .toSubscriptionState(),
            )

        override val state:
            StateFlow<
                SensorSubscriptionState
            > =
            mutableState
                .asStateFlow()

        private val sampleChannel =
            Channel<
                SensorSubscriptionSample
            >(
                capacity =
                    bufferCapacity,
            )

        override val samples =
            sampleChannel
                .receiveAsFlow()

        override val isClosed:
            Boolean
            get() =
                closed.get()

        private var lastDeliveredAtMs:
            Long? =
            null

        private var nextSequence =
            1L

        private var droppedTotal =
            0L

        /*
         * Runtime source accounting is shared by every consumer. Subtract the
         * value at subscription creation so this consumer sees only losses
         * occurring during its own lifetime.
         */
        private val sourceDroppedBaseline =
            initialSourceDroppedTotal

        fun updateRuntimeState(
            runtimeState:
                RuntimeState,
        ) {
            if (
                closed.get()
            ) {
                return
            }

            mutableState.value =
                runtimeState
                    .toSubscriptionState()
        }

        fun resetDeliveryCadence() {
            synchronized(
                sampleLock,
            ) {
                lastDeliveredAtMs =
                    null
            }
        }

        fun offer(
            reading: SensorReading,
            sourceDroppedTotal: Long,
            nowMs: Long,
        ) {
            if (
                closed.get()
            ) {
                return
            }

            synchronized(
                sampleLock,
            ) {
                if (
                    closed.get()
                ) {
                    return
                }

                val previous =
                    lastDeliveredAtMs

                if (
                    previous != null &&
                    nowMs >= previous &&
                    nowMs - previous <
                        request.periodMs
                ) {
                    return
                }

                lastDeliveredAtMs =
                    nowMs

                val sequence =
                    nextSequence++

                fun sample() =
                    SensorSubscriptionSample(
                        sequence =
                            sequence,
                        reading =
                            reading,
                        droppedTotal =
                            droppedTotal,
                        sourceDroppedTotal =
                            (
                                sourceDroppedTotal -
                                    sourceDroppedBaseline
                            )
                                .coerceAtLeast(
                                    0L,
                                ),
                    )

                if (
                    sampleChannel
                        .trySend(
                            sample(),
                        )
                        .isSuccess
                ) {
                    return
                }

                if (
                    closed.get()
                ) {
                    return
                }

                val removed =
                    sampleChannel
                        .tryReceive()
                        .getOrNull()

                if (
                    removed != null
                ) {
                    droppedTotal++
                }

                if (
                    sampleChannel
                        .trySend(
                            sample(),
                        )
                        .isSuccess
                ) {
                    return
                }

                /*
                 * Either the consumer raced us or the queue remained full.
                 * The current sample was not admitted, so account for it.
                 */
                droppedTotal++
            }
        }

        override fun close() {
            if (
                !closed.get()
            ) {
                onClose(
                    id,
                )
            }
        }

        fun closeFromManager() {
            if (
                !closed.compareAndSet(
                    false,
                    true,
                )
            ) {
                return
            }

            mutableState.value =
                mutableState.value
                    .copy(
                        phase =
                            SensorSubscriptionPhase
                                .CLOSED,
                        providerId =
                            null,
                        providerEffectivePeriodMs =
                            null,
                        reason =
                            null,
                    )

            sampleChannel.close()
        }

        private fun RuntimeState
            .toSubscriptionState():
            SensorSubscriptionState =
            SensorSubscriptionState(
                subscriptionId =
                    id,
                logicalId =
                    logicalId,
                phase =
                    phase,
                providerId =
                    providerId,
                requestedPeriodMs =
                    request.periodMs,
                acquisitionPeriodMs =
                    acquisitionPeriodMs,
                providerEffectivePeriodMs =
                    providerEffectivePeriodMs,
                reason =
                    reason,
            )
    }
}
