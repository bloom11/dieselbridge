// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor.observation

import kotlinx.coroutines.flow.Flow
import org.aaustralian.dieselbridge.platform.capability.DieselCapability
import org.aaustralian.dieselbridge.platform.sensor.SensorReading

/**
 * Stable capability identity for a long-lived logical sensor observation.
 *
 * Observation uses a different capability id from bounded spot reads so
 * provider selection can differ between the two modes.
 *
 * Examples:
 *   sensor.heart_rate
 *   sensor.observe.heart_rate
 */
@JvmInline
value class SensorObservationCapabilityId(
    val value: String,
) {
    init {
        require(
            value.startsWith(PREFIX) &&
                value.length > PREFIX.length,
        ) {
            "Invalid sensor observation capability id"
        }
    }

    val logicalId: String
        get() = value.removePrefix(PREFIX)

    companion object {
        const val PREFIX = "sensor.observe."

        private val LOGICAL_ID =
            Regex("[a-z][a-z0-9_.-]*")

        fun forLogical(
            logicalId: String,
        ): SensorObservationCapabilityId {
            require(LOGICAL_ID.matches(logicalId)) {
                "Invalid logical sensor id"
            }

            return SensorObservationCapabilityId(
                PREFIX + logicalId,
            )
        }
    }
}

/**
 * Provider acquisition preference.
 *
 * This is deliberately a preference rather than a guarantee. Some providers
 * have fixed/on-change cadence or impose their own minimum sampling interval.
 */
data class SensorObservationOptions(
    val preferredSamplePeriodMs: Long,
) {
    init {
        require(preferredSamplePeriodMs > 0L) {
            "preferredSamplePeriodMs must be positive"
        }
    }
}

/**
 * Provider-side observation updates.
 *
 * Operational conditions are represented as values rather than exceptions.
 * Cancellation remains coroutine cancellation and unexpected implementation
 * failures may still throw.
 */
sealed interface SensorObservationUpdate {

    data class Started(
        val effectiveSamplePeriodMs: Long? = null,
    ) : SensorObservationUpdate

    data class Sample(
        val reading: SensorReading,

        /**
         * Monotonic count, within one provider observation session, of
         * samples dropped before they reached SensorObservationManager.
         */
        val sourceDroppedTotal: Long = 0L,
    ) : SensorObservationUpdate {
        init {
            require(sourceDroppedTotal >= 0L) {
                "sourceDroppedTotal must not be negative"
            }
        }
    }

    data class PermissionDenied(
        val requiredPermission: String?,
    ) : SensorObservationUpdate

    data class Unavailable(
        val reason: String? = null,
    ) : SensorObservationUpdate

    data class RegistrationRejected(
        val reason: String? = null,
    ) : SensorObservationUpdate
}

/**
 * Long-lived observation capability for one logical sensor.
 *
 * Collection starts the provider acquisition. Cancelling collection MUST
 * release the underlying provider callback/session.
 */
interface SensorObservationCapability :
    DieselCapability {

    val capabilityId:
        SensorObservationCapabilityId

    val logicalId: String
        get() = capabilityId.logicalId

    override val id: String
        get() = capabilityId.value

    fun observe(
        options: SensorObservationOptions,
    ): Flow<SensorObservationUpdate>
}
