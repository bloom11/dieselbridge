// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import org.aaustralian.dieselbridge.companion.CompanionAlarmStateOwner
import org.aaustralian.dieselbridge.companion.CompanionNextAlarmState
import org.aaustralian.dieselbridge.data.WatchNotification
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.action.WatchVibrationAction
import org.aaustralian.dieselbridge.platform.action.WatchVibrationRequest
import org.aaustralian.dieselbridge.platform.action.WatchVibrationResult
import org.aaustralian.dieselbridge.platform.event.SensorObservationSampleEvent
import org.aaustralian.dieselbridge.platform.event.SensorObservationStateEvent
import org.aaustralian.dieselbridge.platform.notification.PhoneNotificationActionResult
import org.aaustralian.dieselbridge.platform.notification.PhoneNotificationDismissRequest
import org.aaustralian.dieselbridge.platform.notification.PhoneNotificationReceivedEvent
import org.aaustralian.dieselbridge.platform.notification.PhoneNotificationRemovedEvent
import org.aaustralian.dieselbridge.platform.notification.PhoneNotificationReplyRequest
import org.aaustralian.dieselbridge.platform.notification.PhoneNotificationService
import org.aaustralian.dieselbridge.platform.notification.PhoneNotificationSummary
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityCatalog
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorObservationCapabilityId
import org.aaustralian.dieselbridge.protocol.DieselValue

object DieselDefaultPublicApi {
    val COMPANION_NEXT_ALARM = DieselResourceDescriptor(
        CompanionAlarmStateOwner.KEY.id,
        DieselResourceKind.STATE,
        "companion.alarm.next.v1",
        setOf(DieselResourceOperation.READ),
        "Current next-alarm occurrence synchronized from the phone companion",
    )

    val WATCH_VIBRATION = DieselResourceDescriptor(
        WatchVibrationAction.ID,
        DieselResourceKind.ACTION,
        "watch.vibration.buzz.v1",
        setOf(DieselResourceOperation.INVOKE),
        "Request bounded watch vibration through the active Diesel provider",
    )

    val PHONE_NOTIFICATION_SUMMARY = DieselResourceDescriptor(
        PhoneNotificationService.SUMMARY_KEY.id,
        DieselResourceKind.STATE,
        "phone.notification.summary.v1",
        setOf(DieselResourceOperation.READ),
        "Current summary of active phone notifications bridged to the watch",
    )

    val PHONE_NOTIFICATION_DISMISS = DieselResourceDescriptor(
        PhoneNotificationService.DISMISS_KEY.id,
        DieselResourceKind.ACTION,
        "phone.notification.dismiss.v1",
        setOf(DieselResourceOperation.INVOKE),
        "Dismiss one active phone notification",
    )

    val PHONE_NOTIFICATION_REPLY = DieselResourceDescriptor(
        PhoneNotificationService.REPLY_KEY.id,
        DieselResourceKind.ACTION,
        "phone.notification.reply.v1",
        setOf(DieselResourceOperation.INVOKE),
        "Reply to one active replyable phone notification",
    )

    val PHONE_NOTIFICATION_DISMISS_ALL = DieselResourceDescriptor(
        PhoneNotificationService.DISMISS_ALL_KEY.id,
        DieselResourceKind.ACTION,
        "phone.notification.dismiss-all.v1",
        setOf(DieselResourceOperation.INVOKE),
        "Dismiss all active phone notifications",
    )

    val PHONE_NOTIFICATION_RECEIVED = DieselResourceDescriptor(
        PhoneNotificationReceivedEvent.RECEIVED_EVENT_ID,
        DieselResourceKind.EVENT,
        "phone.notification.received.v1",
        setOf(DieselResourceOperation.SUBSCRIBE),
        "A phone notification was received or updated",
    )

    val PHONE_NOTIFICATION_REMOVED = DieselResourceDescriptor(
        PhoneNotificationRemovedEvent.REMOVED_EVENT_ID,
        DieselResourceKind.EVENT,
        "phone.notification.removed.v1",
        setOf(DieselResourceOperation.SUBSCRIBE),
        "An active phone notification was removed",
    )

    val PHONE_CANNED_REPLIES =
        PhoneCannedRepliesPublicApi.STATE

    val PHONE_CALL_CURRENT =
        PhoneCallPublicApi.CURRENT

    val PHONE_CALL_ACCEPT =
        PhoneCallPublicApi.ACCEPT

    val PHONE_CALL_REJECT =
        PhoneCallPublicApi.REJECT

