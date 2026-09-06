// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselResponseTest {

    @Test
    fun correlatedResponsePreservesEnvelopeAndStructuredData() {
        val response =
            DieselResponse(
                requestId = "req-42",
                command = "test",
                name = "vibration",
                status = DieselResponseStatus.OK,
                data =
                    mapOf(
                        "provider" to
                            DieselValue.Text(
                                "legacy.vibration",
                            ),
                        "durationMs" to
                            DieselValue.Integer(
                                250L,
                            ),
                        "bounded" to
                            DieselValue.Flag(
                                true,
                            ),
                    ),
            )

        assertEquals(
            DieselResponse.PROTOCOL_VERSION,
            response.version,
        )

        assertEquals(
            "req-42",
            response.requestId,
        )

        assertEquals(
            "test",
            response.command,
        )

        assertEquals(
            "vibration",
            response.name,
        )

        assertEquals(
            DieselResponseStatus.OK,
            response.status,
        )

        assertEquals(
            DieselValue.Text(
                "legacy.vibration",
            ),
            response.data["provider"],
        )
    }

    @Test
    fun responseModelSupportsFutureStructuredCatalogData() {
        val response =
            DieselResponse(
                requestId = "catalog-1",
                command = "commands",
                status = DieselResponseStatus.OK,
                data =
                    mapOf(
                        "commands" to
                            DieselValue.ListValue(
                                listOf(
                                    DieselValue.ObjectValue(
                                        mapOf(
                                            "name" to
                                                DieselValue.Text(
                                                    "diagnostics",
                                                ),
                                            "effect" to
                                                DieselValue.Text(
                                                    "read_only",
                                                ),
                                        ),
                                    ),
                                    DieselValue.ObjectValue(
                                        mapOf(
                                            "name" to
                                                DieselValue.Text(
                                                    "test",
                                                ),
                                            "effect" to
                                                DieselValue.Text(
                                                    "safe_action",
                                                ),
                                        ),
                                    ),
                                ),
                            ),
                    ),
            )

        assertTrue(
            response.data["commands"]
                is DieselValue.ListValue,
        )
    }

    @Test
    fun statusWireNamesAreStable() {
        assertEquals(
            "ok",
            DieselResponseStatus.OK.wireName,
        )
        assertEquals(
            "unavailable",
            DieselResponseStatus.UNAVAILABLE.wireName,
        )
        assertEquals(
            "rate_limited",
            DieselResponseStatus.RATE_LIMITED.wireName,
        )
        assertEquals(
            "failed",
            DieselResponseStatus.FAILED.wireName,
        )
        assertEquals(
            "unknown_command",
            DieselResponseStatus.UNKNOWN_COMMAND.wireName,
        )
        assertEquals(
            "unknown_target",
            DieselResponseStatus.UNKNOWN_TARGET.wireName,
        )
        assertEquals(
            "invalid_request",
            DieselResponseStatus.INVALID_REQUEST.wireName,
        )
    }

    @Test
    fun uncorrelatedResponseIsAllowed() {
        val response =
            DieselResponse(
                requestId = null,
                command = "commands",
                status = DieselResponseStatus.OK,
            )

        assertEquals(
            null,
            response.requestId,
        )
    }

    @Test
    fun oversizedRequestIdIsRejected() {
        var rejected = false

        try {
            DieselResponse(
                requestId =
                    "x".repeat(
                        DieselResponse.MAX_REQUEST_ID_LENGTH + 1,
                    ),
                command = "commands",
                status = DieselResponseStatus.OK,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun blankRequestIdIsRejected() {
        var rejected = false

        try {
            DieselResponse(
                requestId = "   ",
                command = "commands",
                status = DieselResponseStatus.OK,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun invalidCommandNameIsRejected() {
        var rejected = false

        try {
            DieselResponse(
                requestId = "req-1",
                command = "shell command",
                status = DieselResponseStatus.OK,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }

    @Test
    fun nonFiniteDecimalIsRejected() {
        var rejected = false

        try {
            DieselValue.Decimal(
                Double.NaN,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }
}
