// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselRequestTest {

    @Test
    fun genericArgumentsPreserveTypes() {
        val request =
            DieselRequest(
                requestId = "sensor-1",
                command = "sensor",
                name = "accelerometer",
                args =
                    mapOf(
                        "rateHz" to
                            DieselValue.Integer(
                                10L,
                            ),
                        "stream" to
                            DieselValue.Flag(
                                false,
                            ),
                    ),
            )

        assertEquals(
            DieselValue.Integer(10L),
            request.args["rateHz"],
        )

        assertEquals(
            DieselValue.Flag(false),
            request.args["stream"],
        )
    }

    @Test
    fun dottedCommandNamesAreValidForFutureNamespaces() {
        val request =
            DieselRequest(
                requestId = "display-1",
                command = "display.write",
                name = "secondary",
            )

        assertEquals(
            "display.write",
            request.command,
        )
    }

    @Test
    fun tooManyArgumentsAreRejected() {
        var rejected = false

        try {
            DieselRequest(
                requestId = "large-args",
                command = "sensor",
                args =
                    (0 until
                        DieselRequest
                            .MAX_ARGUMENT_FIELDS + 1)
                        .associate {
                            "k$it" to
                                DieselValue.Integer(
                                    it.toLong(),
                                )
                        },
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
    }
}
