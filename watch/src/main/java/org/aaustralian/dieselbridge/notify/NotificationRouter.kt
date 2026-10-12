// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.notify

import android.content.Context
import org.aaustralian.dieselbridge.data.CannedResponsesStore
import org.aaustralian.dieselbridge.data.MusicStore
import org.aaustralian.dieselbridge.data.NotificationStore
import org.aaustralian.dieselbridge.data.WatchNotification
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.capability.VibrationCapability
import org.aaustralian.dieselbridge.protocol.DieselInvalidRequest
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.protocol.GbMessage
import org.aaustralian.dieselbridge.protocol.GbProtocol

/**
 * Applies one inbound Gadgetbridge/Bangle line. Notification mutations can be
 * redirected into the Diesel notification owner while debug/legacy callers
 * keep safe defaults.
 */
class NotificationRouter(
    private val context: Context,
    private val notifier: WatchNotifier,
    private val capabilities: CapabilityRegistry? = null,
    private val onDieselRequest:
        (DieselRequest) -> Unit = {},
    private val onInvalidDieselRequest:
        (DieselInvalidRequest) -> Unit = {},
    private val onActivityControl: (GbMessage.ActivityControl) -> Unit = {},
    private val onNotificationUpsert:
        (WatchNotification) -> Unit =
        {
            NotificationStore.upsert(
                it,
            )
        },
    private val onNotificationRemove:
        (Long) -> Unit =
        {
            NotificationStore.remove(
                it,
            )
        },
) {

    fun handle(line: String): GbMessage? {
        val msg = GbProtocol.parseLine(line)
        when (msg) {
            is GbMessage.Notify -> {
                val wn = WatchNotification(
                    id = msg.id,
                    app = msg.src,
                    title = msg.title ?: msg.sender,
                    body = msg.body ?: msg.subject,
                    sender = msg.sender,
                    receivedAt = System.currentTimeMillis(),
                    replyable = msg.replyable,
                )
                onNotificationUpsert(wn)
                notifier.show(wn)
            }
            is GbMessage.NotifyDelete -> {
                onNotificationRemove(msg.id)
                notifier.cancel(msg.id)
            }
            is GbMessage.Find -> if (msg.active) FindAlertController.start(context) else FindAlertController.stop(context)
            is GbMessage.Vibrate -> {
                val vibration = capabilities
                    ?.resolveAs<VibrationCapability>(VibrationCapability.ID)

                if (vibration != null) {
                    vibration.vibrate()
                } else {
                    FindAlertController.buzzOnce(context)
                }
            }
            is GbMessage.Call -> CallController.onCall(context, msg.cmd, msg.name, msg.number)
            is GbMessage.MusicInfo -> MusicStore.onInfo(msg.artist, msg.album, msg.track, msg.durMs)
            is GbMessage.MusicState -> MusicStore.onState(msg.state, msg.position)
            is GbMessage.CannedResponses -> CannedResponsesStore.set(msg.list)
            is GbMessage.ActivityControl -> onActivityControl(msg)
            is GbMessage.DieselRequestMessage ->
                onDieselRequest(
                    msg.request,
                )
            is GbMessage.InvalidDieselRequestMessage ->
                onInvalidDieselRequest(
                    msg.failure,
                )
            else -> {}
        }
        return msg
    }
}
