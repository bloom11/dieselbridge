// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.alarm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselExecutor
import org.aaustralian.dieselbridge.phone.gateway.PhoneDieselGatewayResult

enum class AlarmSyncStatus {
    IDLE,
    SYNCING,
    CONFIRMED,
    DIRTY,
}

data class AlarmSyncSnapshot(
    val status: AlarmSyncStatus = AlarmSyncStatus.IDLE,
    val desired: NextAlarmObservation? = null,
    val confirmed: NextAlarmObservation? = null,
    val lastReason: String? = null,
    val lastAttemptAtMs: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)

class AlarmSyncCoordinator(
    private val provider: NextAlarmProvider,
    private val executor: PhoneDieselExecutor,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val retryDelaysMs: List<Long> =
        DEFAULT_RETRY_DELAYS_MS,
) {
    private val requests =
        Channel<String>(Channel.CONFLATED)
    private val syncMutex = Mutex()
    private val mutableState =
        MutableStateFlow(AlarmSyncSnapshot())

    val state: StateFlow<AlarmSyncSnapshot> =
        mutableState.asStateFlow()

    init {
        scope.launch {
            for (reason in requests) {
                runRetrySequence(reason)
            }
        }
    }

    fun requestRefresh(reason: String) {
        require(reason.isNotBlank())
        requests.trySend(reason)
    }

    suspend fun syncOnce(reason: String): Boolean =
        syncMutex.withLock {
            val observation = provider.read()
            val previous = mutableState.value

            mutableState.value =
                previous.copy(
                    status = AlarmSyncStatus.SYNCING,
                    desired = observation,
                    lastReason = reason,
                    lastAttemptAtMs = clockMs(),
                    attemptCount = previous.attemptCount + 1,
                    lastError = null,
                )

            val result =
                executor.execute(
                    command = CompanionAlarmProtocol.COMMAND_SYNC,
                    args = CompanionAlarmProtocol.toArgs(observation),
                    timeoutMs = ALARM_SYNC_TIMEOUT_MS,
                )

            val success =
                result is PhoneDieselGatewayResult.Success &&
                    CompanionAlarmProtocol.matchesAcknowledgement(
                        observation = observation,
                        response = result.response,
                    )

            if (success) {
                mutableState.value =
                    mutableState.value.copy(
                        status = AlarmSyncStatus.CONFIRMED,
                        desired = observation,
                        confirmed = observation,
                        lastError = null,
                    )
                true
            } else {
                mutableState.value =
                    mutableState.value.copy(
                        status = AlarmSyncStatus.DIRTY,
                        desired = observation,
                        lastError = failureText(result),
                    )
                false
            }
        }

    private suspend fun runRetrySequence(reason: String) {
        if (syncOnce(reason)) {
            return
        }

        retryDelaysMs.forEachIndexed { index, delayMs ->
            delay(delayMs)
            if (syncOnce("$reason:retry${index + 1}")) {
                return
            }
        }
    }

    private fun failureText(
        result: PhoneDieselGatewayResult,
    ): String =
        when (result) {
            is PhoneDieselGatewayResult.Success ->
                "watch_rejected_or_ack_mismatch:" +
                    result.response.status.wireName
            is PhoneDieselGatewayResult.Timeout ->
                "timeout"
            is PhoneDieselGatewayResult.Rejected ->
                "gateway_rejected:${result.reason}"
            is PhoneDieselGatewayResult.SubmissionFailed ->
                "submission_failed:${result.errorType}"
            is PhoneDieselGatewayResult.Closed ->
                "gateway_closed"
        }

    companion object {
        const val ALARM_SYNC_TIMEOUT_MS = 4_000L
        val DEFAULT_RETRY_DELAYS_MS =
            listOf(
                2_000L,
                5_000L,
                15_000L,
                30_000L,
                60_000L,
            )
    }
}
