// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityCatalog
import org.aaustralian.dieselbridge.platform.sensor.SensorReading
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorBufferPolicy
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationClient
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscription
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionPhase
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionRequest
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionSample
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionState
import org.aaustralian.dieselbridge.protocol.DieselEvent
import org.aaustralian.dieselbridge.protocol.DieselEventTransport
import org.aaustralian.dieselbridge.protocol.DieselValue

data class PublicSensorSubscriptionLimits(
    val maxSubscriptions: Int = 4,
    val minPeriodMs: Long = 250L,
    val maxPeriodMs: Long = 60_000L,
    val defaultPeriodMs: Long = 1_000L,
    val minLeaseMs: Long = 5_000L,
    val maxLeaseMs: Long = 300_000L,
    val defaultLeaseMs: Long = 60_000L,
) {
    init {
        require(maxSubscriptions > 0)
        require(minPeriodMs > 0L)
        require(maxPeriodMs >= minPeriodMs)
        require(defaultPeriodMs in minPeriodMs..maxPeriodMs)
        require(minLeaseMs > 0L)
        require(maxLeaseMs >= minLeaseMs)
        require(defaultLeaseMs in minLeaseMs..maxLeaseMs)
    }
}

enum class PublicSensorSubscriptionRejectReason {
    UNKNOWN_SENSOR,
    PERIOD_OUT_OF_BOUNDS,
    LEASE_OUT_OF_BOUNDS,
    SUBSCRIPTION_LIMIT_REACHED,
}

data class PublicSensorSubscriptionSnapshot(
    val subscriptionId: Long,
    val logicalId: String,
    val phase: SensorSubscriptionPhase,
    val providerId: String?,
    val requestedPeriodMs: Long,
    val acquisitionPeriodMs: Long?,
    val providerEffectivePeriodMs: Long?,
    val reason: String?,
    val leaseMs: Long,
    val expiresAtMs: Long,
    val sourceDroppedTotal: Long,
    val transportDroppedTotal: Long,
)

sealed interface PublicSensorSubscribeResult {

    data class Opened(
        val snapshot: PublicSensorSubscriptionSnapshot,
    ) : PublicSensorSubscribeResult

    data class Rejected(
        val reason: PublicSensorSubscriptionRejectReason,
    ) : PublicSensorSubscribeResult
}

/**
 * Bounded public bridge from Diesel protocol clients into the shared sensor
 * observation runtime.
 *
 * This layer deliberately exposes stricter limits than SensorObservationManager:
 * logical targets only, latest-value buffering, finite leases and a small remote
 * subscription count. It never selects concrete Android routes or providers.
 */