    val PHONE_CALL_IGNORE =
        PhoneCallPublicApi.IGNORE

    val PHONE_CALL_END =
        PhoneCallPublicApi.END

    val PHONE_CALL_CHANGED =
        PhoneCallPublicApi.CHANGED

    val SENSOR_SAMPLE_EVENT = DieselResourceDescriptor(
        SensorObservationSampleEvent.TYPE,
        DieselResourceKind.EVENT,
        "sensor.sample.v1",
        setOf(DieselResourceOperation.SUBSCRIBE),
        "Samples from sensor observations owned by the same public session",
    )

    val SENSOR_STATE_EVENT = DieselResourceDescriptor(
        SensorObservationStateEvent.TYPE,
        DieselResourceKind.EVENT,
        "sensor.subscription.state.v1",
        setOf(DieselResourceOperation.SUBSCRIBE),
        "Lifecycle changes from sensor observations owned by the same public session",
    )

    val NOTIFICATION_STATE_IDS =
        setOf(
            PHONE_NOTIFICATION_SUMMARY.id,
        )

    val NOTIFICATION_ACTION_IDS =
        setOf(
            PHONE_NOTIFICATION_DISMISS.id,
            PHONE_NOTIFICATION_REPLY.id,
            PHONE_NOTIFICATION_DISMISS_ALL.id,
        )

    val NOTIFICATION_EVENT_IDS =
        setOf(
            PHONE_NOTIFICATION_RECEIVED.id,
            PHONE_NOTIFICATION_REMOVED.id,
        )

    val CANNED_REPLY_STATE_IDS =
        PhoneCannedRepliesPublicApi.STATE_IDS

    val CALL_STATE_IDS =
        PhoneCallPublicApi.STATE_IDS

    val CALL_ACTION_IDS =
        PhoneCallPublicApi.ACTION_IDS

    val CALL_EVENT_IDS =
        PhoneCallPublicApi.EVENT_IDS

    val EVENT_IDS: Set<String> =
        setOf(
            SENSOR_SAMPLE_EVENT.id,
            SENSOR_STATE_EVENT.id,
        ) +
            NOTIFICATION_EVENT_IDS +
            CALL_EVENT_IDS

    val OBSERVATIONS: List<DieselPublicObservationBinding> =
        SensorCapabilityCatalog.standardLogicalIds.map { logicalId ->
            val id = SensorObservationCapabilityId.forLogical(logicalId).value
            DieselPublicObservationBinding(
                DieselResourceDescriptor(
                    id,
                    DieselResourceKind.OBSERVATION,
                    "sensor.reading.v1",
                    setOf(
                        DieselResourceOperation.SUBSCRIBE,
                        DieselResourceOperation.PROVIDE,
                    ),
                    "Provider-neutral logical $logicalId observation",
                ),
                logicalId,
            )
        }

    val OBSERVATION_IDS: Set<String> = OBSERVATIONS.map { it.descriptor.id }.toSet()

    fun create(platform: DieselPlatform): DieselPublicApi = DieselPublicApi(
        stateBindings = listOf(
            DieselPublicStateBinding.fromStateStore(
                platform.states,
                COMPANION_NEXT_ALARM,
                CompanionAlarmStateOwner.KEY,
                ::encodeAlarm,
            ),
            DieselPublicStateBinding.fromStateStore(
                platform.states,
                PHONE_NOTIFICATION_SUMMARY,
                PhoneNotificationService.SUMMARY_KEY,
                ::encodeNotificationSummary,
            ),
            PhoneCannedRepliesPublicApi.stateBinding(
                platform.states,
            ),
            PhoneCallPublicApi.stateBinding(
                platform.states,
            ),
        ),
        actionBindings =
            listOf(
            DieselPublicActionBinding.fromActionDispatcher(
                platform.actions,
                WATCH_VIBRATION,
                WatchVibrationAction.KEY,
                ::decodeVibration,
                ::encodeVibration,
            ),
            DieselPublicActionBinding.fromActionDispatcher(
                platform.actions,
                PHONE_NOTIFICATION_DISMISS,
                PhoneNotificationService.DISMISS_KEY,
                ::decodeNotificationDismiss,
                ::encodeNotificationActionResult,
            ),
            DieselPublicActionBinding.fromActionDispatcher(
                platform.actions,
                PHONE_NOTIFICATION_REPLY,
                PhoneNotificationService.REPLY_KEY,
                ::decodeNotificationReply,
                ::encodeNotificationActionResult,
            ),
            DieselPublicActionBinding.fromActionDispatcher(
                platform.actions,
                PHONE_NOTIFICATION_DISMISS_ALL,
                PhoneNotificationService.DISMISS_ALL_KEY,
                ::decodeNotificationDismissAll,
                ::encodeNotificationActionResult,
            ),
        ) +
            PhoneCallPublicApi.actionBindings(
                platform.actions,
            ),
        observationBindings = OBSERVATIONS,
        dynamicStates = platform.states,
        dynamicActions = platform.actions,
        eventBindings = listOf(
            DieselPublicEventBinding(
                SENSOR_SAMPLE_EVENT,
                { it is SensorObservationSampleEvent },
                { encodeSampleEvent(it as SensorObservationSampleEvent) },
            ),
            DieselPublicEventBinding(
                SENSOR_STATE_EVENT,
                { it is SensorObservationStateEvent },
                { encodeStateEvent(it as SensorObservationStateEvent) },
            ),
            DieselPublicEventBinding(
                PHONE_NOTIFICATION_RECEIVED,
                { it is PhoneNotificationReceivedEvent },
                {
                    encodeNotification(
                        (it as PhoneNotificationReceivedEvent).notification,
                    )
                },
            ),
            DieselPublicEventBinding(
                PHONE_NOTIFICATION_REMOVED,
                { it is PhoneNotificationRemovedEvent },
                {
                    encodeNotificationRemoved(
                        it as PhoneNotificationRemovedEvent,
                    )
                },
            ),
            PhoneCallPublicApi.eventBinding(),
        ),
    )

