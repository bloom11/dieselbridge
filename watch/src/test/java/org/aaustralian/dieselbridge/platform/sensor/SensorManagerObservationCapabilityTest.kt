// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorManagerObservationCapabilityTest {

    @Test
    fun spotReadPreferenceRemainsNonWakeThenLowPower() {
        val selected =
            SensorManagerLogicalRouteSelector
                .selectRoute(
                    routes =
                        listOf(
                            route(
                                routeId =
                                    "wake",
                                logicalId =
                                    "accelerometer",
                                wakeUp =
                                    true,
                                power =
                                    0.01f,
                            ),
                            route(
                                routeId =
                                    "nonwake-high",
                                logicalId =
                                    "accelerometer",
                                wakeUp =
                                    false,
                                power =
                                    0.4f,
                            ),
                            route(
                                routeId =
                                    "nonwake-low",
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
            "nonwake-low",
            selected
                ?.descriptor
                ?.routeId
                ?.value,
        )
    }

    @Test
    fun observationRejectsOneShotAndPrefersWakeUpRoute() {
        val selected =
            SensorManagerLogicalRouteSelector
                .selectRouteForObservation(
                    routes =
                        listOf(
                            route(
                                routeId =
                                    "one-shot",
                                logicalId =
                                    "accelerometer",
                                wakeUp =
                                    true,
                                power =
                                    0.01f,
                                reportingMode =
                                    Sensor.REPORTING_MODE_ONE_SHOT,
                            ),
                            route(
                                routeId =
                                    "continuous-nonwake",
                                logicalId =
                                    "accelerometer",
                                wakeUp =
                                    false,
                                power =
                                    0.05f,
                            ),
                            route(
                                routeId =
                                    "continuous-wake",
                                logicalId =
                                    "accelerometer",
                                wakeUp =
                                    true,
                                power =
                                    0.2f,
                            ),
                        ),
                    logicalId =
                        "accelerometer",
                    requestedPeriodUs =
                        100_000,
                )

        assertEquals(
            "continuous-wake",
            selected
                ?.descriptor
                ?.routeId
                ?.value,
        )
    }

    @Test
    fun observationPrefersCadenceCapableRouteBeforePower() {
        val selected =
            SensorManagerLogicalRouteSelector
                .selectRouteForObservation(
                    routes =
                        listOf(
                            route(
                                routeId =
                                    "too-slow",
                                logicalId =
                                    "accelerometer",
                                wakeUp =
                                    true,
                                power =
                                    0.01f,
                                minDelayUs =
                                    500_000,
                            ),
                            route(
                                routeId =
                                    "fast-enough",
                                logicalId =
                                    "accelerometer",
                                wakeUp =
                                    true,
                                power =
                                    0.5f,
                                minDelayUs =
                                    50_000,
                            ),
                        ),
                    logicalId =
                        "accelerometer",
                    requestedPeriodUs =
                        100_000,
                )

        assertEquals(
            "fast-enough",
            selected
                ?.descriptor
                ?.routeId
                ?.value,
        )
    }

    @Test
    fun observationChoosesClosestCadenceWhenEveryRouteIsTooSlow() {
        val selected =
            SensorManagerLogicalRouteSelector
                .selectRouteForObservation(
                    routes =
                        listOf(
                            route(
                                routeId =
                                    "very-slow-low-power",
                                logicalId =
                                    "accelerometer",
                                wakeUp =
                                    true,
                                power =
                                    0.01f,
                                minDelayUs =
                                    500_000,
                            ),
                            route(
                                routeId =
                                    "closest",
                                logicalId =
                                    "accelerometer",
                                wakeUp =
                                    true,
                                power =
                                    0.5f,
                                minDelayUs =
                                    50_000,
                            ),
                            route(
                                routeId =
                                    "middle",
                                logicalId =
                                    "accelerometer",
                                wakeUp =
                                    true,
                                power =
                                    0.1f,
                                minDelayUs =
                                    100_000,
                            ),
                        ),
                    logicalId =
                        "accelerometer",
                    requestedPeriodUs =
                        20_000,
                )

        assertEquals(
            "closest",
            selected
                ?.descriptor
                ?.routeId
                ?.value,
        )
    }

    @Test
    fun observationDoesNotCrossLogicalTargets() {
        val selected =
            SensorManagerLogicalRouteSelector
                .selectRouteForObservation(
                    routes =
                        listOf(
                            route(
                                routeId =
                                    "pressure",
                                logicalId =
                                    "pressure",
                                wakeUp =
                                    true,
                                power =
                                    0.1f,
                            ),
                        ),
                    logicalId =
                        "accelerometer",
                    requestedPeriodUs =
                        100_000,
                )

        assertNull(
            selected,
        )
    }

    @Test
    fun samplingPlanHonorsPhysicalMinimumDelay() {
        val plan =
            sensorManagerObservationSamplingPlan(
                preferredSamplePeriodMs =
                    20L,
                minDelayUs =
                    50_000,
            )

        assertEquals(
            20_000,
            plan.requestedPeriodUs,
        )

        assertEquals(
            50_000,
            plan.registrationPeriodUs,
        )

        assertEquals(
            50L,
            plan.configuredPeriodMs,
        )
    }

    @Test
    fun samplingPlanLeavesSlowerLogicalRequestUnchanged() {
        val plan =
            sensorManagerObservationSamplingPlan(
                preferredSamplePeriodMs =
                    1_000L,
                minDelayUs =
                    50_000,
            )

        assertEquals(
            1_000_000,
            plan.registrationPeriodUs,
        )

        assertEquals(
            1_000L,
            plan.configuredPeriodMs,
        )
    }

    @Test
    fun requestedPeriodConversionStaysInsideSensorManagerIntDomain() {
        val periodUs =
            sensorManagerRequestedPeriodUs(
                Long.MAX_VALUE,
            )

        assertEquals(
            (
                Int.MAX_VALUE /
                    1_000
            ) *
                1_000,
            periodUs,
        )
    }

    private fun route(
        routeId: String,
        logicalId: String,
        wakeUp: Boolean,
        power: Float,
        minDelayUs: Int = 1,
        reportingMode: Int =
            Sensor.REPORTING_MODE_CONTINUOUS,
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
                        minDelayUs,
                    maxDelayUs =
                        1_000_000,
                    fifoReservedEventCount =
                        0,
                    fifoMaxEventCount =
                        0,
                    reportingMode =
                        reportingMode,
                    wakeUp =
                        wakeUp,
                ),
        )
}
