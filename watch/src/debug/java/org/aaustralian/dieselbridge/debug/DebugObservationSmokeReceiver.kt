// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Locale
import org.aaustralian.dieselbridge.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * DEBUG-ONLY ADB surface for the service-owned observation smoke runner.
 *
 * This receiver is intentionally a thin process-local adapter:
 *
 * ADB -> BroadcastReceiver -> DeveloperRuntimeAccess -> SensorObservationSmokeRunner
 *
 * It does not own hardware, register capabilities, add Diesel commands, or
 * participate in BLE/Gadgetbridge transport. Release builds do not contain it.
 */
class DebugObservationSmokeReceiver : BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION) return

        val requestId =
            intent
                .getStringExtra(EXTRA_REQUEST_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "adb-${System.currentTimeMillis()}"

        val operation =
            intent
                .getStringExtra(EXTRA_OPERATION)
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotEmpty() }
                ?: OP_STATUS

        val response =
            runCatching {
                handle(
                    operation = operation,
                    intent = intent,
                )
            }.getOrElse { error ->
                errorResponse(
                    code = "receiver_exception",
                    message =
                        error.javaClass.simpleName +
                            (error.message?.let { ": $it" } ?: ""),
                )
            }

        response.put("requestId", requestId)
        response.put("operation", operation)
        response.put("build", buildJson())

        // One compact, correlated line. The Termux helper filters by requestId,
        // so stale logcat records can never be mistaken for this response.
        Log.i(
            TAG,
            "id=$requestId $response",
        )
    }

    private fun handle(
        operation: String,
        intent: Intent,
    ): JSONObject {
        val runner =
            DeveloperRuntimeAccess
                .observationSmokeRunner
                .value
                ?: return errorResponse(
                    code = "runtime_unavailable",
                    message =
                        "DieselBridge service has not attached the observation smoke runner",
                )

        return when (operation) {
            OP_START ->
                start(
                    runner = runner,
                    intent = intent,
                )

            OP_STATUS ->
                status(
                    runner = runner,
                    requestedRunId =
                        intent
                            .getStringExtra(EXTRA_RUN_ID)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                )

            OP_CANCEL ->
                cancel(
                    runner = runner,
                    runId =
                        intent
                            .getStringExtra(EXTRA_RUN_ID)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                )

            else ->
                errorResponse(
                    code = "invalid_operation",
                    message = "Expected start, status, or cancel",
                )
        }
    }

    private fun start(
        runner: SensorObservationSmokeRunner,
        intent: Intent,
    ): JSONObject {
        val profileName =
            intent
                .getStringExtra(EXTRA_PROFILE)
                ?.trim()
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotEmpty() }
                ?: return errorResponse(
                    code = "missing_profile",
                    message = "start requires --es profile <profile>",
                )

        val profile =
            SensorObservationSmokeProfile
                .fromWireName(profileName)
                ?: return errorResponse(
                    code = "invalid_profile",
                    message =
                        "Unknown profile '$profileName'; expected " +
                            SensorObservationSmokeProfile
                                .values()
                                .joinToString(",") {
                                    it.wireName
                                },
                )

        val runId =
            runner.start(profile)
                ?: return errorResponse(
                    code = "run_already_active",
                    message = "Another observation smoke run is still active",
                ).putSnapshot(
                    runner.latest(),
                )

        return okResponse()
            .put("runId", runId)
            .putSnapshot(
                runner.latest(),
            )
    }

    private fun status(
        runner: SensorObservationSmokeRunner,
        requestedRunId: String?,
    ): JSONObject {
        val snapshot = runner.latest()

        if (
            requestedRunId != null &&
                snapshot?.runId != requestedRunId
        ) {
            return errorResponse(
                code = "run_id_mismatch",
                message =
                    "Latest run is " +
                        (snapshot?.runId ?: "none") +
                        ", not $requestedRunId",
            ).putSnapshot(snapshot)
        }

        return okResponse()
            .putSnapshot(snapshot)
    }

    private fun cancel(
        runner: SensorObservationSmokeRunner,
        runId: String?,
    ): JSONObject {
        val requiredRunId =
            runId
                ?: return errorResponse(
                    code = "missing_run_id",
                    message = "cancel requires --es runId <runId>",
                )

        if (!runner.cancel(requiredRunId)) {
            return errorResponse(
                code = "run_not_active",
                message = "Run $requiredRunId is not the active run",
            ).putSnapshot(
                runner.latest(),
            )
        }

        return okResponse()
            .put("runId", requiredRunId)
            .putSnapshot(
                runner.latest(),
            )
    }

    private fun okResponse(): JSONObject =
        JSONObject()
            .put("ok", true)

    private fun errorResponse(
        code: String,
        message: String,
    ): JSONObject =
        JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("message", message)

    private fun JSONObject.putSnapshot(
        snapshot: SensorObservationSmokeSnapshot?,
    ): JSONObject {
        put(
            "snapshot",
            snapshot?.toJson() ?: JSONObject.NULL,
        )
        return this
    }

    private fun SensorObservationSmokeSnapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("runId", runId)
            put("profile", profile.wireName)
            put(
                "status",
                status.name.lowercase(Locale.US),
            )
            putNullable("logicalId", logicalId)
            putNullable("requestedPeriodMs", requestedPeriodMs)
            putNullable("providerId", providerId)
            putNullable("acquisitionPeriodMs", acquisitionPeriodMs)
            putNullable(
                "providerConfiguredPeriodMs",
                providerConfiguredPeriodMs,
            )
            putNullable(
                "providerEffectivePeriodMs",
                providerEffectivePeriodMs,
            )
            putNullable(
                "sensorManagerRouteId",
                sensorManagerRouteId,
            )
            putNullable(
                "sensorManagerRouteWakeUp",
                sensorManagerRouteWakeUp,
            )
            putNullable(
                "sensorManagerRouteMinDelayUs",
                sensorManagerRouteMinDelayUs,
            )
            putNullable(
                "sensorManagerRouteReportingMode",
                sensorManagerRouteReportingMode,
            )
            putNullable(
                "sensorManagerRoutePowerMilliAmps",
                sensorManagerRoutePowerMilliAmps,
            )
            put("sampleCount", sampleCount)
            putNullable("firstSequence", firstSequence)
            putNullable("lastSequence", lastSequence)
            putNullable(
                "firstSensorTimestampNs",
                firstSensorTimestampNs,
            )
            putNullable(
                "lastSensorTimestampNs",
                lastSensorTimestampNs,
            )
            put(
                "nonAdvancingSequenceCount",
                nonAdvancingSequenceCount,
            )
            put(
                "nonAdvancingTimestampCount",
                nonAdvancingTimestampCount,
            )
            putNullable(
                "observedMinIntervalMs",
                observedMinIntervalMs,
            )
            putNullable(
                "observedMedianIntervalMs",
                observedMedianIntervalMs,
            )
            putNullable(
                "observedMaxIntervalMs",
                observedMaxIntervalMs,
            )
            put(
                "providerDroppedTotal",
                providerDroppedTotal,
            )
            put(
                "subscriptionDroppedTotal",
                subscriptionDroppedTotal,
            )
            put(
                "phaseTransitions",
                JSONArray().apply {
                    phaseTransitions.forEach {
                        put(it)
                    }
                },
            )
            put(
                "lastValues",
                JSONArray().apply {
                    lastValues.forEach { value ->
                        if (value.isFinite()) {
                            put(value.toDouble())
                        } else {
                            // JSON has no NaN/Infinity numeric representation.
                            // Preserve the observation explicitly rather than
                            // silently clamping or dropping it.
                            put(value.toString())
                        }
                    }
                },
            )
            put("completedCycles", completedCycles)
            putNullable(
                "sharingInitialAcquisitionPeriodMs",
                sharingInitialAcquisitionPeriodMs,
            )
            putNullable(
                "sharingCombinedAcquisitionPeriodMs",
                sharingCombinedAcquisitionPeriodMs,
            )
            putNullable(
                "sharingRestoredAcquisitionPeriodMs",
                sharingRestoredAcquisitionPeriodMs,
            )
            putNullable("detail", detail)
            put("startedAtMs", startedAtMs)
            putNullable("finishedAtMs", finishedAtMs)
        }

    private fun JSONObject.putNullable(
        key: String,
        value: Any?,
    ) {
        put(
            key,
            value ?: JSONObject.NULL,
        )
    }

    private fun buildJson(): JSONObject =
        JSONObject()
            .put(
                "applicationId",
                BuildConfig.APPLICATION_ID,
            )
            .put(
                "versionName",
                BuildConfig.VERSION_NAME,
            )
            .put(
                "versionCode",
                BuildConfig.VERSION_CODE,
            )
            .put(
                "gitSha",
                BuildConfig.BUILD_GIT_SHA,
            )
            .put(
                "ciRunId",
                BuildConfig.BUILD_CI_RUN_ID,
            )
            .put(
                "timestampUtc",
                BuildConfig.BUILD_TIMESTAMP_UTC,
            )

    private companion object {
        const val TAG = "DieselObservationSmoke"

        const val ACTION =
            "org.aaustralian.dieselbridge.OBSERVATION_SMOKE"

        const val EXTRA_REQUEST_ID = "requestId"
        const val EXTRA_OPERATION = "op"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_RUN_ID = "runId"

        const val OP_START = "start"
        const val OP_STATUS = "status"
        const val OP_CANCEL = "cancel"
    }
}
