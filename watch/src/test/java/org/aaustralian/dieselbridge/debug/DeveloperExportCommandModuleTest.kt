// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlinx.coroutines.runBlocking

import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.protocol.DieselResponse
import org.aaustralian.dieselbridge.protocol.DieselResponseCodec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperExportCommandModuleTest {

    private class FakeAuthorization(
        var enabled: Boolean,
    ) : DeveloperRemoteAccessAuthorization {

        override fun isEnabled(): Boolean =
            enabled
    }

    private class CountingSnapshot(
        private val items:
            List<DieselValue.ObjectValue>,
    ) {
        var calls = 0

        fun read():
            DeveloperExportSnapshot {
            calls += 1

            return DeveloperExportSnapshot(
                source = "fake",
                items = items,
            )
        }
    }

    @Test
    fun statusPublishesLiveRegistrySections(): Unit = runBlocking {
        val exportRegistry =
            DeveloperExportRegistry()
                .apply {
                    register("alpha") {
                        DeveloperExportSnapshot(
                            source = "a",
                            items = emptyList(),
                        )
                    }

                    register("beta") {
                        DeveloperExportSnapshot(
                            source = "b",
                            items = emptyList(),
                        )
                    }
                }

        val registry =
            commandRegistry(
                enabled = false,
                exportRegistry =
                    exportRegistry,
            )

        val result =
            registry.dispatch(
                DieselRequest(
                    requestId = "status",
                    command = "debug.status",
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        val sections =
            result.data["sections"]
                as DieselValue.ListValue

        assertEquals(
            listOf(
                DieselValue.Text("alpha"),
                DieselValue.Text("beta"),
            ),
            sections.value,
        )

        assertEquals(
            DieselValue.Text(
                "byte_budget_offset_limit",
            ),
            result.data["pagination"],
        )
    }

    @Test
    fun disabledExportDoesNotTouchProvider(): Unit = runBlocking {
        val snapshot =
            CountingSnapshot(
                items =
                    listOf(
                        smallItem(0),
                    ),
            )

        val exportRegistry =
            DeveloperExportRegistry()
                .apply {
                    register(
                        "dynamic",
                        snapshot::read,
                    )
                }

        val registry =
            commandRegistry(
                enabled = false,
                exportRegistry =
                    exportRegistry,
            )

        val result =
            registry.dispatch(
                exportRequest(
                    section = "dynamic",
                ),
            )

        assertEquals(
            DieselResponseStatus.UNAVAILABLE,
            result.status,
        )

        assertEquals(
            0,
            snapshot.calls,
        )
    }

    @Test
    fun byteBudgetCanReturnMoreThanTwoCompactItems(): Unit = runBlocking {
        val items =
            List(20) {
                smallItem(
                    it,
                )
            }

        val exportRegistry =
            DeveloperExportRegistry()
                .apply {
                    register("dynamic") {
                        DeveloperExportSnapshot(
                            source = "fake",
                            items = items,
                        )
                    }
                }

        val registry =
            commandRegistry(
                enabled = true,
                exportRegistry =
                    exportRegistry,
            )

        val result =
            registry.dispatch(
                exportRequest(
                    section = "dynamic",
                    limit = 20,
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        val returned =
            (
                result.data["returned"]
                    as DieselValue.Integer
            )
                .value

        assertTrue(
            "Expected byte budget to admit more than two compact items",
            returned > 2,
        )

        assertEquals(
            DieselValue.Integer(20),
            result.data["requestedLimit"],
        )

        assertFitsResponseBoundary(
            result.data,
        )
    }

    @Test
    fun largeItemsAreAutomaticallyPagedBeforeBoundary(): Unit = runBlocking {
        val noisyText =
            "sensor-metadata-" +
                "x".repeat(300)

        val items =
            List(30) { index ->
                DieselValue.ObjectValue(
                    mapOf(
                        "index" to
                            DieselValue.Integer(
                                index.toLong(),
                            ),
                        "name" to
                            DieselValue.Text(
                                noisyText,
                            ),
                        "vendor" to
                            DieselValue.Text(
                                noisyText,
                            ),
                    ),
                )
            }

        val exportRegistry =
            DeveloperExportRegistry()
                .apply {
                    register("dynamic") {
                        DeveloperExportSnapshot(
                            source = "fake",
                            items = items,
                        )
                    }
                }

        val registry =
            commandRegistry(
                enabled = true,
                exportRegistry =
                    exportRegistry,
            )

        val result =
            registry.dispatch(
                exportRequest(
                    section = "dynamic",
                    limit = 30,
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        val returned =
            (
                result.data["returned"]
                    as DieselValue.Integer
            )
                .value

        assertTrue(
            returned > 0,
        )

        assertTrue(
            returned < 30,
        )

        assertEquals(
            DieselValue.Flag(true),
            result.data["hasMore"],
        )

        assertEquals(
            DieselValue.Integer(
                returned,
            ),
            result.data["nextOffset"],
        )

        assertFitsResponseBoundary(
            result.data,
        )
    }

    @Test
    fun secondPageUsesGenericNextOffset(): Unit = runBlocking {
        val items =
            List(10) {
                smallItem(
                    it,
                )
            }

        val exportRegistry =
            DeveloperExportRegistry()
                .apply {
                    register("dynamic") {
                        DeveloperExportSnapshot(
                            source = "fake",
                            items = items,
                        )
                    }
                }

        val registry =
            commandRegistry(
                enabled = true,
                exportRegistry =
                    exportRegistry,
            )

        val result =
            registry.dispatch(
                exportRequest(
                    section = "dynamic",
                    offset = 4,
                    limit = 3,
                ),
            )

        assertEquals(
            DieselResponseStatus.OK,
            result.status,
        )

        assertEquals(
            DieselValue.Integer(4),
            result.data["offset"],
        )

        assertEquals(
            DieselValue.Integer(3),
            result.data["returned"],
        )

        assertEquals(
            DieselValue.Integer(7),
            result.data["nextOffset"],
        )

        assertEquals(
            DieselValue.Flag(true),
            result.data["hasMore"],
        )
    }

    @Test
    fun invalidSectionOrRequestedLimitDoesNotSnapshot(): Unit = runBlocking {
        val snapshot =
            CountingSnapshot(
                items =
                    emptyList(),
            )

        val exportRegistry =
            DeveloperExportRegistry()
                .apply {
                    register(
                        "dynamic",
                        snapshot::read,
                    )
                }

        val registry =
            commandRegistry(
                enabled = true,
                exportRegistry =
                    exportRegistry,
            )

        val unknown =
            registry.dispatch(
                exportRequest(
                    section = "unknown",
                ),
            )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            unknown.status,
        )

        val oversized =
            registry.dispatch(
                exportRequest(
                    section = "dynamic",
                    limit =
                        DeveloperExportCommandModule
                            .MAX_REQUESTED_LIMIT +
                            1,
                ),
            )

        assertEquals(
            DieselResponseStatus.INVALID_REQUEST,
            oversized.status,
        )

        assertEquals(
            0,
            snapshot.calls,
        )
    }

    private fun commandRegistry(
        enabled: Boolean,
        exportRegistry:
            DeveloperExportRegistry,
    ): DieselCommandRegistry =
        DieselCommandRegistry()
            .apply {
                install(
                    DeveloperExportCommandModule(
                        authorization =
                            FakeAuthorization(
                                enabled,
                            ),
                        registry =
                            exportRegistry,
                    ),
                )
            }

    private fun exportRequest(
        section: String,
        offset: Int = 0,
        limit: Int =
            DeveloperExportCommandModule
                .DEFAULT_REQUESTED_LIMIT,
    ): DieselRequest =
        DieselRequest(
            requestId = "export-test",
            command = "debug.export",
            args =
                mapOf(
                    "section" to
                        DieselValue.Text(
                            section,
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

    private fun smallItem(
        index: Int,
    ): DieselValue.ObjectValue =
        DieselValue.ObjectValue(
            mapOf(
                "index" to
                    DieselValue.Integer(
                        index.toLong(),
                    ),
                "name" to
                    DieselValue.Text(
                        "item-$index",
                    ),
            ),
        )

    private fun assertFitsResponseBoundary(
        data: Map<String, DieselValue>,
    ) {
        val encoded =
            DieselResponseCodec
                .encodeResponseJson(
                    DieselResponse(
                        requestId =
                            "x".repeat(
                                DieselResponse
                                    .MAX_REQUEST_ID_LENGTH,
                            ),
                        command =
                            "debug.export",
                        status =
                            DieselResponseStatus.OK,
                        data =
                            data,
                    ),
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
    }
}
