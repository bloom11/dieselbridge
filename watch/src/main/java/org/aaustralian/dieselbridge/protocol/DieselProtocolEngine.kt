// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

import kotlin.coroutines.cancellation.CancellationException

/**
 * Result of one protocol dispatch.
 *
 * Transport delivery is tracked separately from command status: a command may
 * succeed even if the response cannot currently be delivered.
 */
data class DieselProtocolDispatch(
    val request: DieselRequest,
    val response: DieselResponse,
    val sent: Boolean,
    val handlerError: Exception? = null,
    val transportError: Exception? = null,
)

/**
 * Result of rejecting an inbound request before command dispatch.
 *
 * Invalid requests use the same response transport as valid requests but
 * never enter the command registry.
 */
data class DieselInvalidProtocolDispatch(
    val failure: DieselInvalidRequest,
    val response: DieselResponse,
    val sent: Boolean,
    val transportError: Exception? = null,
)

/**
 * Generic Diesel request/response engine.
 *
 * It owns correlation and response-envelope construction exactly once.
 * Neither command modules nor transports need command-specific response code.
 */
class DieselProtocolEngine(
    private val commands: DieselCommandRegistry,
    private val responses: DieselResponseTransport,
    private val onDispatch:
        (DieselProtocolDispatch) -> Unit = {},
    private val onInvalidDispatch:
        (DieselInvalidProtocolDispatch) -> Unit = {},
) {

    suspend fun handle(
        request: DieselRequest,
    ): DieselProtocolDispatch {
        var handlerError: Exception? = null

        val result =
            try {
                commands.dispatch(
                    request,
                )
            } catch (error: CancellationException) {
                /*
                 * Cancellation is lifecycle/control flow, not a command
                 * failure. Propagate it so a cancelled service or future
                 * sensor probe cannot emit a stale FAILED response.
                 */
                throw error
            } catch (error: Exception) {
                handlerError = error

                DieselCommandResult(
                    status =
                        DieselResponseStatus.FAILED,
                )
            }

        val response =
            DieselResponse(
                requestId = request.requestId,
                command = request.command,
                name = request.name,
                status = result.status,
                data = result.data,
                version = request.version,
            )

        var transportError: Exception? = null

        val sent =
            try {
                responses.send(
                    response,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                transportError = error
                false
            }

        val dispatch =
            DieselProtocolDispatch(
                request = request,
                response = response,
                sent = sent,
                handlerError = handlerError,
                transportError = transportError,
            )

        try {
            onDispatch(
                dispatch,
            )
        } catch (_: Exception) {
            /*
             * Observability callbacks must never change command execution or
             * response-delivery semantics.
             */
        }

        return dispatch
    }

    /**
     * Reject a request that failed before command dispatch.
     *
     * Only sanitized correlation fields are reflected remotely. The detailed
     * parser diagnostic stays local in DieselInvalidRequest.detail.
     */
    fun handleInvalid(
        failure: DieselInvalidRequest,
    ): DieselInvalidProtocolDispatch {
        val response =
            DieselResponse(
                requestId =
                    failure.requestId,
                command =
                    failure.command
                        ?: PROTOCOL_ERROR_COMMAND,
                name =
                    failure.name,
                status =
                    DieselResponseStatus.INVALID_REQUEST,
                data =
                    mapOf(
                        "reason" to
                            DieselValue.Text(
                                failure.reason.wireName,
                            ),
                    ),
            )

        var transportError: Exception? =
            null

        val sent =
            try {
                responses.send(
                    response,
                )
            } catch (error: Exception) {
                transportError =
                    error

                false
            }

        val dispatch =
            DieselInvalidProtocolDispatch(
                failure = failure,
                response = response,
                sent = sent,
                transportError =
                    transportError,
            )

        try {
            onInvalidDispatch(
                dispatch,
            )
        } catch (_: Exception) {
            /*
             * Observability callbacks must never alter protocol semantics.
             */
        }

        return dispatch
    }

    companion object {
        /**
         * Reserved response command used when an invalid request had no valid
         * command identifier that can safely be echoed.
         */
        const val PROTOCOL_ERROR_COMMAND =
            "protocol"
    }

}
