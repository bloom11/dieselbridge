// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

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

class DeveloperExportCommandModuleTest {

    private class FakeAuthorization(
        var enabled: Boolean,
    ) : DeveloperExportAuthorization {

        override fun isEnabled(): Boolean =
            enabled
    }

    private class CountingInventory(
        private val sensors:
            List<SensorInventoryEntry>,
    ) : SensorInventory {

        var calls = 0

        override fun snapshot():
            List<SensorInventoryEntry> {
            calls += 1

            return sensors
        }
    }

    @Test
    fun moduleRegistersStatusAndExportCommands() {
        val registry =
            registry(
                enabled = false,
            )

        assertEquals(
            listOf(
                "debug.status",
                "debug.export",
            ),
            registry.commands(),
        )
    }

    @Test
    fun statusIsAvailableWhileExportIsDisabled() {
        val authorization =
            FakeAuthorization(
                enabled = false,
            )

        val inventory =
            CountingInventory(
                emptyList(),
            )

        val registry =
            registry(
                authorization =
                    authorization,
                inventory =
                    inventory,
            )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "status-1",
                    command = "debug.status",
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        assertEquals(
            DieselValue.Flag(false),
            result.data["enabled"],
        )

        val sections =
            result.data["sections"]
                as DieselValue.ListValue

        assertEquals(
            listOf(
                DieselValue.Text("sensors"),
            ),
            sections.value,
        )

        assertEquals(
            0,
            inventory.calls,
        )
    }

    @Test
    fun disabledExportNeverTouchesInventory() {
        val inventory =
            CountingInventory(
                listOf(
                    sensor(
                        logicalId =
                            "accelerometer",
                        androidId = 11,
                        androidType = 1,
                    ),
                ),
            )

        val registry =
            registry(
                enabled = false,
                inventory =
                    inventory,
            )

        val result =
            registry.dispatch(
                exportRequest(),
            )

        assertEquals(
            DieselResponseStatus.UNAVAILABLE,
            result.status,
        )

        assertEquals(
            DieselValue.Text(
                "remote_export_disabled",
            ),
            result.data["reason"],
        )

        assertEquals(
            0,
            inventory.calls,
        )
    }

    @Test
    fun enabledSensorExportReturnsFullPaginatedMetadata() {
        val inventory =
            CountingInventory(
                listOf(
                    sensor(
                        logicalId =
                            "accelerometer",
                        androidId = 11,
                        androidType = 1,
                        wakeUp = false,
                    ),
                    sensor(
                        logicalId =
                            "accelerometer",
                        androidId = 12,
                        androidType = 1,
                        wakeUp = true,
                    ),
                    sensor(
                        logicalId =
                            "android_type_33171103",
                        androidId = 2031,
                        androidType = 33171103,
                        stringType =
                            "mobvoi_spo2",
                        name =
                            "psp_spo2 Wakeup",
                        vendor =
                            "psp",
                        wakeUp = true,
                    ),
                ),
            )

        val registry =
            registry(
                enabled = true,
                inventory =
                    inventory,
            )

        val result =
            registry.dispatch(
                exportRequest(
                    offset = 1,
                    limit = 2,
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        assertEquals(
            DieselValue.Integer(3),
            result.data["total"],
        )

        assertEquals(
            DieselValue.Integer(1),
            result.data["offset"],
        )

        assertEquals(
            DieselValue.Integer(2),
            result.data["returned"],
        )

        assertEquals(
            DieselValue.Flag(false),
            result.data["hasMore"],
        )

        val items =
            result.data["items"]
                as DieselValue.ListValue

        assertEquals(
            2,
            items.value.size,
        )

        val first =
            items.value.first()
                as DieselValue.ObjectValue

        assertEquals(
            DieselValue.Integer(12),
            first.value["androidId"],
        )

        assertEquals(
            DieselValue.Integer(1),
            first.value["androidType"],
        )

        assertEquals(
            DieselValue.Flag(true),
            first.value["wakeUp"],
        )

        assertTrue(
            first.value.containsKey(
                "maxRange",
            ),
        )

        assertTrue(
            first.value.containsKey(
                "fifoMaxEventCount",
            ),
        )

        assertEquals(
            1,
            inventory.calls,
        )
    }

    @Test
    fun invalidOrOversizedRequestIsRejectedBeforeSnapshot() {
        val inventory =
            CountingInventory(
                emptyList(),
            )

        val registry =
            registry(
                enabled = true,
                inventory =
                    inventory,
            )

        val oversized =
            registry.dispatch(
                exportRequest(
                    limit =
                        DeveloperExportCommandModule
                            .MAX_PAGE_SIZE +
                            1,
                ),
            )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            oversized.status,
        )

        val unknownSection =
            registry.dispatch(
                DieselRequest(
                    requestId = "export-unknown",
                    command = "debug.export",
                    args =
                        mapOf(
                            "section" to
                                DieselValue.Text(
                                    "not_real",
                                ),
                        ),
                ),
            )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            unknownSection.status,
        )

        assertEquals(
            0,
            inventory.calls,
        )
    }

