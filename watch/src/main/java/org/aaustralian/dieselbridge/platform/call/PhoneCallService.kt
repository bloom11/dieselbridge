// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.call

import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.action.DieselActionKey
import org.aaustralian.dieselbridge.platform.action.DieselActionRegistration
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.aaustralian.dieselbridge.platform.event.DieselEvent
import org.aaustralian.dieselbridge.platform.state.DieselStateKey
import org.aaustralian.dieselbridge.platform.state.DieselStatePublisher

enum class PhoneCallPhase(
    val wireName: String,
) {
    IDLE("idle"),
    RINGING("ringing"),
    ACTIVE("active"),
}

enum class PhoneCallDirection(
    val wireName: String,
) {
    NONE("none"),
    INCOMING("incoming"),
    OUTGOING("outgoing"),
    UNKNOWN("unknown"),
}

data class PhoneCallState(
    val phase: PhoneCallPhase,
    val direction: PhoneCallDirection,
    val name: String?,
    val number: String?,
    val updatedAtMs: Long,
) {
    init {
        require(updatedAtMs >= 0L)
        if (phase == PhoneCallPhase.IDLE) {
            require(direction == PhoneCallDirection.NONE)
            require(name == null)
            require(number == null)
        } else {
            require(direction != PhoneCallDirection.NONE)
        }
    }
}

data class PhoneCallActionResult(
    val sent: Boolean,
    val phase: PhoneCallPhase,
)

sealed interface PhoneCallEvent : DieselEvent

data class PhoneCallChangedEvent(
    val state: PhoneCallState,
    val reason: String,
    override val timestampMs: Long = state.updatedAtMs,
) : PhoneCallEvent {
    override val type: String =
        CHANGED_EVENT_ID

    companion object {
        const val CHANGED_EVENT_ID =
            "phone.call.changed"
    }
}

/**
 * Diesel owner for the current phone-call service.
 *
 * Gadgetbridge/Bangle is only a transport adapter. Inbound call messages update
 * this canonical state first and are then projected into the existing
 * CallController UI/ringtone implementation.
 *
 * Public and native UI actions share the same Diesel action registrations and
 * the same outbound call sender. Local optimistic projection happens only
 * after the transport accepts the command.
 */
