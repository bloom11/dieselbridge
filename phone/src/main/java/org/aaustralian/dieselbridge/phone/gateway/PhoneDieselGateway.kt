// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

sealed interface PhoneDieselGatewayResult {
    data class Success(
        val response: PhoneDieselResponse,
    ) : PhoneDieselGatewayResult
    data class Timeout(
        val requestId: String,
    ) : PhoneDieselGatewayResult
    data class Rejected(
        val requestId: String,
        val reason: String,
    ) : PhoneDieselGatewayResult
    data class SubmissionFailed(
        val requestId: String,
        val errorType: String,
        val message: String?,
    ) : PhoneDieselGatewayResult
    data class Closed(
        val requestId: String,
    ) : PhoneDieselGatewayResult
}

interface PhoneDieselExecutor {
    suspend fun execute(
        command: String,
        name: String? = null,
        args: Map<String, PhoneDieselValue> = emptyMap(),
        timeoutMs: Long = PhoneDieselGateway.DEFAULT_TIMEOUT_MS,
    ): PhoneDieselGatewayResult
}

class PhoneDieselGateway(
    private val transport: PhoneDieselTransport,
    private val responses: PhoneDieselResponseRouter,
    private val requestIdFactory: () -> String =
        defaultRequestIdFactory(),
) : PhoneDieselExecutor {

    override suspend fun execute(
        command: String,
        name: String?,
        args: Map<String, PhoneDieselValue>,
        timeoutMs: Long,
    ): PhoneDieselGatewayResult {
        require(timeoutMs in 1L..MAX_TIMEOUT_MS)
        val request =
            PhoneDieselRequest(
                requestId = requestIdFactory(),
                command = command,
                name = name,
                args = args,
            )
        val registration = responses.register(request)
        if (registration is PhoneDieselRegistrationResult.Rejected) {
            return if (registration.reason == "closed") {
                PhoneDieselGatewayResult.Closed(request.requestId)
            } else {
                PhoneDieselGatewayResult.Rejected(
                    requestId = request.requestId,
                    reason = registration.reason,
                )
            }
        }
        registration as PhoneDieselRegistrationResult.Registered

        try {
            transport.submit(
                PhoneDieselRequestCodec.encodeGadgetbridgeLine(request),
            )
        } catch (error: Exception) {
            responses.cancel(request.requestId, registration.token)
            return PhoneDieselGatewayResult.SubmissionFailed(
                requestId = request.requestId,
                errorType = error.javaClass.name,
                message = error.message,
            )
        }

        val completion =
            try {
                withTimeoutOrNull(timeoutMs) {
                    registration.completion.await()
                }
            } catch (cancellation: CancellationException) {
                responses.cancel(request.requestId, registration.token)
                throw cancellation
            }

        if (completion == null) {
            responses.cancel(request.requestId, registration.token)
            return PhoneDieselGatewayResult.Timeout(request.requestId)
        }

        return when (completion) {
            is PhoneDieselPendingCompletion.Response ->
                PhoneDieselGatewayResult.Success(completion.response)
            PhoneDieselPendingCompletion.Closed ->
                PhoneDieselGatewayResult.Closed(request.requestId)
        }
    }

    suspend fun buildInfo(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): PhoneDieselGatewayResult =
        execute(
            command = COMMAND_BUILD_INFO,
            timeoutMs = timeoutMs,
        )

    fun close() {
        responses.close()
    }

    companion object {
        const val COMMAND_BUILD_INFO = "debug.build.info"
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 20_000L

        private fun defaultRequestIdFactory(): () -> String {
            val session =
                UUID.randomUUID().toString().replace("-", "").take(8)
            return {
                val nonce =
                    UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(24)
                "phone-$session-$nonce"
            }
        }
    }
}
