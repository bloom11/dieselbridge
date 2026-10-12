// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.notification

import org.aaustralian.dieselbridge.data.NotificationActions
import org.aaustralian.dieselbridge.data.NotificationStore
import org.aaustralian.dieselbridge.data.WatchNotification
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.action.DieselActionKey
import org.aaustralian.dieselbridge.platform.action.DieselActionRegistration
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.aaustralian.dieselbridge.platform.event.DieselEvent
import org.aaustralian.dieselbridge.platform.state.DieselStateKey
import org.aaustralian.dieselbridge.platform.state.DieselStatePublisher

data class PhoneNotificationSummary(
    val activeCount: Int,
    val latest: WatchNotification?,
)

data class PhoneNotificationDismissRequest(
    val id: Long,
)

data class PhoneNotificationReplyRequest(
    val id: Long,
    val text: String,
)

data class PhoneNotificationActionResult(
    val sent: Boolean,
    val affectedCount: Int,
)

sealed interface PhoneNotificationEvent : DieselEvent

data class PhoneNotificationReceivedEvent(
    val notification: WatchNotification,
    override val timestampMs: Long = notification.receivedAt,
) : PhoneNotificationEvent {
    override val type: String = RECEIVED_EVENT_ID

    companion object {
        const val RECEIVED_EVENT_ID = "phone.notification.received"
    }
}

data class PhoneNotificationRemovedEvent(
    val notificationId: Long,
    val reason: String,
    override val timestampMs: Long,
) : PhoneNotificationEvent {
    override val type: String = REMOVED_EVENT_ID

    companion object {
        const val REMOVED_EVENT_ID = "phone.notification.removed"
    }
}

/**
 * Diesel ownership for phone notifications.
 *
 * M8.1a keeps NotificationStore as the legacy UI projection until the bounded
 * collection API exists, but all production mutations pass through this owner
 * so public state/events/actions remain coherent with that projection.
 */
