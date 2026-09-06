// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import java.util.Locale

/**
 * SensorManager-backed process-visible hardware census.
 *
 * This provider only calls getSensorList(TYPE_ALL). It never registers a
 * SensorEventListener and therefore never starts continuous sensing.
 */
class AndroidSensorInventory(
    context: Context,
) : SensorInventory {

    private val sensorManager =
        context.getSystemService(
            SensorManager::class.java,
        )

    override fun snapshot(): List<SensorInventoryEntry> =
        sensorManager
            ?.getSensorList(
                Sensor.TYPE_ALL,
            )
            .orEmpty()
            .sortedWith(
                compareBy<Sensor>(
                    { it.type },
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
                ),
            )
            .map { sensor ->
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
            }

    /**
     * Public Android sensors receive stable logical names such as
     * accelerometer, pressure or heart_rate.
     *
     * Vendor/private string types deliberately fall back to their numeric
     * Android type for now. M4 provider discovery will classify them instead
     * of accidentally treating a vendor name as a stable Diesel API.
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
    }
}
