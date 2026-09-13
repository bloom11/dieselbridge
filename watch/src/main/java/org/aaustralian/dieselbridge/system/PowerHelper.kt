// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.system

import android.content.Context
import android.os.PowerManager
import androidx.core.content.getSystemService

/**
 * Battery-optimization exemption status. A `connectedDevice` foreground service alone does not
 * guarantee exemption from Doze or vendor power management when the watch is idle.
 *
 * DieselBridge only detects the current exemption state here and surfaces it in the UI. If a
 * particular Wear OS build does not expose a working exemption UI, a developer can inspect or
 * change device-idle policy through ADB for the installed application ID:
 *
 * `adb shell dumpsys deviceidle whitelist +io.github.bloom11.dieselbridge`
 *
 * Device/vendor behavior varies, so this should not be treated as a universal setup requirement.
 */
object PowerHelper {
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService<PowerManager>() ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