    private fun decodeVibration(value: DieselValue): DieselPublicDecodeResult<WatchVibrationRequest> {
        val fields = (value as? DieselValue.ObjectValue)?.value
            ?: return DieselPublicDecodeResult.Invalid("object_required")
        if (fields.keys != setOf("durationMs")) return DieselPublicDecodeResult.Invalid("invalid_fields")
        val duration = (fields["durationMs"] as? DieselValue.Integer)?.value
            ?: return DieselPublicDecodeResult.Invalid("duration_integer_required")
        if (duration !in WatchVibrationAction.MIN_DURATION_MS..WatchVibrationAction.MAX_DURATION_MS) {
            return DieselPublicDecodeResult.Invalid("duration_out_of_bounds")
        }
        return DieselPublicDecodeResult.Value(WatchVibrationRequest(duration))
    }

    private fun decodeNotificationDismiss(
        value: DieselValue,
    ): DieselPublicDecodeResult<PhoneNotificationDismissRequest> {
        val fields =
            (value as? DieselValue.ObjectValue)?.value
                ?: return DieselPublicDecodeResult.Invalid("object_required")

        if (fields.keys != setOf("id")) {
            return DieselPublicDecodeResult.Invalid("invalid_fields")
        }

        val id =
            (fields["id"] as? DieselValue.Integer)?.value
                ?: return DieselPublicDecodeResult.Invalid("id_integer_required")

        return DieselPublicDecodeResult.Value(
            PhoneNotificationDismissRequest(
                id = id,
            ),
        )
    }

    private fun decodeNotificationReply(
        value: DieselValue,
    ): DieselPublicDecodeResult<PhoneNotificationReplyRequest> {
        val fields =
            (value as? DieselValue.ObjectValue)?.value
                ?: return DieselPublicDecodeResult.Invalid("object_required")

        if (fields.keys != setOf("id", "text")) {
            return DieselPublicDecodeResult.Invalid("invalid_fields")
        }

        val id =
            (fields["id"] as? DieselValue.Integer)?.value
                ?: return DieselPublicDecodeResult.Invalid("id_integer_required")

        val text =
            (fields["text"] as? DieselValue.Text)?.value
                ?: return DieselPublicDecodeResult.Invalid("text_required")

        if (text.isEmpty()) {
            return DieselPublicDecodeResult.Invalid("reply_empty")
        }

        if (text.toByteArray(Charsets.UTF_8).size > PhoneNotificationService.MAX_REPLY_BYTES) {
            return DieselPublicDecodeResult.Invalid("reply_too_large")
        }

        return DieselPublicDecodeResult.Value(
            PhoneNotificationReplyRequest(
                id = id,
                text = text,
            ),
        )
    }

    private fun decodeNotificationDismissAll(
        value: DieselValue,
    ): DieselPublicDecodeResult<Unit> =
        when (value) {
            DieselValue.Null ->
                DieselPublicDecodeResult.Value(Unit)

            is DieselValue.ObjectValue ->
                if (value.value.isEmpty()) {
                    DieselPublicDecodeResult.Value(Unit)
                } else {
                    DieselPublicDecodeResult.Invalid("empty_object_required")
                }

            else ->
                DieselPublicDecodeResult.Invalid("null_or_empty_object_required")
        }

