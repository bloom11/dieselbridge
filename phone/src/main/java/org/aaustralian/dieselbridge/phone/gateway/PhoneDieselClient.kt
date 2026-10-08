// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import java.util.UUID

data class PhoneDieselDispatch(
    val request: PhoneDieselRequest,
    val gadgetbridgeLine: String,
    val submittedAtMs: Long,
)

/**
 * Request-only Diesel client for M6.0b.
 *
 * There is intentionally no pending request map, response receiver, timeout,
 * retry, reconnect or device-target logic here. Those semantics belong to
 * M6.0c/d after the one-way transport is physically proven.
 */
class PhoneDieselClient(
    private val transport:
        PhoneDieselTransport,
    private val requestIdFactory:
        () -> String = {
            "phone-${UUID.randomUUID()}"
        },
    private val clockMs:
        () -> Long =
            System::currentTimeMillis,
) {

    fun sendBuildInfo(
        requestId: String =
            requestIdFactory(),
    ): PhoneDieselDispatch {
        val request =
            PhoneDieselRequest(
                requestId =
                    requestId,
                command =
                    COMMAND_BUILD_INFO,
            )

        val line =
            PhoneDieselRequestCodec
                .encodeGadgetbridgeLine(
                    request,
                )

        transport.submit(
            line,
        )

        return PhoneDieselDispatch(
            request =
                request,
            gadgetbridgeLine =
                line,
            submittedAtMs =
                clockMs(),
        )
    }

    companion object {
        const val COMMAND_BUILD_INFO =
            "debug.build.info"
    }
}
