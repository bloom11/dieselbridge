// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.system

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.BatteryManager

/** Best-effort battery and app-usage snapshot for the local developer screen. */
data class BatteryUsageSnapshot(
    val capturedAtMs: Long,
    val levelPercent: Int?,
    val voltageVolts: Double?,
    val currentMilliAmps: Double?,
    val chargeCounterMah: Double?,
    val energyCounterMwh: Double?,
    val temperatureCelsius: Double?,
    val charging: Boolean?,
    val usageAccessGranted: Boolean,
    val batteryAttributionAvailable: Boolean,
    val batteryAttributionReason: String,
    val appUsage: List<AppUsageEntry>,
) {
    val totalForegroundMs: Long = appUsage.sumOf { it.foregroundMs }
}

data class AppUsageEntry(
    val packageName: String,
    val label: String,
    val foregroundMs: Long,
    val sharePercent: Double,
)

object BatteryUsageMonitor {
    private const val LOOKBACK_MS = 24L * 60L * 60L * 1000L

    fun snapshot(context: Context, nowMs: Long = System.currentTimeMillis()): BatteryUsageSnapshot {
        val app = context.applicationContext
        val battery = app.getSystemService(BatteryManager::class.java)
        val intent = app.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.let {
            val raw = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (raw >= 0 && scale > 0) ((raw * 100L) / scale).toInt() else null
        }
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }?.div(1000.0)
        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        }
        val current = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Long.MIN_VALUE && it != 0L }?.div(1000.0)
        val charge = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it != Long.MIN_VALUE && it >= 0L }?.div(1000.0)
        val energy = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            ?.takeIf { it != Long.MIN_VALUE && it >= 0L }?.div(1_000_000.0)

        val usage = queryUsage(app, nowMs)
        return BatteryUsageSnapshot(
            capturedAtMs = nowMs,
            levelPercent = level,
            voltageVolts = voltage,
            currentMilliAmps = current,
            chargeCounterMah = charge,
            energyCounterMwh = energy,
            temperatureCelsius = temperature,
            charging = charging,
            usageAccessGranted = usage.first,
            batteryAttributionAvailable = false,
            batteryAttributionReason = "BatteryStats attribution requires a privileged system permission",
            appUsage = usage.second,
        )
    }

    private fun queryUsage(context: Context, nowMs: Long): Pair<Boolean, List<AppUsageEntry>> {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return false to emptyList()
        val end = nowMs
        val start = end - LOOKBACK_MS
        val stats = runCatching { manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end) }
            .getOrNull().orEmpty()
        if (stats.isEmpty()) return false to emptyList()
        val total = stats.sumOf { it.totalTimeInForeground.coerceAtLeast(0L) }
        if (total <= 0L) return true to emptyList()
        val packageManager = context.packageManager
        val entries = stats.asSequence()
            .filter { it.totalTimeInForeground > 0L }
            .sortedByDescending { it.totalTimeInForeground }
            .take(12)
            .map { stat ->
                val label = runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(stat.packageName, 0)).toString()
                }.getOrDefault(stat.packageName)
                AppUsageEntry(stat.packageName, label, stat.totalTimeInForeground, stat.totalTimeInForeground * 100.0 / total)
            }
            .toList()
        return true to entries
    }
}
