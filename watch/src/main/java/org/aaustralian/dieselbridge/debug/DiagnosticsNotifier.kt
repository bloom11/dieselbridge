// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import org.aaustralian.dieselbridge.BuildConfig
import org.aaustralian.dieselbridge.R
import org.aaustralian.dieselbridge.ble.ProbeReport
import org.aaustralian.dieselbridge.ui.debug.DiagnosticsActivity

/**
 * Posts one stable developer notification that acts as an entry point into
 * the internal diagnostics Activity.
 *
 * Repeated commands update the same notification rather than filling the
 * Wear OS notification stream.
 */
class DiagnosticsNotifier(
    private val context: Context,
) {

    init {
        createChannel()
    }

    @SuppressLint("MissingPermission")
    fun show(
        probe: ProbeReport,
        batteryPercent: Int?,
        charging: Boolean?,
        batteryProviderId: String?,
    ) {
        val openIntent =
            Intent(
                context,
                DiagnosticsActivity::class.java,
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                OPEN_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE,
            )

        val bleText =
            when {
                probe.centralConnected &&
                    probe.notifySubscribed ->
                    "BLE connected"

                probe.centralConnected ->
                    "BLE connected · not subscribed"

                probe.advertising ->
                    "BLE advertising"

                else ->
                    "BLE disconnected"
            }

        val batteryText =
            batteryPercent?.let {
                buildString {
                    append("battery ")
                    append(it)
                    append("%")

                    if (charging == true) {
                        append(" charging")
                    }
                }
            } ?: "battery unknown"

        val detail =
            buildString {
                append("BLE: ")
                append(bleText)

                append("\nBattery: ")
                append(
                    batteryPercent?.let { "$it%" }
                        ?: "unknown",
                )

                if (charging == true) {
                    append(" · charging")
                }

                append("\nProvider: ")
                append(
                    batteryProviderId
                        ?: "none",
                )

                probe.lastBatteryTxReason?.let {
                    append("\nLast battery TX: ")
                    append(it)

                    probe.lastBatteryTxPercent?.let { pct ->
                        append(" · ")
                        append(pct)
                        append("%")
                    }
                }

                append("\nBuild: ")
                append(BuildConfig.VERSION_NAME)
            }

        val notification =
            NotificationCompat.Builder(
                context,
                CHANNEL_ID,
            )
                .setSmallIcon(
                    R.drawable.ic_launcher_foreground,
                )
                .setContentTitle("Diesel Developer")
                .setContentText("$bleText · $batteryText")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(detail),
                )
                .setCategory(
                    NotificationCompat.CATEGORY_STATUS,
                )
                .setPriority(
                    NotificationCompat.PRIORITY_DEFAULT,
                )
                .setContentIntent(pendingIntent)
                .addAction(
                    R.drawable.ic_launcher_foreground,
                    "Open diagnostics",
                    pendingIntent,
                )
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build()

        runCatching {
            NotificationManagerCompat
                .from(context)
                .notify(
                    NOTIFICATION_ID,
                    notification,
                )
        }
    }

    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Developer tools",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description =
                    "DieselBridge developer diagnostics"
                enableVibration(false)
                setSound(null, null)
            }

        context
            .getSystemService<NotificationManager>()
            ?.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID =
            "diesel_developer"

        const val NOTIFICATION_ID =
            0x44494553

        const val OPEN_REQUEST_CODE =
            0x4449
    }
}
