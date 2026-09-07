// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

/**
 * Typed logical identity assigned during sensor discovery.
 *
 * This is intentionally not yet the final public Diesel sensor capability id.
 * Standard Android sensor families such as accelerometer or heart_rate are
 * stable enough to classify directly, while vendor/private sensors may still
 * carry provisional ids such as android_type_33171103 until their semantics
 * are understood.
 */
@JvmInline
value class SensorLogicalId(
    val value: String,
) {
    init {
        require(
            VALID_VALUE.matches(
                value,
            ),
        ) {
            "Invalid sensor logical id '$value'"
        }
    }

    private companion object {
        val VALID_VALUE =
            Regex(
                "[a-z][a-z0-9_.-]{0,63}",
            )
    }
}

/**
 * Opaque identity of one concrete sensor route.
 *
 * Normal applications must not select providers by parsing or constructing
 * this value. It exists for route inventory, diagnostics and explicit
 * developer probing.
 */
@JvmInline
value class SensorRouteId(
    val value: String,
) {
    init {
        require(
            value.isNotBlank() &&
                value.length <=
                    MAX_LENGTH &&
                value.none {
                    Character.isISOControl(
                        it.code,
                    )
                },
        ) {
            "Invalid sensor route id"
        }
    }

    private companion object {
        const val MAX_LENGTH =
            128
    }
}

/**
 * Provider-neutral identity of one concrete route.
 *
 * It deliberately contains no Android SensorManager object or metadata.
 * Health Services, Mobvoi/private providers and future APK plugins can
 * therefore describe routes using the same type.
 *
 * This descriptor does not choose a provider. Normal application access will
 * still resolve logical SensorCapability implementations through the Diesel
 * CapabilityRegistry.
 */
data class SensorRouteDescriptor(
    val routeId: SensorRouteId,
    val logicalId: SensorLogicalId,
    val providerId: String,
) {
    init {
        require(
            providerId.isNotBlank() &&
                providerId.length <=
                    MAX_PROVIDER_ID_LENGTH &&
                providerId.none {
                    Character.isISOControl(
                        it.code,
                    )
                },
        ) {
            "Invalid sensor provider id"
        }
    }

    private companion object {
        const val MAX_PROVIDER_ID_LENGTH =
            128
    }
}
