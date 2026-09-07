// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.sensor

import org.aaustralian.dieselbridge.platform.sensor.SensorManagerRouteCatalog
import org.aaustralian.dieselbridge.platform.sensor.SensorInventory
import org.aaustralian.dieselbridge.platform.sensor.SensorInventoryEntry
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.protocol.DieselResponse
import org.aaustralian.dieselbridge.protocol.DieselResponseCodec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorCommandModuleTest {

    @Test
    fun sensorListIsNormalDiscoverableCommand() {
        val registry =
            registryWith(
                entries =
                    listOf(
                        sensor(
                            logicalId =
                                "accelerometer",
                            androidType = 1,
                        ),
                    ),
            )

        assertTrue(
            SensorCommandModule.COMMAND_SENSOR_LIST in
                registry.commands(),
        )

        val spec =
            registry
                .specs()
                .single {
                    it.name ==
                        SensorCommandModule
                            .COMMAND_SENSOR_LIST
                }

        assertEquals(
            DieselValue.Text(
                "sensor",
            ),
            spec.metadata["domain"],
        )

        assertEquals(
            DieselValue.Text(
                "read_only",
            ),
            spec.metadata["effect"],
        )
    }

    @Test
    fun defaultPageIsBoundedToFourSensors() {
        val registry =
            registryWith(
                entries =
                    (0 until 5)
                        .map { index ->
                            sensor(
                                logicalId =
                                    "sensor_$index",
                                androidType =
                                    100 + index,
                            )
                        },
            )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "list-1",
                    command =
                        SensorCommandModule
                            .COMMAND_SENSOR_LIST,
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        assertEquals(
            DieselValue.Integer(5L),
            result.data["total"],
        )

        assertEquals(
            DieselValue.Integer(4L),
            result.data["returned"],
        )

        assertEquals(
            DieselValue.Flag(true),
            result.data["hasMore"],
        )

        val sensors =
            (
                result.data["sensors"] as
                    DieselValue.ListValue
            ).value

        assertEquals(
            4,
            sensors.size,
        )

        val first =
            (
                sensors.first() as
                    DieselValue.ObjectValue
            ).value

        assertEquals(
            DieselValue.Integer(0L),
            first["index"],
        )

        assertEquals(
            DieselValue.Text(
                "sensor_0",
            ),
            first["id"],
        )
    }

    @Test
    fun explicitPageUsesOffsetAndLimit() {
        val registry =
            registryWith(
                entries =
                    (0 until 5)
                        .map { index ->
                            sensor(
                                logicalId =
                                    "sensor_$index",
                                androidType =
                                    100 + index,
                            )
                        },
            )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "list-2",
                    command =
                        SensorCommandModule
                            .COMMAND_SENSOR_LIST,
                    args =
                        mapOf(
                            "offset" to
                                DieselValue.Integer(
                                    4L,
                                ),
                            "limit" to
                                DieselValue.Integer(
                                    1L,
                                ),
                        ),
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        assertEquals(
            DieselValue.Integer(1L),
            result.data["returned"],
        )

        assertEquals(
            DieselValue.Flag(false),
            result.data["hasMore"],
        )

        val sensors =
            (
                result.data["sensors"] as
                    DieselValue.ListValue
            ).value

        val item =
            (
                sensors.single() as
                    DieselValue.ObjectValue
            ).value

        assertEquals(
            DieselValue.Integer(4L),
            item["index"],
        )

        assertEquals(
            DieselValue.Text(
                "sensor_4",
            ),
            item["id"],
        )
    }

    @Test
    fun oversizedPageRequestIsRejected() {
        val registry =
            registryWith(
                entries =
                    emptyList(),
            )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "list-3",
                    command =
                        SensorCommandModule
                            .COMMAND_SENSOR_LIST,
                    args =
                        mapOf(
                            "limit" to
                                DieselValue.Integer(
                                    5L,
                                ),
                        ),
                ),
            )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            result.status,
        )

        assertEquals(
            DieselValue.Text(
                "invalid_args",
            ),
            result.data["reason"],
        )
    }

    @Test
    fun unknownArgumentIsRejected() {
        val registry =
            registryWith(
                entries =
                    emptyList(),
            )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "list-4",
                    command =
                        SensorCommandModule
                            .COMMAND_SENSOR_LIST,
                    args =
                        mapOf(
                            "continuous" to
                                DieselValue.Flag(
                                    true,
                                ),
                        ),
                ),
            )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            result.status,
        )
    }

    @Test
    fun sensorListNeverNeedsTargetName() {
        val registry =
            registryWith(
                entries =
                    emptyList(),
            )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "list-5",
                    command =
                        SensorCommandModule
                            .COMMAND_SENSOR_LIST,
                    name =
                        "accelerometer",
                ),
            )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            result.status,
        )
    }

    @Test
    fun compactInventoryPreservesAndroidIdentity() {
        val registry =
            registryWith(
                entries =
                    listOf(
                        sensor(
                            logicalId =
                                "heart_rate",
                            androidType = 21,
                            stringType =
                                "android.sensor.heart_rate",
                            name =
                                "Wrist Heart Rate",
                            vendor =
                                "Example Vendor",
                            wakeUp =
                                true,
                            reportingMode =
                                1,
                        ),
                    ),
            )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "list-6",
                    command =
                        SensorCommandModule
                            .COMMAND_SENSOR_LIST,
                ),
            )

        val sensors =
            (
                result.data["sensors"] as
                    DieselValue.ListValue
            ).value

        val item =
            (
                sensors.single() as
                    DieselValue.ObjectValue
            ).value

        assertEquals(
            DieselValue.Text(
                "heart_rate",
            ),
            item["id"],
        )

        assertEquals(
            DieselValue.Text(
                "android.sensor_manager:21:21:0",
            ),
            item["routeId"],
        )

        assertEquals(
            DieselValue.Text(
                "android.sensor_manager",
            ),
            item["providerId"],
        )

        assertEquals(
            DieselValue.Integer(21L),
            item["androidId"],
        )

        assertEquals(
            DieselValue.Integer(21L),
            item["androidType"],
        )

        assertEquals(
            DieselValue.Text(
                "android.sensor.heart_rate",
            ),
            item["stringType"],
        )

        assertEquals(
            DieselValue.Flag(true),
            item["wakeUp"],
        )

        assertEquals(
            DieselValue.Integer(1L),
            item["reportingMode"],
        )
    }

    @Test
    fun duplicateLogicalFamiliesRemainDistinguishable() {
        val registry =
            registryWith(
                entries =
                    listOf(
                        sensor(
                            logicalId =
                                "accelerometer",
                            androidId = 101,
                            androidType = 1,
                        ),
                        sensor(
                            logicalId =
                                "accelerometer",
                            androidId = 102,
                            androidType = 1,
                            wakeUp = true,
                        ),
                    ),
            )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "list-7",
                    command =
                        SensorCommandModule
                            .COMMAND_SENSOR_LIST,
                ),
            )

        val sensors =
            (
                result.data["sensors"] as
                    DieselValue.ListValue
            ).value

        assertEquals(
            2,
            sensors.size,
        )

        val first =
            (
                sensors[0] as
                    DieselValue.ObjectValue
            ).value

        val second =
            (
                sensors[1] as
                    DieselValue.ObjectValue
            ).value

        assertEquals(
            DieselValue.Text(
                "accelerometer",
            ),
            first["id"],
        )

        assertEquals(
            DieselValue.Text(
                "accelerometer",
            ),
            second["id"],
        )

        assertEquals(
            DieselValue.Integer(101L),
            first["androidId"],
        )

        assertEquals(
            DieselValue.Integer(102L),
            second["androidId"],
        )
    }

    @Test
    fun maximumCompactPageFitsDieselResponseBoundary() {
        val largeMetadata =
            buildString {
                repeat(
                    200,
                ) {
                    appendCodePoint(
                        0x1F600,
                    )
                }

                repeat(
                    200,
                ) {
                    append(
                        '\u0001',
                    )
                }
            }

        val registry =
            registryWith(
                entries =
                    (0 until
                        SensorCommandModule.MAX_PAGE_SIZE)
                        .map { index ->
                            sensor(
                                logicalId =
                                    "sensor_$index",
                                androidId =
                                    300 + index,
                                androidType =
                                    400 + index,
                                stringType =
                                    largeMetadata,
                                name =
                                    largeMetadata,
                                vendor =
                                    largeMetadata,
                            )
                        },
            )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "list-8",
                    command =
                        SensorCommandModule
                            .COMMAND_SENSOR_LIST,
                    args =
                        mapOf(
                            "limit" to
                                DieselValue.Integer(
                                    SensorCommandModule
                                        .MAX_PAGE_SIZE
                                        .toLong(),
                                ),
                        ),
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        val encoded =
            DieselResponseCodec
                .encodeResponseJson(
                    DieselResponse(
                        requestId = "list-8",
                        command =
                            SensorCommandModule
                                .COMMAND_SENSOR_LIST,
                        status =
                            result.status,
                        data =
                            result.data,
                    ),
                )

        assertTrue(
            encoded
                .toByteArray(
                    Charsets.UTF_8,
                )
                .size <=
                DieselResponseCodec
                    .MAX_RESPONSE_JSON_BYTES,
        )
    }

    private fun registryWith(
        entries: List<SensorInventoryEntry>,
    ): DieselCommandRegistry {
        val registry =
            DieselCommandRegistry()

        registry.install(
            SensorCommandModule(
                routes =
                    SensorManagerRouteCatalog(
                        SensorInventory {
                            entries
                        },
                    ),
            ),
        )

        return registry
    }

    private fun sensor(
        logicalId: String,
        androidType: Int,
        androidId: Int =
            androidType,
        stringType: String =
            "android.sensor.$logicalId",
        name: String =
            logicalId,
        vendor: String =
            "test",
        wakeUp: Boolean =
            false,
        reportingMode: Int =
            0,
    ): SensorInventoryEntry =
        SensorInventoryEntry(
            logicalId = logicalId,
            androidId = androidId,
            androidType = androidType,
            stringType = stringType,
            name = name,
            vendor = vendor,
            version = 1,
            maxRange = 1.0f,
            resolution = 0.1f,
            powerMilliAmps = 0.1f,
            minDelayUs = 1000,
            maxDelayUs = 1000000,
            fifoReservedEventCount = 0,
            fifoMaxEventCount = 0,
            reportingMode = reportingMode,
            wakeUp = wakeUp,
        )
}