    private fun encodeVibration(result: WatchVibrationResult): DieselValue =
        DieselValue.ObjectValue(mapOf("durationMs" to DieselValue.Integer(result.durationMs)))

    private fun encodeNotificationActionResult(
        result: PhoneNotificationActionResult,
    ): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf(
                "sent" to DieselValue.Flag(result.sent),
                "affectedCount" to DieselValue.Integer(result.affectedCount.toLong()),
            ),
        )

    private fun encodeAlarm(state: CompanionNextAlarmState): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf<String, DieselValue>().apply {
                put("kind", DieselValue.Text(state.kind.wireName))
                put("observedAtMs", DieselValue.Integer(state.observedAtMs))
                put("receivedAtMs", DieselValue.Integer(state.receivedAtMs))
                state.triggerAtMs?.let { put("triggerAtMs", DieselValue.Integer(it)) }
                state.reason?.let { put("reason", DieselValue.Text(it)) }
            },
        )

    private fun encodeNotificationSummary(
        summary: PhoneNotificationSummary,
    ): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf(
                "activeCount" to DieselValue.Integer(summary.activeCount.toLong()),
                "latest" to (
                    summary.latest?.let(::encodeNotification)
                        ?: DieselValue.Null
                ),
            ),
        )

    private fun encodeNotification(
        notification: WatchNotification,
    ): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf<String, DieselValue>().apply {
                put("id", DieselValue.Integer(notification.id))
                put("receivedAtMs", DieselValue.Integer(notification.receivedAt))
                put("replyable", DieselValue.Flag(notification.replyable))
                notification.app?.let { put("app", DieselValue.Text(it)) }
                notification.title?.let { put("title", DieselValue.Text(it)) }
                notification.body?.let { put("body", DieselValue.Text(it)) }
                notification.sender?.let { put("sender", DieselValue.Text(it)) }
            },
        )

    private fun encodeNotificationRemoved(
        event: PhoneNotificationRemovedEvent,
    ): DieselValue =
        DieselValue.ObjectValue(
            linkedMapOf(
                "id" to DieselValue.Integer(event.notificationId),
                "reason" to DieselValue.Text(event.reason),
            ),
        )

    private fun encodeSampleEvent(event: SensorObservationSampleEvent): DieselValue {
        val reading = event.sample.reading
        return DieselValue.ObjectValue(
            linkedMapOf<String, DieselValue>().apply {
                put("subscriptionId", DieselValue.Integer(event.subscriptionId))
                put("logicalId", DieselValue.Text(event.logicalId))
                put("sequence", DieselValue.Integer(event.sample.sequence))
                put("timestampNanos", DieselValue.Integer(reading.timestampNanos))
                reading.accuracy?.let { put("accuracy", DieselValue.Integer(it.toLong())) }
                put("values", DieselValue.ListValue(reading.values.map { DieselValue.Decimal(it.toDouble()) }))
                put("providerDroppedTotal", DieselValue.Integer(event.providerDroppedTotal))
                put("subscriptionDroppedTotal", DieselValue.Integer(event.subscriptionDroppedTotal))
            },
        )
    }

    private fun encodeStateEvent(event: SensorObservationStateEvent): DieselValue {
        val state = event.state
        return DieselValue.ObjectValue(
            linkedMapOf<String, DieselValue>().apply {
                put("subscriptionId", DieselValue.Integer(event.subscriptionId))
                put("logicalId", DieselValue.Text(event.logicalId))
                put("phase", DieselValue.Text(state.phase.name.lowercase()))
                put("requestedPeriodMs", DieselValue.Integer(state.requestedPeriodMs))
                state.acquisitionPeriodMs?.let { put("acquisitionPeriodMs", DieselValue.Integer(it)) }
                state.providerEffectivePeriodMs?.let { put("providerEffectivePeriodMs", DieselValue.Integer(it)) }
                state.providerConfiguredPeriodMs?.let { put("providerConfiguredPeriodMs", DieselValue.Integer(it)) }
                state.reason?.let { put("reason", DieselValue.Text(it)) }
                put("providerDroppedTotal", DieselValue.Integer(event.providerDroppedTotal))
                put("subscriptionDroppedTotal", DieselValue.Integer(event.subscriptionDroppedTotal))
            },
        )
    }
}
