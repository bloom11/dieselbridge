// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GadgetbridgeDieselRequestAdapterTest {

    @Test
    fun legacyWrappedDieselCommandBecomesGenericRequest() {
        val message =
            GbProtocol.parseLine(
                """
                GB({"t":"diesel","id":"legacy-1","cmd":"test","name":"vibration"})
                """.trimIndent(),
            )

        assertTrue(
            message is
                GbMessage.DieselRequestMessage,
        )

        val request =
            (
                message as
                    GbMessage.DieselRequestMessage
            ).request

        assertEquals(
            "legacy-1",
            request.requestId,
        )

        assertEquals(
            "test",
            request.command,
        )

        assertEquals(
            "vibration",
            request.name,
        )

        assertEquals(
            DieselProtocolRules.PROTOCOL_VERSION,
            request.version,
        )

        assertTrue(
            request.args.isEmpty(),
        )
    }

    @Test
    fun fullGenericArgumentsSurviveGadgetbridgeAdapter() {
        val message =
            GbProtocol.parseLine(
                """
                GB({
                  "t":"diesel",
                  "v":1,
                  "kind":"request",
                  "id":"r42",
                  "cmd":"sensor.read",
                  "name":"accelerometer",
                  "args":{
                    "rateHz":25,
                    "raw":true,
                    "options":{
                      "frame":"device"
                    }
                  }
                })
                """.trimIndent(),
            ) as GbMessage.DieselRequestMessage

        val request =
            message.request

        assertEquals(
            "r42",
            request.requestId,
        )

        assertEquals(
            "sensor.read",
            request.command,
        )

        assertEquals(
            "accelerometer",
            request.name,
        )

        assertEquals(
            DieselValue.Integer(25L),
            request.args["rateHz"],
        )

        assertEquals(
            DieselValue.Flag(true),
            request.args["raw"],
        )

        val options =
            request.args["options"]
                as DieselValue.ObjectValue

        assertEquals(
            DieselValue.Text("device"),
            options.value["frame"],
        )
    }

    @Test
    fun invalidVersionBecomesExplicitInvalidDieselMessage() {
        val message =
            GbProtocol.parseLine(
                """
                GB({
                  "t":"diesel",
                  "v":2,
                  "id":"r42",
                  "cmd":"commands"
                })
                """.trimIndent(),
            )

        assertTrue(
            message is
                GbMessage.InvalidDieselRequestMessage,
        )

        val failure =
            (
                message as
                    GbMessage.InvalidDieselRequestMessage
            ).failure

        assertEquals(
            DieselRequestFailureReason.UNSUPPORTED_VERSION,
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
    fun invalidCommandIsNotReflectedByGadgetbridgeAdapter() {
        val message =
            GbProtocol.parseLine(
                """
                GB({
                  "t":"diesel",
                  "id":"r43",
                  "cmd":"INVALID COMMAND"
                })
                """.trimIndent(),
            ) as GbMessage.InvalidDieselRequestMessage

        assertEquals(
            DieselRequestFailureReason.INVALID_COMMAND,
            message.failure.reason,
        )

        assertEquals(
            "r43",
            message.failure.requestId,
        )

        assertNull(
            message.failure.command,
        )
    }

    @Test
    fun decodedRequestCanEnterEngineWithoutTransportTranslation() {
        var handlerRequest:
            DieselRequest? =
            null

        var response:
            DieselResponse? =
            null

        val registry =
            DieselCommandRegistry()

        registry.register(
            DieselCommandSpec(
                name = "sensor.read",
                summary =
                    "Generic integration test command",
            ),
        ) { context ->
            handlerRequest =
                context.request

            DieselCommandResult.ok(
                data =
                    mapOf(
                        "seenRateHz" to
                            (
                                context.args["rateHz"]
                                    ?: DieselValue.Null
                            ),
                    ),
            )
        }

        val engine =
            DieselProtocolEngine(
                commands = registry,
                responses =
                    DieselResponseTransport {
                            value,
                        ->
                        response =
                            value

                        true
                    },
            )

        val message =
            GbProtocol.parseLine(
                """
                GB({
                  "t":"diesel",
                  "v":1,
                  "kind":"request",
                  "id":"integration-1",
                  "cmd":"sensor.read",
                  "name":"accelerometer",
                  "args":{
                    "rateHz":25,
                    "raw":true
                  }
                })
                """.trimIndent(),
            ) as GbMessage.DieselRequestMessage

        engine.handle(
            message.request,
        )

        assertEquals(
            message.request,
            handlerRequest,
        )

        assertEquals(
            DieselValue.Integer(25L),
            handlerRequest
                ?.args
                ?.get("rateHz"),
        )

        assertEquals(
            "integration-1",
            response?.requestId,
        )

        assertEquals(
            "sensor.read",
            response?.command,
        )

        assertEquals(
            DieselResponseStatus.OK,
            response?.status,
        )

        assertEquals(
            DieselValue.Integer(25L),
            response
                ?.data
                ?.get("seenRateHz"),
        )
    }
}
