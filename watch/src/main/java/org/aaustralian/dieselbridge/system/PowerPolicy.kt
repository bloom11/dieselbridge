// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.system

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.aaustralian.dieselbridge.service.DieselBridgeService

enum class PowerMode(val wireName: String, val label: String) {
    ACTIVE("active", "Active"),
    OPTIMIZED("optimized", "Optimized"),
    SLEEPING("sleeping", "Sleeping"),
}

/** User-selectable app policy. It never changes other apps or hidden system settings. */
object PowerPolicy {
    private const val PREFS = "diesel_power"
    private const val MODE = "mode"

    fun mode(context: Context): PowerMode =
        runCatching {
            PowerMode.valueOf(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(MODE, PowerMode.ACTIVE.name) ?: PowerMode.ACTIVE.name,
            )
        }.getOrDefault(PowerMode.ACTIVE)

    fun setMode(context: Context, mode: PowerMode) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(MODE, mode.name).apply()
        when (mode) {
            PowerMode.SLEEPING -> DieselBridgeService.stop(context)
            PowerMode.ACTIVE, PowerMode.OPTIMIZED -> DieselBridgeService.start(context)
        }
    }

    fun isSleeping(context: Context): Boolean = mode(context) == PowerMode.SLEEPING

    fun openOptimizationSettings(context: Context): Boolean =
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
}