class PhoneCallService(
    private val platform: DieselPlatform,
    private val sendAction: (String) -> Boolean,
    private val onInboundProjection:
        (
            cmd: String,
            name: String?,
            number: String?,
        ) -> Unit = { _, _, _ -> },
    private val onLocalProjection:
        (String) -> Unit = {},
    private val clockMs: () -> Long =
        System::currentTimeMillis,
) : AutoCloseable {
    private val lock =
        Any()

    private var currentState =
        idleState(
            clockMs(),
        )

    private val publisher:
        DieselStatePublisher<PhoneCallState> =
        platform.states.registerPublisher(
            key =
                CURRENT_KEY,
            ownerId =
                OWNER_ID,
        )

    private val acceptRegistration =
        registerAction(
            ACCEPT_KEY,
            ACTION_ACCEPT,
        )

    private val rejectRegistration =
        registerAction(
            REJECT_KEY,
            ACTION_REJECT,
        )

    private val ignoreRegistration =
        registerAction(
            IGNORE_KEY,
            ACTION_IGNORE,
        )

    private val endRegistration =
        registerAction(
            END_KEY,
            ACTION_END,
        )

    init {
        check(
            publisher.set(
                currentState,
            ),
        )
    }

    fun current(): PhoneCallState =
        synchronized(lock) {
            currentState
        }

    fun receiveInbound(
        cmd: String,
        name: String?,
        number: String?,
    ): Boolean {
        val normalized =
            cmd.lowercase()

        val next =
            synchronized(lock) {
                val now =
                    clockMs()

                val computed =
                    when (normalized) {
                        "incoming" ->
                            PhoneCallState(
                                phase =
                                    PhoneCallPhase.RINGING,
                                direction =
                                    PhoneCallDirection.INCOMING,
                                name =
                                    name,
                                number =
                                    number,
                                updatedAtMs =
                                    now,
                            )

                        "outgoing" ->
                            PhoneCallState(
                                phase =
                                    PhoneCallPhase.ACTIVE,
                                direction =
                                    PhoneCallDirection.OUTGOING,
                                name =
                                    name,
                                number =
                                    number,
                                updatedAtMs =
                                    now,
                            )

                        "accept",
                        "start",
                        ->
                            PhoneCallState(
                                phase =
                                    PhoneCallPhase.ACTIVE,
                                direction =
                                    when (
                                        currentState.direction
                                    ) {
                                        PhoneCallDirection.NONE ->
                                            PhoneCallDirection.UNKNOWN

                                        else ->
                                            currentState.direction
                                    },
                                name =
                                    name
                                        ?: currentState.name,
                                number =
                                    number
                                        ?: currentState.number,
                                updatedAtMs =
                                    now,
                            )

                        "end",
                        "reject",
                        "ignore",
                        ->
                            idleState(
                                now,
                            )

                        else ->
                            return false
                    }

                publishLocked(
                    state =
                        computed,
                    reason =
                        "phone_$normalized",
                )

                computed
            }

        runCatching {
            onInboundProjection(
                normalized,
                next.name
                    ?: name,
                next.number
                    ?: number,
            )
        }

        return true
    }

    /**
     * Native UI compatibility path. The UI does not own call state anymore;
     * it asks this same action owner used by public Binder clients.
     */
    fun performLegacyAction(
        action: String,
    ): Boolean =
        when (
            val result =
                performAction(
                    action,
                )
        ) {
            is DieselActionResult.Success ->
                result.value.sent

            else ->
                false
        }

    private fun registerAction(
        key:
            DieselActionKey<
                Unit,
                PhoneCallActionResult,
            >,
        action: String,
    ): DieselActionRegistration =
        platform.actions.register(
            key =
                key,
            ownerId =
                OWNER_ID,
        ) {
            performAction(
                action,
            )
        }

    private fun performAction(
        action: String,
    ): DieselActionResult<PhoneCallActionResult> {
        var project =
            false

        val result:
            DieselActionResult<PhoneCallActionResult> =
            synchronized(lock) {
                val current =
                    currentState

                val invalidReason =
                    when (action) {
                        ACTION_ACCEPT,
                        ACTION_REJECT,
                        ACTION_IGNORE,
                        ->
                            if (
                                current.phase !=
                                PhoneCallPhase.RINGING
                            ) {
                                "call_not_ringing"
                            } else {
                                null
                            }

                        ACTION_END ->
                            if (
                                current.phase !=
                                PhoneCallPhase.ACTIVE
                            ) {
                                "call_not_active"
                            } else {
                                null
                            }

                        else ->
                            "unsupported_call_action"
                    }

                if (
                    invalidReason !=
                    null
                ) {
                    return@synchronized DieselActionResult.Rejected(
                        invalidReason,
                    )
                }

                if (
                    !sendAction(
                        action,
                    )
                ) {
                    return@synchronized DieselActionResult.Unavailable
                }

                val next =
                    when (action) {
                        ACTION_ACCEPT ->
                            current.copy(
                                phase =
                                    PhoneCallPhase.ACTIVE,
                                updatedAtMs =
                                    clockMs(),
                            )

                        ACTION_REJECT,
                        ACTION_IGNORE,
                        ACTION_END,
                        ->
                            idleState(
                                clockMs(),
                            )

                        else ->
                            error(
                                "unreachable action $action",
                            )
                    }

                publishLocked(
                    state =
                        next,
                    reason =
                        "local_${action.lowercase()}",
                )

                project =
                    true

                DieselActionResult.Success(
                    PhoneCallActionResult(
                        sent =
                            true,
                        phase =
                            next.phase,
                    ),
                )
            }

        if (
            project
        ) {
            runCatching {
                onLocalProjection(
                    action,
                )
            }
        }

        return result
    }

    private fun publishLocked(
        state: PhoneCallState,
        reason: String,
    ) {
        currentState =
            state

        check(
            publisher.set(
                state,
            ),
        ) {
            "Phone call state publisher is closed"
        }

        platform.events.tryEmit(
            PhoneCallChangedEvent(
                state =
                    state,
                reason =
                    reason,
            ),
        )
    }

    override fun close() {
        endRegistration.close()
        ignoreRegistration.close()
        rejectRegistration.close()
        acceptRegistration.close()
        publisher.close()
    }

    companion object {
        const val OWNER_ID =
            "platform.phone.call"

        const val ACTION_ACCEPT =
            "ACCEPT"

        const val ACTION_REJECT =
            "REJECT"

        const val ACTION_IGNORE =
            "IGNORE"

        const val ACTION_END =
            "END"

        val CURRENT_KEY =
            DieselStateKey(
                id =
                    "phone.call.current",
                valueClass =
                    PhoneCallState::class.java,
            )

        val ACCEPT_KEY =
            DieselActionKey(
                id =
                    "phone.call.accept",
                inputClass =
                    Unit::class.java,
                outputClass =
                    PhoneCallActionResult::class.java,
            )

        val REJECT_KEY =
            DieselActionKey(
                id =
                    "phone.call.reject",
                inputClass =
                    Unit::class.java,
                outputClass =
                    PhoneCallActionResult::class.java,
            )

        val IGNORE_KEY =
            DieselActionKey(
                id =
                    "phone.call.ignore",
                inputClass =
                    Unit::class.java,
                outputClass =
                    PhoneCallActionResult::class.java,
            )

        val END_KEY =
            DieselActionKey(
                id =
                    "phone.call.end",
                inputClass =
                    Unit::class.java,
                outputClass =
                    PhoneCallActionResult::class.java,
            )

        private fun idleState(
            timestampMs: Long,
        ) =
            PhoneCallState(
                phase =
                    PhoneCallPhase.IDLE,
                direction =
                    PhoneCallDirection.NONE,
                name =
                    null,
                number =
                    null,
                updatedAtMs =
                    timestampMs,
            )
    }
}