    @Test
    fun maximumSensorExportPageFitsResponseBoundary() {
        val noisy =
            buildString {
                repeat(200) {
                    appendCodePoint(
                        0x1F680,
                    )
                    append(
                        '\u0001',
                    )
                }
            }

        val sensors =
            List(
                DeveloperExportCommandModule
                    .MAX_PAGE_SIZE,
            ) { index ->
                sensor(
                    logicalId = noisy,
                    androidId = index + 100,
                    androidType =
                        33171103 +
                            index,
                    stringType = noisy,
                    name = noisy,
                    vendor = noisy,
                    wakeUp =
                        index % 2 == 0,
                )
            }

        val registry =
            registry(
                enabled = true,
                inventory =
                    CountingInventory(
                        sensors,
                    ),
            )

        val result =
            registry.dispatch(
                exportRequest(
                    limit =
                        DeveloperExportCommandModule
                            .MAX_PAGE_SIZE,
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        val response =
            DieselResponse(
                requestId = "boundary",
                command = "debug.export",
                status = result.status,
                data = result.data,
            )

        val encoded =
            DieselResponseCodec
                .encodeResponseJson(
                    response,
                )

        val bytes =
            encoded
                .toByteArray(
                    Charsets.UTF_8,
                )
                .size

        assertTrue(
            "response was $bytes bytes",
            bytes <=
                DieselResponseCodec
                    .MAX_RESPONSE_JSON_BYTES,
        )

        val items =
            result.data["items"]
                as DieselValue.ListValue

        val first =
            items.value.first()
                as DieselValue.ObjectValue

        val compactName =
            (
                first.value["name"]
                    as DieselValue.Text
            )
                .value

        assertFalse(
            compactName.contains(
                '\u0001',
            ),
        )
    }

    private fun registry(
        enabled: Boolean,
        inventory: SensorInventory =
            CountingInventory(
                emptyList(),
            ),
    ): DieselCommandRegistry =
        registry(
            authorization =
                FakeAuthorization(
                    enabled,
                ),
            inventory =
                inventory,
        )

    private fun registry(
        authorization:
            DeveloperExportAuthorization,
        inventory:
            SensorInventory,
    ): DieselCommandRegistry =
        DieselCommandRegistry()
            .apply {
                install(
                    DeveloperExportCommandModule(
                        authorization =
                            authorization,
                        sensorInventory =
                            inventory,
                    ),
                )
            }

    private fun exportRequest(
        offset: Int = 0,
        limit: Int =
            DeveloperExportCommandModule
                .DEFAULT_PAGE_SIZE,
    ): DieselRequest =
        DieselRequest(
            requestId = "export-1",
            command = "debug.export",
            args =
                mapOf(
                    "section" to
                        DieselValue.Text(
                            "sensors",
                        ),
                    "offset" to
                        DieselValue.Integer(
                            offset.toLong(),
                        ),
                    "limit" to
                        DieselValue.Integer(
                            limit.toLong(),
                        ),
                ),
        )

    private fun sensor(
        logicalId: String,
        androidId: Int,
        androidType: Int,
        stringType: String =
            "android.sensor.accelerometer",
        name: String =
            "Test Sensor",
        vendor: String =
            "Test Vendor",
        wakeUp: Boolean = false,
    ): SensorInventoryEntry =
        SensorInventoryEntry(
            logicalId = logicalId,
            androidId = androidId,
            androidType = androidType,
            stringType = stringType,
            name = name,
            vendor = vendor,
            version = 7,
            maxRange = 123.5f,
            resolution = 0.25f,
            powerMilliAmps = 0.75f,
            minDelayUs = 1000,
            maxDelayUs = 1000000,
            fifoReservedEventCount = 4,
            fifoMaxEventCount = 64,
            reportingMode = 0,
            wakeUp = wakeUp,
        )
}
