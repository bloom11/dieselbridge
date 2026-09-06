// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

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
) {

    fun handle(
        request: DieselRequest,
    ): DieselProtocolDispatch {
        var handlerError: Exception? = null

        val result =
            try {
                commands.dispatch(
                    request,
                )
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
}
