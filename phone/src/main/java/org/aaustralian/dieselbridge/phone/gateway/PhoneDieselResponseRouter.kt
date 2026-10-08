// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

import kotlinx.coroutines.CompletableDeferred

sealed interface PhoneDieselPendingCompletion {
    data class Response(
        val response: PhoneDieselResponse,
    ) : PhoneDieselPendingCompletion
    data object Closed : PhoneDieselPendingCompletion
}

sealed interface PhoneDieselRegistrationResult {
    data class Registered(
        val token: Long,
        val completion:
            CompletableDeferred<PhoneDieselPendingCompletion>,
    ) : PhoneDieselRegistrationResult
    data class Rejected(
        val reason: String,
    ) : PhoneDieselRegistrationResult
}

enum class PhoneDieselRouteResult {
    DELIVERED,
    NO_REQUEST_ID,
    UNKNOWN_OR_LATE,
    METADATA_MISMATCH,
}

class PhoneDieselResponseRouter(
    private val maxPending: Int = DEFAULT_MAX_PENDING,
) {
    private data class Pending(
        val token: Long,
        val command: String,
        val name: String?,
        val completion:
            CompletableDeferred<PhoneDieselPendingCompletion>,
    )

    private val lock = Any()
    private val pending = linkedMapOf<String, Pending>()
    private var nextToken = 1L
    private var closed = false

    init { require(maxPending > 0) }

    fun register(
        request: PhoneDieselRequest,
    ): PhoneDieselRegistrationResult =
        synchronized(lock) {
            when {
                closed ->
                    PhoneDieselRegistrationResult.Rejected("closed")
                request.requestId in pending ->
                    PhoneDieselRegistrationResult.Rejected(
                        "duplicate_request_id",
                    )
                pending.size >= maxPending ->
                    PhoneDieselRegistrationResult.Rejected(
                        "too_many_pending",
                    )
                else -> {
                    val token = nextToken++
                    val completion =
                        CompletableDeferred<
                            PhoneDieselPendingCompletion
                        >()
                    pending[request.requestId] =
                        Pending(
                            token = token,
                            command = request.command,
                            name = request.name,
                            completion = completion,
                        )
                    PhoneDieselRegistrationResult.Registered(
                        token = token,
                        completion = completion,
                    )
                }
            }
        }

    fun accept(
        response: PhoneDieselResponse,
    ): PhoneDieselRouteResult {
        val requestId =
            response.requestId
                ?: return PhoneDieselRouteResult.NO_REQUEST_ID

        val current =
            synchronized(lock) {
                val found =
                    pending[requestId]
                        ?: return@synchronized null
                if (
                    found.command != response.command ||
                    found.name != response.name
                ) {
                    return PhoneDieselRouteResult.METADATA_MISMATCH
                }
                pending.remove(requestId)
                found
            }
                ?: return PhoneDieselRouteResult.UNKNOWN_OR_LATE

        current.completion.complete(
            PhoneDieselPendingCompletion.Response(response),
        )
        return PhoneDieselRouteResult.DELIVERED
    }

    fun cancel(requestId: String, token: Long) {
        synchronized(lock) {
            val current = pending[requestId] ?: return
            if (current.token == token) {
                pending.remove(requestId)
            }
        }
    }

    fun close() {
        val toClose =
            synchronized(lock) {
                if (closed) return
                closed = true
                pending.values.toList().also { pending.clear() }
            }
        toClose.forEach {
            it.completion.complete(PhoneDieselPendingCompletion.Closed)
        }
    }

    fun pendingCount(): Int =
        synchronized(lock) { pending.size }

    companion object {
        const val DEFAULT_MAX_PENDING = 8
    }
}
