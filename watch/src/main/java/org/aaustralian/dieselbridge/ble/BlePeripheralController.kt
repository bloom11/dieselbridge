// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ble

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Log
import org.aaustralian.dieselbridge.BuildConfig
import org.aaustralian.dieselbridge.data.NotificationActions
import org.aaustralian.dieselbridge.data.NotificationStore
import org.aaustralian.dieselbridge.notify.NotificationRouter
import org.aaustralian.dieselbridge.notify.WatchNotifier
import org.aaustralian.dieselbridge.platform.capability.BatteryState
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.protocol.GbMessage
import org.aaustralian.dieselbridge.protocol.GbProtocol

/**
 * Owns the BLE peripheral stack: capability probe -> open GATT server -> advertise, and routes
 * inbound `GB({...})` lines into [NotificationStore].
 *
 * Idempotent and Bluetooth-aware: [start] is a no-op while Bluetooth is off, and the service's
 * Bluetooth-state receiver calls [onBluetoothStateOn]/[onBluetoothStateOff] so advertising begins
 * automatically once BT is enabled (handles the "BT off at startup" case).
 *
 * BLUETOOTH_CONNECT is requested at runtime in MainActivity, hence @SuppressLint (matches NusGattServer).
 */
@SuppressLint("MissingPermission")
class BlePeripheralController(
    private val context: Context,
    private val capabilities: CapabilityRegistry = CapabilityRegistry(),
    private val batterySnapshot: () -> BatteryState? = { null },
) {
    private var advertiser: NusAdvertiser? = null
    private var gattServer: NusGattServer? = null
    private var running = false
    private val notifier = WatchNotifier(context)
    private val router = NotificationRouter(context, notifier, capabilities)

    // Last battery snapshot pushed to the phone; used to suppress duplicate `status` lines.
    @Volatile
    private var lastSentPct = -1

    @Volatile
    private var lastSentChg = -1

    fun start() {
        if (running) return
        val caps = CapabilityProbe.run(context)
        ProbeStateHolder.update {
            it.copy(
                bluetoothOn = caps.bluetoothOn,
                advertiserAvailable = caps.advertiserAvailable,
                multipleAdvSupported = caps.multipleAdvSupported,
            )
        }
        if (!caps.bluetoothOn) {
            ProbeStateHolder.log("Bluetooth OFF — advertising will start automatically when it turns on")
            return
        }
        if (!caps.advertiserAvailable) {
            ProbeStateHolder.log("FAIL: no BLE advertiser — this watch cannot be a peripheral")
            return
        }

        val server = NusGattServer(
            context,
            onLine = ::onRxLine,
            onStateChange = ::publishServerState,
            onSubscribed = ::onSubscribed,
        )
        val opened = server.open()
        gattServer = server
        ProbeStateHolder.update { it.copy(gattServerOpen = opened) }

        val adv = NusAdvertiser(context)
        advertiser = adv
        adv.start { success, error ->
            ProbeStateHolder.update { it.copy(advertising = success, advertiseError = error) }
            ProbeStateHolder.log(
                if (success) "advertising as '${BleUuids.ADVERTISED_NAME}'" else "advertise FAIL: $error",
            )
        }
        running = true
    }

    fun stop() {
        advertiser?.stop()
        gattServer?.close()
        advertiser = null
        gattServer = null
        running = false
    }

    /**
     * Watch -> phone action (dismiss / reply / open). Sent as raw newline-terminated JSON over the
     * NUS TX characteristic; Gadgetbridge maps DISMISS -> cancelNotification and REPLY -> RemoteInput.
     * On DISMISS we also clear it locally (optimistic; the phone's `notify-` round-trip is idempotent).
     */
    fun sendAction(id: Long, action: String, reply: String?): Boolean {
        val line = GbProtocol.encodeAction(id, action, reply)
        val sent = gattServer?.sendLine(line) ?: false
        Log.i(TAG, "action TX: $line (sent=$sent)")
        ProbeStateHolder.log("action $action #$id (sent=$sent)")
        if (action == NotificationActions.ACTION_DISMISS) {
            NotificationStore.remove(id)
            notifier.cancel(id)
        }
        if (action == NotificationActions.ACTION_DISMISS_ALL) {
            // Cancel only the cards we posted (never NotificationManagerCompat.cancelAll()).
            NotificationStore.items.value.forEach { notifier.cancel(it.id) }
            NotificationStore.clear()
        }
        return sent
    }

    // NOTE: these watch->phone sends do NOT gate on isNotifyEnabled. That flag goes stale across
    // Gadgetbridge reconnects (it holds the link but our tracked subscription resets), which
    // silently swallowed Answer/Decline/music taps. sendLine() already no-ops when no central is
    // connected, and the stack harmlessly drops a notify if the central isn't subscribed — exactly
    // how the dismiss/reply path (sendAction) has always worked reliably.

    /** Watch -> phone: ask Gadgetbridge to ring the phone (findPhone). */
    fun sendFindPhone(active: Boolean): Boolean {
        val sent = gattServer?.sendLine(GbProtocol.encodeFindPhone(active)) ?: false
        Log.i(TAG, "findPhone TX: active=$active (sent=$sent)")
        return sent
    }

    /** Watch -> phone: telephony action (accept / reject / ignore / end). */
    fun sendCall(action: String): Boolean {
        val line = GbProtocol.encodeCall(action)
        val sent = gattServer?.sendLine(line) ?: false
        Log.i(TAG, "call TX: $line (sent=$sent)")
        ProbeStateHolder.log("call $action (sent=$sent)")
        return sent
    }

    /** Watch -> phone: music control (play / pause / next / previous / volumeup / volumedown). */
    fun sendMusic(cmd: String): Boolean {
        val line = GbProtocol.encodeMusic(cmd)
        val sent = gattServer?.sendLine(line) ?: false
        Log.i(TAG, "music TX: $cmd (sent=$sent)")
        ProbeStateHolder.log("music $cmd (sent=$sent)")
        return sent
    }

    /**
     * Canonical platform battery-state callback.
     *
     * null means no usable battery provider is currently available. In that
     * case the wire protocol has no unknown-state representation, so nothing
     * is transmitted and the dedup state is reset. If battery state later
     * returns with the same value, it will therefore be sent again.
     */
    fun onBatteryStateChanged(state: BatteryState?) {
        if (state == null) {
            clearBatteryState()
            return
        }

        val charging =
            if (state.charging) {
                1
            } else {
                0
            }

        ProbeStateHolder.update {
            it.copy(
                batteryPct = state.percent,
                charging = state.charging,
            )
        }

        if (
            state.percent == lastSentPct &&
            charging == lastSentChg
        ) {
            return
        }

        lastSentPct = state.percent
        lastSentChg = charging

        val sent =
            sendStatus(
                state.percent,
                state.voltageVolts,
                charging,
            )

        recordBatteryTx(
            percent = state.percent,
            reason = BATTERY_TX_REASON_REACTIVE,
            sent = sent,
        )
    }

    fun onBluetoothStateOn() {
        ProbeStateHolder.update { it.copy(bluetoothOn = true) }
        ProbeStateHolder.log("Bluetooth ON")
        start()
    }

    fun onBluetoothStateOff() {
        stop()
        ProbeStateHolder.update {
            it.copy(
                bluetoothOn = false,
                advertising = false,
                gattServerOpen = false,
                centralConnected = false,
                notifySubscribed = false,
            )
        }
        ProbeStateHolder.log("Bluetooth OFF — advertising stopped")
    }

    private fun onRxLine(line: String) {
        Log.i(TAG, "RX: $line")
        ProbeStateHolder.update { it.copy(bytesReceived = it.bytesReceived + line.length, lastLine = line) }
        when (val msg = router.handle(line)) {
            is GbMessage.Notify -> ProbeStateHolder.log("notify #${msg.id}: ${msg.title ?: msg.body ?: ""}")
            is GbMessage.NotifyDelete -> ProbeStateHolder.log("dismiss #${msg.id}")
            is GbMessage.Find -> ProbeStateHolder.log("find " + (if (msg.active) "start" else "stop"))
            is GbMessage.Vibrate -> ProbeStateHolder.log("vibrate n=" + msg.n)
            is GbMessage.Call -> ProbeStateHolder.log("call " + msg.cmd + " " + (msg.name ?: msg.number ?: ""))
            is GbMessage.MusicInfo -> ProbeStateHolder.log("musicinfo " + (msg.track ?: ""))
            is GbMessage.MusicState -> ProbeStateHolder.log("musicstate " + msg.state)
            is GbMessage.CannedResponses -> ProbeStateHolder.log("canned x" + msg.list.size)
            is GbMessage.Other -> ProbeStateHolder.log("msg t=${msg.type}")
            null -> ProbeStateHolder.log("unparsed: ${line.take(40)}")
        }
    }

    /** CCCD rising-edge callback: greet the freshly-subscribed central with version + battery. */
    private fun onSubscribed() {
        sendVer()
        pushBatteryStatus()
    }

    private fun sendVer(): Boolean =
        gattServer?.sendLine(GbProtocol.encodeVer(BuildConfig.VERSION_NAME, Build.MODEL ?: Build.PRODUCT)) ?: false

    private fun sendStatus(bat: Int, volt: Double, chg: Int): Boolean =
        gattServer?.sendLine(GbProtocol.encodeStatus(bat, volt, chg)) ?: false

    /** Pushes a synchronous snapshot from the currently selected battery provider. */
    private fun pushBatteryStatus(): Boolean {
        val state = batterySnapshot()

        if (state == null) {
            clearBatteryState()
            return false
        }

        val charging =
            if (state.charging) {
                1
            } else {
                0
            }

        ProbeStateHolder.update {
            it.copy(
                batteryPct = state.percent,
                charging = state.charging,
            )
        }

        lastSentPct = state.percent
        lastSentChg = charging

        val sent =
            sendStatus(
                state.percent,
                state.voltageVolts,
                charging,
            )

        recordBatteryTx(
            percent = state.percent,
            reason = BATTERY_TX_REASON_SUBSCRIPTION,
            sent = sent,
        )

        return sent
    }

    private fun clearBatteryState() {
        lastSentPct = -1
        lastSentChg = -1

        ProbeStateHolder.update {
            it.copy(
                batteryPct = null,
                charging = false,
            )
        }
    }

    private fun recordBatteryTx(
        percent: Int,
        reason: String,
        sent: Boolean,
    ) {
        val now = System.currentTimeMillis()

        ProbeStateHolder.update {
            it.copy(
                batteryTxAttempts = it.batteryTxAttempts + 1,
                lastBatteryTxPercent = percent,
                lastBatteryTxAtMs = now,
                lastBatteryTxReason = reason,
                lastBatteryTxSucceeded = sent,
            )
        }

        ProbeStateHolder.log(
            "battery TX $percent% reason=$reason sent=$sent",
        )
    }

    private fun publishServerState() {
        val server = gattServer ?: return
        val device = server.connectedDevice
        ProbeStateHolder.update {
            it.copy(
                centralConnected = device != null,
                connectedDeviceName = device?.let { d -> runCatching { d.name }.getOrNull() ?: d.address },
                notifySubscribed = server.isNotifyEnabled,
            )
        }
    }

    private companion object {
        const val TAG = "BleController"

        const val BATTERY_TX_REASON_REACTIVE =
            "reactive"

        const val BATTERY_TX_REASON_SUBSCRIPTION =
            "subscription"
    }
}
