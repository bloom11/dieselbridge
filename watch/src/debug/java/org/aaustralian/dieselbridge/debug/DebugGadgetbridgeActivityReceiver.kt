// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.aaustralian.dieselbridge.BuildConfig
import org.aaustralian.dieselbridge.integration.gadgetbridge.GadgetbridgeActivitySessionController
import org.json.JSONObject

/** DEBUG-only read-only ADB view of the service-owned Gadgetbridge activity session. */
class DebugGadgetbridgeActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: "adb-${System.currentTimeMillis()}"

        val state = DeveloperRuntimeAccess.gadgetbridgeActivityState.value
        val response = if (state == null) {
            JSONObject()
                .put("ok", false)
                .put("error", "runtime_unavailable")
                .put("message", "DieselBridge service has not attached Gadgetbridge activity state")
        } else {
            JSONObject()
                .put("ok", true)
                .put("readOnly", true)
                .put("snapshot", state.value.toJson())
        }

        response.put("requestId", requestId).put("build", buildJson())
        Log.i(TAG, "id=$requestId $response")
    }

    private fun GadgetbridgeActivitySessionController.Snapshot.toJson(): JSONObject =
        JSONObject().apply {
            put("enabled", enabled)
            put("heartRateRequested", heartRateRequested)
            put("stepsRequested", stepsRequested)
            put("intervalSeconds", intervalSeconds)
            putNullable("heartRateSubscriptionId", heartRateSubscriptionId)
            putNullable("stepSubscriptionId", stepSubscriptionId)
            putNullable("latestHeartRateBpm", latestHeartRateBpm)
            putNullable("heartRateProviderId", heartRateProviderId)
            putNullable("lastHeartRateSensorTimestampNs", lastHeartRateSensorTimestampNs)
            putNullable("stepProviderId", stepProviderId)
            putNullable("stepBaselineRaw", stepBaselineRaw)
            putNullable("latestStepCounterRaw", latestStepCounterRaw)
            put("pendingStepDelta", pendingStepDelta)
            putNullable("lastStepSensorTimestampNs", lastStepSensorTimestampNs)
            put("stepDomainResetCount", stepDomainResetCount)
            putNullable("lastReportStepDelta", lastReportStepDelta)
            put("reportsAttempted", reportsAttempted)
            put("reportsQueued", reportsQueued)
            put("reportsRejected", reportsRejected)
            putNullable("lastReportAtMs", lastReportAtMs)
            putNullable("lastReportLine", lastReportLine)
            putNullable("lastStopReason", lastStopReason)
            putNullable("lastError", lastError)
        }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun buildJson(): JSONObject = JSONObject()
        .put("applicationId", BuildConfig.APPLICATION_ID)
        .put("versionName", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE)
        .put("gitSha", BuildConfig.BUILD_GIT_SHA)
        .put("ciRunId", BuildConfig.BUILD_CI_RUN_ID)
        .put("timestampUtc", BuildConfig.BUILD_TIMESTAMP_UTC)

    private companion object {
        const val TAG = "DieselGbActivityProof"
        const val ACTION = "org.aaustralian.dieselbridge.GADGETBRIDGE_ACTIVITY_STATUS"
        const val EXTRA_REQUEST_ID = "requestId"
    }
}
