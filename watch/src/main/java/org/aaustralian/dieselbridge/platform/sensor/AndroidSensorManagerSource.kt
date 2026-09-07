// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import java.util.Locale

/**
 * One live Android SensorManager route.
 *
 * [route] contains the provider-neutral diagnostic identity and passive
 * inventory metadata. [sensor] is the exact Android Sensor object from the
 * same ordered SensorManager snapshot.
 *
 * This is an internal implementation handle. It is never a wire-protocol
 * object and normal Diesel sensor consumers must not select it directly.
 */
internal data class AndroidSensorHandle(
    val route: AndroidSensorRoute,
    val sensor: Sensor,
)

/**
 * Canonical process-local view of Android SensorManager sensors.
 *
 * Every snapshot performs only getSensorList(TYPE_ALL). It does not register
 * listeners, request trigger sensors, or otherwise activate hardware.
 *
 * Keeping the Sensor object, inventory projection and route identity in one
 * ordered snapshot prevents a future developer probe from independently
 * reconstructing which physical Sensor a route id represented.
 */
internal class AndroidSensorManagerSource(
    context: Context,
) {

    private val sensorManager =
        context.applicationContext
            .getSystemService(
                SensorManager::class.java,
            )

    fun snapshot():
        List<AndroidSensorHandle> {
        val sensors =
            sensorManager
                ?.getSensorList(
                    Sensor.TYPE_ALL,
                )
                .orEmpty()
                .sortedWith(
                    SENSOR_ORDER,
                )

        val inventory =
            sensors.map(
                ::inventoryEntry,
            )

        val routes =
            SensorManagerRouteProjector
                .project(
                    inventory,
                )

        check(
            routes.size ==
                sensors.size,
        ) {
            "Android sensor route projection changed snapshot size"
        }

        return sensors
            .indices
            .map { index ->
                AndroidSensorHandle(
                    route =
                        routes[index],
                    sensor =
                        sensors[index],
                )
            }
    }

    /**
     * Resolve an opaque route against a fresh process-visible snapshot.
     *
     * Resolution itself is passive. M4.2b1b will be responsible for bounded
     * registration/sampling after an authorized caller has selected a route.
     */
    fun resolve(
        routeId: SensorRouteId,
    ): AndroidSensorHandle? =
        snapshot()
            .firstOrNull { handle ->
                handle
                    .route
                    .descriptor
                    .routeId ==
                    routeId
            }

    private fun inventoryEntry(
        sensor: Sensor,
    ): SensorInventoryEntry =
        SensorInventoryEntry(
            logicalId =
                logicalId(
                    sensor,
                ),
            androidId =
                sensor.id,
            androidType =
                sensor.type,
            stringType =
                sensor.stringType
                    .orEmpty(),
            name =
                sensor.name
                    .orEmpty(),
            vendor =
                sensor.vendor
                    .orEmpty(),
            version =
                sensor.version,
            maxRange =
                sensor.maximumRange,
            resolution =
                sensor.resolution,
            powerMilliAmps =
                sensor.power,
            minDelayUs =
                sensor.minDelay,
            maxDelayUs =
                sensor.maxDelay,
            fifoReservedEventCount =
                sensor.fifoReservedEventCount,
            fifoMaxEventCount =
                sensor.fifoMaxEventCount,
            reportingMode =
                sensor.reportingMode,
            wakeUp =
                sensor.isWakeUpSensor,
        )

    /**
     * Public Android sensors receive stable logical names such as
     * accelerometer, pressure or heart_rate.
     *
     * Vendor/private string types deliberately retain provisional numeric
     * logical ids until their semantics are established empirically.
     */
    private fun logicalId(
        sensor: Sensor,
    ): String {
        val stringType =
            sensor.stringType
                .orEmpty()

        if (
            stringType.startsWith(
                ANDROID_SENSOR_PREFIX,
            )
        ) {
            val normalized =
                stringType
                    .removePrefix(
                        ANDROID_SENSOR_PREFIX,
                    )
                    .lowercase(
                        Locale.ROOT,
                    )
                    .replace(
                        NON_IDENTIFIER_CHARACTER,
                        "_",
                    )
                    .trim(
                        '_',
                        '.',
                        '-',
                    )

            if (normalized.isNotEmpty()) {
                val identifier =
                    if (
                        normalized.first() in
                        'a'..'z'
                    ) {
                        normalized
                    } else {
                        "type_$normalized"
                    }

                return identifier
                    .take(
                        MAX_LOGICAL_ID_LENGTH,
                    )
                    .trimEnd(
                        '_',
                        '.',
                        '-',
                    )
                    .ifEmpty {
                        fallbackId(
                            sensor.type,
                        )
                    }
            }
        }

        return fallbackId(
            sensor.type,
        )
    }

    private fun fallbackId(
        androidType: Int,
    ): String =
        "android_type_$androidType"

    private companion object {
        const val ANDROID_SENSOR_PREFIX =
            "android.sensor."

        const val MAX_LOGICAL_ID_LENGTH =
            64

        val NON_IDENTIFIER_CHARACTER =
            Regex(
                "[^a-z0-9_.-]+",
            )

        /*
         * This is intentionally the same ordering used by the original
         * AndroidSensorInventory. Preserving it preserves existing route ids.
         */
        val SENSOR_ORDER =
            compareBy<Sensor>(
                {
                    it.type
                },
                {
                    it.stringType
                        .orEmpty()
                },
                {
                    it.id
                },
                {
                    it.name
                        .orEmpty()
                },
                {
                    it.vendor
                        .orEmpty()
                },
            )
    }
}
