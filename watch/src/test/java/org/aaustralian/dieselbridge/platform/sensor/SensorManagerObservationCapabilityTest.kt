// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorManagerObservationCapabilityTest {

    @Test
    fun routePreferenceMatchesLogicalReadOrdering() {
        val selected =
            selectSensorManagerObservationRoute(
                routes =
                    listOf(
                        route(
                            routeId =
                                "route-c",
                            logicalId =
                                "accelerometer",
                            wakeUp =
                                true,
                            power =
                                0.1f,
                        ),
                        route(
                            routeId =
                                "route-b",
                            logicalId =
                                "accelerometer",
                            wakeUp =
                                false,
                            power =
                                0.4f,
                        ),
                        route(
                            routeId =
                                "route-z",
                            logicalId =
                                "accelerometer",
                            wakeUp =
                                false,
                            power =
                                0.2f,
                        ),
                        route(
                            routeId =
                                "route-a",
                            logicalId =
                                "accelerometer",
                            wakeUp =
                                false,
                            power =
                                0.2f,
                        ),
                    ),
                logicalId =
                    "accelerometer",
            )

        assertEquals(
            "route-a",
            selected
                ?.descriptor
                ?.routeId
                ?.value,
        )
    }

    @Test
    fun routePreferenceDoesNotCrossLogicalTargets() {
        val selected =
            selectSensorManagerObservationRoute(
                routes =
                    listOf(
                        route(
                            routeId =
                                "route-a",
                            logicalId =
                                "pressure",
                            wakeUp =
                                false,
                            power =
                                0.1f,
                        ),
                    ),
                logicalId =
                    "accelerometer",
            )

        assertNull(
            selected,
        )
    }

    private fun route(
        routeId: String,
        logicalId: String,
        wakeUp: Boolean,
        power: Float,
    ): AndroidSensorRoute =
        AndroidSensorRoute(
            descriptor =
                SensorRouteDescriptor(
                    routeId =
                        SensorRouteId(
                            routeId,
                        ),
                    logicalId =
                        SensorLogicalId(
                            logicalId,
                        ),
                    providerId =
                        SensorManagerRouteCatalog
                            .PROVIDER_ID,
                ),
            inventory =
                SensorInventoryEntry(
                    logicalId =
                        logicalId,
                    androidId =
                        1,
                    androidType =
                        1,
                    stringType =
                        "android.sensor.$logicalId",
                    name =
                        logicalId,
                    vendor =
                        "test",
                    version =
                        1,
                    maxRange =
                        1f,
                    resolution =
                        1f,
                    powerMilliAmps =
                        power,
                    minDelayUs =
                        1,
                    maxDelayUs =
                        1,
                    fifoReservedEventCount =
                        0,
                    fifoMaxEventCount =
                        0,
                    reportingMode =
                        Sensor.REPORTING_MODE_CONTINUOUS,
                    wakeUp =
                        wakeUp,
                ),
        )
}