class PublicSensorSubscriptionController(
    private val client: SensorObservationClient,
    private val eventTransport: DieselEventTransport,
    scope: CoroutineScope,
    private val limits: PublicSensorSubscriptionLimits =
        PublicSensorSubscriptionLimits(),
    private val monotonicMs: () -> Long = {
        System.nanoTime() /
            1_000_000L
    },
) : AutoCloseable {

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

    private var closed =
        false

    private data class Record(
        val subscription: SensorSubscription,
        val leaseMs: Long,
        val expiresAtMs: Long,
        var state: SensorSubscriptionState,
        var sourceDroppedTotal: Long = 0L,
        var transportDroppedTotal: Long = 0L,
        var stateJob: Job? = null,
        var sampleJob: Job? = null,
        var leaseJob: Job? = null,
    )

    private val records =
        linkedMapOf<Long, Record>()

    fun subscribe(
        logicalId: String,
        periodMs: Long =
            limits.defaultPeriodMs,
        leaseMs: Long =
            limits.defaultLeaseMs,
    ): PublicSensorSubscribeResult {
        if (
            logicalId !in
            SensorCapabilityCatalog
                .standardLogicalIds
        ) {
            return PublicSensorSubscribeResult.Rejected(
                PublicSensorSubscriptionRejectReason
                    .UNKNOWN_SENSOR,
            )
        }

        if (
            periodMs !in
            limits.minPeriodMs..
                limits.maxPeriodMs
        ) {
            return PublicSensorSubscribeResult.Rejected(
                PublicSensorSubscriptionRejectReason
                    .PERIOD_OUT_OF_BOUNDS,
            )
        }

        if (
            leaseMs !in
            limits.minLeaseMs..
                limits.maxLeaseMs
        ) {
            return PublicSensorSubscribeResult.Rejected(
                PublicSensorSubscriptionRejectReason
                    .LEASE_OUT_OF_BOUNDS,
            )
        }

        synchronized(lock) {
            check(!closed) {
                "Public sensor subscription controller is closed"
            }

            if (
                records.size >=
                limits.maxSubscriptions
            ) {
                return PublicSensorSubscribeResult.Rejected(
                    PublicSensorSubscriptionRejectReason
                        .SUBSCRIPTION_LIMIT_REACHED,
                )
            }
        }

        val subscription =
            client.subscribe(
                SensorSubscriptionRequest(
                    logicalId =
                        logicalId,
                    periodMs =
                        periodMs,
                    bufferPolicy =
                        SensorBufferPolicy.Latest,
                ),
            )

        val expiresAtMs =
            monotonicMs() +
                leaseMs

        val record =
            Record(
                subscription =
                    subscription,
                leaseMs =
                    leaseMs,
                expiresAtMs =
                    expiresAtMs,
                state =
                    subscription.state.value,
            )

        synchronized(lock) {
            if (closed) {
                subscription.close()
                error(
                    "Public sensor subscription controller is closed",
                )
            }

            if (
                records.size >=
                limits.maxSubscriptions
            ) {
                subscription.close()

                return PublicSensorSubscribeResult.Rejected(
                    PublicSensorSubscriptionRejectReason
                        .SUBSCRIPTION_LIMIT_REACHED,
                )
            }

            records[
                subscription.id
            ] = record
        }

        startCollectors(
            record,
        )

        return PublicSensorSubscribeResult.Opened(
            snapshotOf(
                record,
            ),
        )
    }

    fun unsubscribe(
        subscriptionId: Long,
    ): Boolean =
        closeSubscription(
            subscriptionId =
                subscriptionId,
            reason =
                "client_unsubscribe",
            emitClosedState =
                true,
        )

    fun snapshots():
        List<PublicSensorSubscriptionSnapshot> =
        synchronized(lock) {
            records
                .values
                .map(
                    ::snapshotOf,
                )
        }

    fun closeAll(
        reason: String =
            "client_closed",
    ) {
        val ids =
            synchronized(lock) {
                records
                    .keys
                    .toList()
            }

        ids.forEach {
            closeSubscription(
                subscriptionId =
                    it,
                reason =
                    reason,
                emitClosedState =
                    true,
            )
        }
    }

    override fun close() {
        val shouldClose =
            synchronized(lock) {
                if (closed) {
                    false
                } else {
                    closed =
                        true

                    true
                }
            }

        if (!shouldClose) {
            return
        }

        closeAll(
            reason =
                "controller_closed",
        )

        client.close()
        controllerScope.cancel()
    }

    private fun startCollectors(
        record: Record,
    ) {
        record.stateJob =
            controllerScope.launch {
                record.subscription
                    .state
                    .collect {
                            state,
                        ->
                        synchronized(lock) {
                            records[
                                record.subscription.id
                            ]
                                ?.state =
                                state
                        }

                        sendStateEvent(
                            record =
                                record,
                            state =
                                state,
                        )
                    }
            }

        record.sampleJob =
            controllerScope.launch {
                record.subscription
                    .samples
                    .collect {
                            sample,
                        ->
                        synchronized(lock) {
                            records[
                                record.subscription.id
                            ]
                                ?.sourceDroppedTotal =
                                sample.droppedTotal
                        }

                        sendSampleEvent(
                            record =
                                record,
                            sample =
                                sample,
                        )
                    }
            }

        record.leaseJob =
            controllerScope.launch {
                delay(
                    record.leaseMs,
                )

                closeSubscription(
                    subscriptionId =
                        record.subscription.id,
                    reason =
                        "lease_expired",
                    emitClosedState =
                        true,
                )
            }
    }

    private fun closeSubscription(
        subscriptionId: Long,
        reason: String,
        emitClosedState: Boolean,
    ): Boolean {
        val record =
            synchronized(lock) {
                records.remove(
                    subscriptionId,
                )
            }
                ?: return false

        record.stateJob?.cancel()
        record.sampleJob?.cancel()
        record.leaseJob?.cancel()

        record.subscription.close()

        if (emitClosedState) {
            sendClosedStateEvent(
                record =
                    record,
                reason =
                    reason,
            )
        }

        return true
    }

    private fun sendSampleEvent(
        record: Record,
        sample: SensorSubscriptionSample,
    ) {
        val transportDroppedBefore =
            synchronized(lock) {
                records[
                    record.subscription.id
                ]
                    ?.transportDroppedTotal
                    ?: record
                        .transportDroppedTotal
            }

        val event =
            DieselEvent(
                topic =
                    TOPIC_SENSOR_SAMPLE,
                data =
                    encodeSample(
                        subscriptionId =
                            record.subscription.id,
                        sample =
                            sample,
                        transportDroppedTotal =
                            transportDroppedBefore,
                    ),
            )

        if (
            !eventTransport.send(
                event,
            )
        ) {
            synchronized(lock) {
                val active =
                    records[
                        record.subscription.id
                    ]

                if (active != null) {
                    active.transportDroppedTotal++
                } else {
                    record.transportDroppedTotal++
                }
            }
        }
    }

    private fun sendStateEvent(
        record: Record,
        state: SensorSubscriptionState,
    ) {
        val transportDroppedBefore =
            synchronized(lock) {
                records[
                    record.subscription.id
                ]
                    ?.transportDroppedTotal
                    ?: record
                        .transportDroppedTotal
            }

        if (
            !eventTransport.send(
                DieselEvent(
                    topic =
                        TOPIC_SENSOR_STATE,
                    data =
                        encodeState(
                            state =
                                state,
                            leaseMs =
                                record.leaseMs,
                            expiresAtMs =
                                record.expiresAtMs,
                            transportDroppedTotal =
                                transportDroppedBefore,
                        ),
                ),
            )
        ) {
            synchronized(lock) {
                val active =
                    records[
                        record.subscription.id
                    ]

                if (active != null) {
                    active.transportDroppedTotal++
                } else {
                    record.transportDroppedTotal++
                }
            }
        }
    }

    private fun sendClosedStateEvent(
        record: Record,
        reason: String,
    ) {
        val finalState =
            record.state.copy(
                phase =
                    SensorSubscriptionPhase.CLOSED,
                reason =
                    reason,
            )

        eventTransport.send(
            DieselEvent(
                topic =
                    TOPIC_SENSOR_STATE,
                data =
                    encodeState(
                        state =
                            finalState,
                        leaseMs =
                            record.leaseMs,
                        expiresAtMs =
                            record.expiresAtMs,
                        transportDroppedTotal =
                            record.transportDroppedTotal,
                    ),
            ),
        )
    }

    private fun snapshotOf(
        record: Record,
    ): PublicSensorSubscriptionSnapshot =
        PublicSensorSubscriptionSnapshot(
            subscriptionId =
                record.subscription.id,
            logicalId =
                record.subscription.logicalId,
            phase =
                record.state.phase,
            providerId =
                record.state.providerId,
            requestedPeriodMs =
                record.state.requestedPeriodMs,
            acquisitionPeriodMs =
                record.state.acquisitionPeriodMs,
            providerEffectivePeriodMs =
                record.state.providerEffectivePeriodMs,
            reason =
                record.state.reason,
            leaseMs =
                record.leaseMs,
            expiresAtMs =
                record.expiresAtMs,
            sourceDroppedTotal =
                record.sourceDroppedTotal,
            transportDroppedTotal =
                record.transportDroppedTotal,
        )

    private fun encodeSample(
        subscriptionId: Long,
        sample: SensorSubscriptionSample,
        transportDroppedTotal: Long,
    ): Map<String, DieselValue> {
        val reading =
            sample.reading

        val values =
            reading.values
                .take(
                    MAX_SAMPLE_VALUES,
                )

        return linkedMapOf(
            "subscriptionId" to
                DieselValue.Integer(
                    subscriptionId,
                ),
            "sequence" to
                DieselValue.Integer(
                    sample.sequence,
                ),
            "capability" to
                DieselValue.Text(
                    reading
                        .capabilityId
                        .value,
                ),
            "providerId" to
                DieselValue.Text(
                    reading.providerId,
                ),
            "sensorTimestampNs" to
                DieselValue.Integer(
                    reading.timestampNanos,
                ),
            "accuracy" to
                (
                    reading.accuracy
                        ?.let {
                            DieselValue.Integer(
                                it.toLong(),
                            )
                        }
                        ?: DieselValue.Null
                ),
            "valueCount" to
                DieselValue.Integer(
                    reading.values
                        .size
                        .toLong(),
                ),
            "returnedValueCount" to
                DieselValue.Integer(
                    values.size
                        .toLong(),
                ),
            "valuesTruncated" to
                DieselValue.Flag(
                    reading.values.size >
                        values.size,
                ),
            "values" to
                DieselValue.ListValue(
                    values.map(
                        ::encodeFloat,
                    ),
                ),
            "sourceDroppedTotal" to
                DieselValue.Integer(
                    sample.droppedTotal,
                ),
            "transportDroppedTotal" to
                DieselValue.Integer(
                    transportDroppedTotal,
                ),
            "droppedTotal" to
                DieselValue.Integer(
                    sample.droppedTotal +
                        transportDroppedTotal,
                ),
        )
    }

    private fun encodeState(
        state: SensorSubscriptionState,
        leaseMs: Long,
        expiresAtMs: Long,
        transportDroppedTotal: Long,
    ): Map<String, DieselValue> =
        linkedMapOf(
            "subscriptionId" to
                DieselValue.Integer(
                    state.subscriptionId,
                ),
            "capability" to
                DieselValue.Text(
                    "sensor." +
                        state.logicalId,
                ),
            "phase" to
                DieselValue.Text(
                    state.phase
                        .name
                        .lowercase(),
                ),
            "providerId" to
                (
                    state.providerId
                        ?.let(
                            DieselValue::Text,
                        )
                        ?: DieselValue.Null
                ),
            "requestedPeriodMs" to
                DieselValue.Integer(
                    state.requestedPeriodMs,
                ),
            "acquisitionPeriodMs" to
                nullableInteger(
                    state.acquisitionPeriodMs,
                ),
            "providerEffectivePeriodMs" to
                nullableInteger(
                    state.providerEffectivePeriodMs,
                ),
            "reason" to
                (
                    state.reason
                        ?.let(
                            DieselValue::Text,
                        )
                        ?: DieselValue.Null
                ),
            "leaseMs" to
                DieselValue.Integer(
                    leaseMs,
                ),
            "expiresAtMs" to
                DieselValue.Integer(
                    expiresAtMs,
                ),
            "transportDroppedTotal" to
                DieselValue.Integer(
                    transportDroppedTotal,
                ),
        )

    private fun nullableInteger(
        value: Long?,
    ): DieselValue =
        value
            ?.let(
                DieselValue::Integer,
            )
            ?: DieselValue.Null

    private fun encodeFloat(
        value: Float,
    ): DieselValue =
        if (value.isFinite()) {
            DieselValue.Decimal(
                value.toDouble(),
            )
        } else {
            DieselValue.Null
        }

    companion object {
        const val TOPIC_SENSOR_SAMPLE =
            "sensor.sample"

        const val TOPIC_SENSOR_STATE =
            "sensor.subscription.state"

        const val MAX_SAMPLE_VALUES =
            16
    }
}
