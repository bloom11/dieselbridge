// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SensorManagerRouteCatalogTest {

    @Test
    fun duplicateLogicalFamilyKeepsConcreteRoutesDistinct() {
        val routes =
            catalog(
                sensor(
                    logicalId = "accelerometer",
                    androidType = 1,
                    androidId = 101,
                ),
                sensor(
                    logicalId = "accelerometer",
                    androidType = 1,
                    androidId = 102,
                ),
            ).snapshot()

        assertEquals(
            2,
            routes.size,
        )

        assertEquals(
            SensorLogicalId(
                "accelerometer",
            ),
            routes[0]
                .descriptor
                .logicalId,
        )

        assertEquals(
            routes[0]
                .descriptor
                .logicalId,
            routes[1]
                .descriptor
                .logicalId,
        )

        assertNotEquals(
            routes[0]
                .descriptor
                .routeId,
            routes[1]
                .descriptor
                .routeId,
        )

        assertEquals(
            SensorManagerRouteCatalog
                .PROVIDER_ID,
            routes[0]
                .descriptor
                .providerId,
        )
    }

    @Test
    fun duplicateAndroidIdentityGetsExplicitOrdinal() {
        val routes =
            catalog(
                sensor(
                    logicalId =
                        "android_type_33171103",
                    androidType =
                        33171103,
                    androidId = 7,
                    name = "first",
                ),
                sensor(
                    logicalId =
                        "android_type_33171103",
                    androidType =
                        33171103,
                    androidId = 7,
                    name = "second",
                ),
            ).snapshot()

        assertEquals(
            SensorRouteId(
                "android.sensor_manager:33171103:7:0",
            ),
            routes[0]
                .descriptor
                .routeId,
        )

        assertEquals(
            SensorRouteId(
                "android.sensor_manager:33171103:7:1",
            ),
            routes[1]
                .descriptor
                .routeId,
        )
    }

    @Test
    fun unchangedSnapshotProducesDeterministicRouteIds() {
        val entries =
            listOf(
                sensor(
                    logicalId = "pressure",
                    androidType = 6,
                    androidId = 42,
                ),
                sensor(
                    logicalId = "heart_rate",
                    androidType = 21,
                    androidId = 43,
                ),
            )

        val catalog =
            SensorManagerRouteCatalog(
                SensorInventory {
                    entries
                },
            )

        val first =
            catalog
                .snapshot()
                .map {
                    it.descriptor.routeId
                }

        val second =
            catalog
                .snapshot()
                .map {
                    it.descriptor.routeId
                }

        assertEquals(
            first,
            second,
        )
    }

    @Test
    fun genericDescriptorContainsNoAndroidInventory() {
        val descriptor =
            SensorRouteDescriptor(
                routeId =
                    SensorRouteId(
                        "health_services:heart_rate",
                    ),
                logicalId =
                    SensorLogicalId(
                        "heart_rate",
                    ),
                providerId =
                    "health_services",
            )

        assertEquals(
            "health_services",
            descriptor.providerId,
        )
    }

    private fun catalog(
        vararg entries:
            SensorInventoryEntry,
    ): AndroidSensorRouteCatalog =
        SensorManagerRouteCatalog(
            SensorInventory {
                entries.toList()
            },
        )

    private fun sensor(
        logicalId: String,
        androidType: Int,
        androidId: Int,
        name: String = logicalId,
    ): SensorInventoryEntry =
        SensorInventoryEntry(
            logicalId = logicalId,
            androidId = androidId,
            androidType = androidType,
            stringType =
                "test.sensor.$logicalId",
            name = name,
            vendor = "test",
            version = 1,
            maxRange = 1.0f,
            resolution = 0.1f,
            powerMilliAmps = 0.1f,
            minDelayUs = 1000,
            maxDelayUs = 1000000,
            fifoReservedEventCount = 0,
            fifoMaxEventCount = 0,
            reportingMode = 0,
            wakeUp = false,
        )
}