class PhoneNotificationService(
    private val platform: DieselPlatform,
    private val sendAction: (id: Long, action: String, reply: String?) -> Boolean,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val lock = Any()

    private val summaryPublisher: DieselStatePublisher<PhoneNotificationSummary> =
        platform.states.registerPublisher(
            key = SUMMARY_KEY,
            ownerId = OWNER_ID,
        )

    private val dismissRegistration: DieselActionRegistration =
        platform.actions.register(
            key = DISMISS_KEY,
            ownerId = OWNER_ID,
        ) { request ->
            dismiss(request.id)
        }

    private val replyRegistration: DieselActionRegistration =
        platform.actions.register(
            key = REPLY_KEY,
            ownerId = OWNER_ID,
        ) { request ->
            reply(request.id, request.text)
        }

    private val dismissAllRegistration: DieselActionRegistration =
        platform.actions.register(
            key = DISMISS_ALL_KEY,
            ownerId = OWNER_ID,
        ) {
            dismissAll()
        }

    init {
        synchronized(lock) {
            publishSummaryLocked()
        }
    }

    fun receive(notification: WatchNotification) {
        synchronized(lock) {
            NotificationStore.upsert(notification)
            publishSummaryLocked()
        }

        platform.events.tryEmit(
            PhoneNotificationReceivedEvent(
                notification = notification,
            ),
        )
    }

    fun removeFromPhone(id: Long) {
        removeExisting(
            id = id,
            reason = "phone_removed",
        )
    }

    /**
     * Compatibility entry point for the existing native UI. Native UI and
     * public Binder actions therefore share one action owner and one outbound
     * Gadgetbridge/Bangle transport callback.
     */
    fun performLegacyAction(
        id: Long,
        action: String,
        reply: String?,
    ): Boolean =
        when (action) {
            NotificationActions.ACTION_DISMISS ->
                wasSent(dismiss(id))

            NotificationActions.ACTION_REPLY ->
                reply?.let {
                    wasSent(
                        reply(
                            id = id,
                            text = it,
                        ),
                    )
                } ?: false

            NotificationActions.ACTION_DISMISS_ALL ->
                wasSent(dismissAll())

            else ->
                sendAction(id, action, reply)
        }

    private fun dismiss(
        id: Long,
    ): DieselActionResult<PhoneNotificationActionResult> {
        val existing =
            synchronized(lock) {
                NotificationStore.items.value.firstOrNull {
                    it.id == id
                }
            } ?: return DieselActionResult.Rejected(
                "notification_not_found",
            )

        val sent =
            sendAction(
                id,
                NotificationActions.ACTION_DISMISS,
                null,
            )

        if (!sent) {
            return DieselActionResult.Unavailable
        }

        /*
         * BlePeripheralController still owns native card cancellation. Reapply
         * the store mutation idempotently and publish Diesel state/event from
         * the pre-send snapshot.
         */
        synchronized(lock) {
            NotificationStore.remove(id)
            publishSummaryLocked()
        }

        platform.events.tryEmit(
            PhoneNotificationRemovedEvent(
                notificationId = existing.id,
                reason = "dismissed",
                timestampMs = clockMs(),
            ),
        )

        return DieselActionResult.Success(
            PhoneNotificationActionResult(
                sent = true,
                affectedCount = 1,
            ),
        )
    }

    private fun reply(
        id: Long,
        text: String,
    ): DieselActionResult<PhoneNotificationActionResult> {
        val existing =
            synchronized(lock) {
                NotificationStore.items.value.firstOrNull {
                    it.id == id
                }
            } ?: return DieselActionResult.Rejected(
                "notification_not_found",
            )

        if (!existing.replyable) {
            return DieselActionResult.Rejected(
                "notification_not_replyable",
            )
        }

        if (text.isEmpty()) {
            return DieselActionResult.Rejected(
                "reply_empty",
            )
        }

        if (text.toByteArray(Charsets.UTF_8).size > MAX_REPLY_BYTES) {
            return DieselActionResult.Rejected(
                "reply_too_large",
            )
        }

        val sent =
            sendAction(
                id,
                NotificationActions.ACTION_REPLY,
                text,
            )

        return if (sent) {
            DieselActionResult.Success(
                PhoneNotificationActionResult(
                    sent = true,
                    affectedCount = 0,
                ),
            )
        } else {
            DieselActionResult.Unavailable
        }
    }

    private fun dismissAll():
        DieselActionResult<PhoneNotificationActionResult> {
        val existing =
            synchronized(lock) {
                NotificationStore.items.value.toList()
            }

        val sent =
            sendAction(
                0L,
                NotificationActions.ACTION_DISMISS_ALL,
                null,
            )

        if (!sent) {
            return DieselActionResult.Unavailable
        }

        synchronized(lock) {
            NotificationStore.clear()
            publishSummaryLocked()
        }

        val timestamp = clockMs()

        existing.forEach { notification ->
            platform.events.tryEmit(
                PhoneNotificationRemovedEvent(
                    notificationId = notification.id,
                    reason = "dismissed_all",
                    timestampMs = timestamp,
                ),
            )
        }

        return DieselActionResult.Success(
            PhoneNotificationActionResult(
                sent = true,
                affectedCount = existing.size,
            ),
        )
    }

    private fun removeExisting(
        id: Long,
        reason: String,
    ) {
        val existing =
            synchronized(lock) {
                val found =
                    NotificationStore.items.value.firstOrNull {
                        it.id == id
                    } ?: return@synchronized null

                NotificationStore.remove(id)
                publishSummaryLocked()
                found
            } ?: return

        platform.events.tryEmit(
            PhoneNotificationRemovedEvent(
                notificationId = existing.id,
                reason = reason,
                timestampMs = clockMs(),
            ),
        )
    }

    private fun publishSummaryLocked() {
        val items = NotificationStore.items.value

        check(
            summaryPublisher.set(
                PhoneNotificationSummary(
                    activeCount = items.size,
                    latest = items.firstOrNull(),
                ),
            ),
        ) {
            "Phone notification summary publisher is closed"
        }
    }

    private fun wasSent(
        result: DieselActionResult<PhoneNotificationActionResult>,
    ): Boolean =
        result is DieselActionResult.Success &&
            result.value.sent

    override fun close() {
        dismissAllRegistration.close()
        replyRegistration.close()
        dismissRegistration.close()
        summaryPublisher.close()
    }

    companion object {
        const val OWNER_ID = "platform.phone.notification"
        const val MAX_REPLY_BYTES = 1024

        val SUMMARY_KEY =
            DieselStateKey(
                id = "phone.notification.summary",
                valueClass = PhoneNotificationSummary::class.java,
            )

        val DISMISS_KEY =
            DieselActionKey(
                id = "phone.notification.dismiss",
                inputClass = PhoneNotificationDismissRequest::class.java,
                outputClass = PhoneNotificationActionResult::class.java,
            )

        val REPLY_KEY =
            DieselActionKey(
                id = "phone.notification.reply",
                inputClass = PhoneNotificationReplyRequest::class.java,
                outputClass = PhoneNotificationActionResult::class.java,
            )

        val DISMISS_ALL_KEY =
            DieselActionKey(
                id = "phone.notification.dismiss_all",
                inputClass = Unit::class.java,
                outputClass = PhoneNotificationActionResult::class.java,
            )
    }
}
