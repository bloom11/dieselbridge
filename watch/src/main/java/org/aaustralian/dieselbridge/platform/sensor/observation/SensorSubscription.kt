// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor.observation

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.aaustralian.dieselbridge.platform.sensor.SensorReading

sealed interface SensorBufferPolicy {

    /**
     * Keep only a very small latest-value queue.
     *
     * Suitable for heart rate, steps, watch UI and Gadgetbridge realtime data.
     */
    data object Latest :
        SensorBufferPolicy

    /**
     * Finite queue for consumers that need short bursts of ordered samples.
     */
    data class Bounded(
        val capacity: Int,
    ) : SensorBufferPolicy {
        init {
            require(capacity > 0) {
                "Buffer capacity must be positive"
            }
        }
    }
}

/**
 * Process-local safety limits.
 *
 * External APIs may impose stricter limits but must not be able to exceed
 * these platform bounds.
 */
data class SensorObservationLimits(
    val maxClients: Int = 16,
    val maxSubscriptions: Int = 32,
    val maxSubscriptionsPerClient: Int = 8,
    val maxSubscriptionsPerSensor: Int = 8,
    val maxBufferCapacity: Int = 64,
    val minPeriodMs: Long = 20L,
    val maxPeriodMs: Long = 3_600_000L,
) {
    init {
        require(maxClients > 0)
        require(maxSubscriptions > 0)
        require(maxSubscriptionsPerClient > 0)
        require(maxSubscriptionsPerSensor > 0)
        require(maxBufferCapacity > 0)
        require(minPeriodMs > 0L)
        require(maxPeriodMs >= minPeriodMs)
    }
}

data class SensorSubscriptionRequest(
    val logicalId: String,
    val periodMs: Long,
    val bufferPolicy: SensorBufferPolicy =
        SensorBufferPolicy.Latest,
)

enum class SensorSubscriptionPhase {
    WAITING_FOR_PROVIDER,
    STARTING,
    ACTIVE,
    DEGRADED,
    CLOSED,
}

data class SensorSubscriptionState(
    val subscriptionId: Long,
    val logicalId: String,
    val phase: SensorSubscriptionPhase,
    val providerId: String?,
    val requestedPeriodMs: Long,
    val acquisitionPeriodMs: Long?,
    val providerEffectivePeriodMs: Long?,
    val reason: String?,
)

/**
 * One sample delivered to one consumer.
 *
 * sequence increments for every sample admitted for this consumer after
 * cadence filtering.
 *
 * droppedTotal counts this subscription's own bounded-queue losses.
 * sourceDroppedTotal counts upstream provider-ingress losses observed during
 * this subscription.
 */
data class SensorSubscriptionSample(
    val sequence: Long,
    val reading: SensorReading,
    val droppedTotal: Long,
    val sourceDroppedTotal: Long = 0L,
) {
    init {
        require(droppedTotal >= 0L)
        require(sourceDroppedTotal >= 0L)
    }
}

/**
 * One logical consumer subscription.
 *
 * samples is intentionally single-consumer. A caller that needs fan-out
 * should open independent subscriptions so each consumer has its own cadence
 * and backpressure accounting.
 */
interface SensorSubscription :
    AutoCloseable {

    val id: Long
    val logicalId: String
    val state: StateFlow<SensorSubscriptionState>
    val samples: Flow<SensorSubscriptionSample>
    val isClosed: Boolean

    override fun close()
}

/**
 * Lifecycle owner for a group of subscriptions.
 *
 * Future Binder connections, Gadgetbridge sessions and local modules can each
 * own a client. Closing a client closes every subscription it created.
 */
class SensorObservationClient internal constructor(
    private val manager:
        SensorObservationManager,
    internal val clientId: Long,
    val label: String,
) : AutoCloseable {

    private val closed =
        AtomicBoolean(false)

    fun subscribe(
        request: SensorSubscriptionRequest,
    ): SensorSubscription {
        check(!closed.get()) {
            "Sensor observation client is closed"
        }

        return manager.subscribe(
            clientId = clientId,
            request = request,
        )
    }

    override fun close() {
        if (
            closed.compareAndSet(
                false,
                true,
            )
        ) {
            manager.closeClient(
                clientId,
            )
        }
    }
}
