// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselJsonRequestDecoderTest {

    @Test
    fun legacyRequestGetsV1Defaults() {
        val request =
            success(
                """
                {
                  "t":"diesel",
                  "cmd":"test",
                  "name":"vibration"
                }
                """.trimIndent(),
            )

        assertEquals(
            DieselProtocolRules.PROTOCOL_VERSION,
            request.version,
        )

        assertEquals(
            "test",
            request.command,
        )

        assertEquals(
            "vibration",
            request.name,
        )

        assertTrue(
            request.args.isEmpty(),
        )
    }

    @Test
    fun fullRequestPreservesGenericTypedArguments() {
        val request =
            success(
                """
                {
                  "t":"diesel",
                  "v":1,
                  "kind":"request",
                  "id":"r42",
                  "cmd":"sensor.read",
                  "name":"accelerometer",
                  "args":{
                    "rateHz":25,
                    "raw":true,
                    "ratio":2.5,
                    "optional":null,
                    "options":{
                      "frame":"device"
                    },
                    "samples":[1,2.5,null]
                  }
                }
                """.trimIndent(),
            )

        assertEquals(
            "r42",
            request.requestId,
        )

        assertEquals(
            DieselValue.Integer(25L),
            request.args["rateHz"],
        )

        assertEquals(
            DieselValue.Flag(true),
            request.args["raw"],
        )

        assertEquals(
            DieselValue.Decimal(2.5),
            request.args["ratio"],
        )

        assertEquals(
            DieselValue.Null,
            request.args["optional"],
        )

        val options =
            request.args["options"]
                as DieselValue.ObjectValue

        assertEquals(
            DieselValue.Text("device"),
            options.value["frame"],
        )

        val samples =
            request.args["samples"]
                as DieselValue.ListValue

        assertEquals(
            listOf(
                DieselValue.Integer(1L),
                DieselValue.Decimal(2.5),
                DieselValue.Null,
            ),
            samples.value,
        )
    }

    @Test
    fun directJsonTransportMayOmitGadgetbridgeType() {
        val request =
            success(
                """
                {
                  "v":1,
                  "kind":"request",
                  "id":"direct-1",
                  "cmd":"commands"
                }
                """.trimIndent(),
            )

        assertEquals(
            "commands",
            request.command,
        )
    }

    @Test
    fun unsupportedVersionIsRejected() {
        assertEquals(
            DieselRequestFailureReason.UNSUPPORTED_VERSION,
            invalid(
                """{"v":2,"cmd":"commands"}""",
            ).reason,
        )
    }

    @Test
    fun numericLookingStringVersionIsRejected() {
        assertEquals(
            DieselRequestFailureReason.UNSUPPORTED_VERSION,
            invalid(
                """{"v":"1","cmd":"commands"}""",
            ).reason,
        )
    }

    @Test
    fun decimalVersionIsRejected() {
        assertEquals(
            DieselRequestFailureReason.UNSUPPORTED_VERSION,
            invalid(
                """{"v":1.0,"cmd":"commands"}""",
            ).reason,
        )
    }

    @Test
    fun wrongMessageKindIsRejected() {
        assertEquals(
            DieselRequestFailureReason.INVALID_KIND,
            invalid(
                """
                {
                  "kind":"response",
                  "cmd":"commands"
                }
                """.trimIndent(),
            ).reason,
        )
    }

    @Test
    fun unknownTopLevelFieldIsRejectedButCorrelationIsKept() {
        val failure =
            invalid(
                """
                {
                  "id":"r42",
                  "cmd":"commands",
                  "typo":true
                }
                """.trimIndent(),
            )

        assertEquals(
            DieselRequestFailureReason.UNKNOWN_FIELD,
            failure.reason,
        )

        assertEquals(
            "r42",
            failure.requestId,
        )

        assertEquals(
            "commands",
            failure.command,
        )
    }

    @Test
    fun missingCommandIsRejected() {
        val failure =
            invalid(
                """{"id":"r42"}""",
            )

        assertEquals(
            DieselRequestFailureReason.MISSING_COMMAND,
            failure.reason,
        )

        assertEquals(
            "r42",
            failure.requestId,
        )

        assertNull(
            failure.command,
        )
    }

    @Test
    fun invalidCommandIsNeverReflected() {
        val failure =
            invalid(
                """
                {
                  "id":"r42",
                  "cmd":"INVALID COMMAND"
                }
                """.trimIndent(),
            )

        assertEquals(
            DieselRequestFailureReason.INVALID_COMMAND,
            failure.reason,
        )

        assertEquals(
            "r42",
            failure.requestId,
        )

        assertNull(
            failure.command,
        )
    }

    @Test
    fun invalidRequestIdIsNeverReflected() {
        val id =
            "x".repeat(
                DieselProtocolRules
                    .MAX_REQUEST_ID_LENGTH + 1,
            )

        val failure =
            invalid(
                JSONObject()
                    .put(
                        "id",
                        id,
                    )
                    .put(
                        "cmd",
                        "commands",
                    )
                    .toString(),
            )

        assertEquals(
            DieselRequestFailureReason.INVALID_REQUEST_ID,
            failure.reason,
        )

        assertNull(
            failure.requestId,
        )

        assertEquals(
            "commands",
            failure.command,
        )
    }

    @Test
    fun argsMustBeAnObject() {
        assertEquals(
            DieselRequestFailureReason.INVALID_ARGS,
            invalid(
                """
                {
                  "cmd":"commands",
                  "args":[]
                }
                """.trimIndent(),
            ).reason,
        )
    }

    @Test
    fun camelCaseArgumentNamesAreAllowed() {
        val request =
            success(
                """
                {
                  "cmd":"sensor.read",
                  "args":{
                    "rateHz":25
                  }
                }
                """.trimIndent(),
            )

        assertEquals(
            DieselValue.Integer(25L),
            request.args["rateHz"],
        )
    }

    @Test
    fun invalidArgumentFieldNameIsRejected() {
        assertEquals(
            DieselRequestFailureReason.INVALID_FIELD_NAME,
            invalid(
                """
                {
                  "cmd":"sensor.read",
                  "args":{
                    "RateHz":25
                  }
                }
                """.trimIndent(),
            ).reason,
        )
    }

    @Test
    fun tooManyTopLevelArgumentsAreRejected() {
        val args =
            JSONObject()

        repeat(
            DieselProtocolRules
                .MAX_TOP_LEVEL_FIELDS + 1,
        ) { index ->
            args.put(
                "k$index",
                index,
            )
        }

        val failure =
            invalid(
                JSONObject()
                    .put(
                        "cmd",
                        "sensor.read",
                    )
                    .put(
                        "args",
                        args,
                    )
                    .toString(),
            )

        assertEquals(
            DieselRequestFailureReason.TOO_MANY_VALUES,
            failure.reason,
        )
    }

    @Test
    fun excessiveNestingIsRejected() {
        var value: Any =
            1

        repeat(
            DieselProtocolRules
                .MAX_VALUE_DEPTH + 1,
        ) {
            value =
                JSONObject()
                    .put(
                        "child",
                        value,
                    )
        }

        val failure =
            invalid(
                JSONObject()
                    .put(
                        "cmd",
                        "sensor.read",
                    )
                    .put(
                        "args",
                        JSONObject()
                            .put(
                                "root",
                                value,
                            ),
                    )
                    .toString(),
            )

        assertEquals(
            DieselRequestFailureReason.STRUCTURE_TOO_DEEP,
            failure.reason,
        )
    }

    @Test
    fun totalNodeBudgetIsEnforced() {
        val args =
            JSONObject()

        repeat(4) { group ->
            val values =
                JSONArray()

            repeat(
                DieselProtocolRules
                    .MAX_COLLECTION_ENTRIES,
            ) { index ->
                values.put(index)
            }

            args.put(
                "group$group",
                values,
            )
        }

        val failure =
            invalid(
                JSONObject()
                    .put(
                        "cmd",
                        "sensor.read",
                    )
                    .put(
                        "args",
                        args,
                    )
                    .toString(),
            )

        assertEquals(
            DieselRequestFailureReason.TOO_MANY_VALUES,
            failure.reason,
        )
    }

    @Test
    fun oversizedTextValueIsRejected() {
        val value =
            "x".repeat(
                DieselProtocolRules
                    .MAX_TEXT_VALUE_BYTES + 1,
            )

        val failure =
            invalid(
                JSONObject()
                    .put(
                        "cmd",
                        "sensor.read",
                    )
                    .put(
                        "args",
                        JSONObject()
                            .put(
                                "text",
                                value,
                            ),
                    )
                    .toString(),
            )

        assertEquals(
            DieselRequestFailureReason.VALUE_TOO_LARGE,
            failure.reason,
        )
    }

    @Test
    fun oversizedJsonIsRejectedBeforeParsing() {
        val value =
            "x".repeat(
                DieselProtocolRules
                    .MAX_REQUEST_JSON_BYTES + 128,
            )

        val failure =
            invalid(
                """{"cmd":"commands","padding":"$value"}""",
            )

        assertEquals(
            DieselRequestFailureReason.PAYLOAD_TOO_LARGE,
            failure.reason,
        )

        assertNull(
            failure.requestId,
        )

        assertNull(
            failure.command,
        )
    }

    @Test
    fun malformedJsonIsRejectedWithoutCorrelation() {
        val failure =
            invalid(
                """{"id":"r42","cmd":""",
            )

        assertEquals(
            DieselRequestFailureReason.MALFORMED_JSON,
            failure.reason,
        )

        assertNull(
            failure.requestId,
        )

        assertNull(
            failure.command,
        )
    }

    private fun success(
        json: String,
    ): DieselRequest {
        val result =
            DieselJsonRequestDecoder
                .decode(
                    json,
                )

        assertTrue(
            "expected successful decode, got $result",
            result is
                DieselRequestDecodeResult.Success,
        )

        return (
            result as
                DieselRequestDecodeResult.Success
        ).request
    }

    private fun invalid(
        json: String,
    ): DieselInvalidRequest {
        val result =
            DieselJsonRequestDecoder
                .decode(
                    json,
                )

        assertTrue(
            "expected invalid decode, got $result",
            result is
                DieselRequestDecodeResult.Invalid,
        )

        return (
            result as
                DieselRequestDecodeResult.Invalid
        ).failure
    }
}
