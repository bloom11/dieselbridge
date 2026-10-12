// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.api

import kotlin.coroutines.cancellation.CancellationException
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselExecutor
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselGateway
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselGatewayResult
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselProtocolRules
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselValue
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselValueCodec

data class PhoneDieselPublicApiVersion(
    val major: Int,
    val minor: Int,
) {
    companion object {
        val CURRENT =
            PhoneDieselPublicApiVersion(
                major = 1,
                minor = 0,
            )
    }
}

data class PhoneDieselPublicRequest(
    val command: String,
    val name: String? = null,
    val args: Map<String, PhoneDieselValue> = emptyMap(),
    val timeoutMs: Long = PhoneDieselGateway.DEFAULT_TIMEOUT_MS,
)

sealed interface PhoneDieselPublicResult {
    data class Success(
        val command: String,
        val name: String?,
        val remoteStatus: String,
        val data: Map<String, PhoneDieselValue>,
    ) : PhoneDieselPublicResult

    data class Invalid(
        val reason: String,
    ) : PhoneDieselPublicResult

    data class Denied(
        val reason: String,
    ) : PhoneDieselPublicResult

    data class Failed(
        val reason: String,
    ) : PhoneDieselPublicResult
}

class PhoneDieselPublicAccessPolicy(
    allowedCommands: Set<String> =
        DEFAULT_ALLOWED_COMMANDS,
) {
    private val allowed =
        allowedCommands
            .also { commands ->
                require(
                    commands.all(
                        PhoneDieselProtocolRules::isValidIdentifier,
                    ),
                )
            }
            .toSortedSet()

    fun canExecute(
        command: String,
    ): Boolean =
        command in allowed

    fun commands():
        List<String> =
        allowed.toList()

    companion object {
        val DEFAULT_ALLOWED_COMMANDS =
            setOf(
                "commands",
                "debug.build.info",
                "companion.alarm.next.get",
            )
    }
}

class PhoneDieselPublicApi(
    private val executor: PhoneDieselExecutor,
    private val accessPolicy:
        PhoneDieselPublicAccessPolicy =
        PhoneDieselPublicAccessPolicy(),
) {
    val version:
        PhoneDieselPublicApiVersion =
        PhoneDieselPublicApiVersion.CURRENT

    fun allowedCommands():
        List<String> =
        accessPolicy.commands()

    suspend fun execute(
        request: PhoneDieselPublicRequest,
    ): PhoneDieselPublicResult {
        validate(
            request,
        )?.let {
            return it
        }

        if (
            !accessPolicy.canExecute(
                request.command,
            )
        ) {
            return PhoneDieselPublicResult.Denied(
                "command_not_allowed",
            )
        }

        val result =
            try {
                executor.execute(
                    command =
                        request.command,
                    name =
                        request.name,
                    args =
                        request.args,
                    timeoutMs =
                        request.timeoutMs,
                )
            } catch (
                cancellation:
                    CancellationException,
            ) {
                throw cancellation
            } catch (_: Exception) {
                return PhoneDieselPublicResult.Failed(
                    "gateway_failed",
                )
            }

        return mapResult(
            result,
        )
    }

    private fun validate(
        request: PhoneDieselPublicRequest,
    ): PhoneDieselPublicResult.Invalid? {
        if (
            !PhoneDieselProtocolRules.isValidIdentifier(
                request.command,
            )
        ) {
            return PhoneDieselPublicResult.Invalid(
                "invalid_command",
            )
        }

        if (
            request.name != null &&
            !PhoneDieselProtocolRules.isValidIdentifier(
                request.name,
            )
        ) {
            return PhoneDieselPublicResult.Invalid(
                "invalid_name",
            )
        }

        if (
            request.args.size >
            PhoneDieselProtocolRules.MAX_TOP_LEVEL_FIELDS
        ) {
            return PhoneDieselPublicResult.Invalid(
                "too_many_arguments",
            )
        }

        if (
            request.timeoutMs !in
            1L..
            PhoneDieselGateway.MAX_TIMEOUT_MS
        ) {
            return PhoneDieselPublicResult.Invalid(
                "timeout_out_of_bounds",
            )
        }

        try {
            PhoneDieselValueCodec.encodeObject(
                request.args,
            )
        } catch (_: Exception) {
            return PhoneDieselPublicResult.Invalid(
                "invalid_arguments",
            )
        }

        return null
    }

    private fun mapResult(
        result: PhoneDieselGatewayResult,
    ): PhoneDieselPublicResult =
        when (result) {
            is PhoneDieselGatewayResult.Success ->
                PhoneDieselPublicResult.Success(
                    command =
                        result.response.command,
                    name =
                        result.response.name,
                    remoteStatus =
                        result.response.status.wireName,
                    data =
                        result.response.data.toMap(),
                )

            is PhoneDieselGatewayResult.Timeout ->
                PhoneDieselPublicResult.Failed(
                    "timeout",
                )

            is PhoneDieselGatewayResult.Rejected ->
                PhoneDieselPublicResult.Failed(
                    when (
                        result.reason
                    ) {
                        "too_many_pending" ->
                            "gateway_busy"

                        else ->
                            "gateway_rejected"
                    },
                )

            is PhoneDieselGatewayResult.SubmissionFailed ->
                PhoneDieselPublicResult.Failed(
                    "submission_failed",
                )

            is PhoneDieselGatewayResult.Closed ->
                PhoneDieselPublicResult.Failed(
                    "runtime_closed",
                )
        }
}
