// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.action

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Typed identity for one logical Diesel action.
 *
 * Action keys name platform operations, not transports or concrete providers.
 * For example, a companion media action must not be named after Gadgetbridge,
 * Bangle JSON or NUS even when those implement it today.
 */
class DieselActionKey<I : Any, O : Any>(
    val id: String,
    val inputClass: Class<I>,
    val outputClass: Class<O>,
) {
    init {
        require(
            id.isNotBlank(),
        ) {
            "Action key id must not be blank"
        }
    }

    override fun equals(
        other: Any?,
    ): Boolean =
        other is DieselActionKey<*, *> &&
            id ==
                other.id &&
            inputClass ==
                other.inputClass &&
            outputClass ==
                other.outputClass

    override fun hashCode(): Int =
        31 *
            (
                31 *
                    id.hashCode() +
                    inputClass.hashCode()
            ) +
            outputClass.hashCode()

    override fun toString(): String =
        "DieselActionKey(" +
            "id=$id, " +
            "inputClass=${inputClass.name}, " +
            "outputClass=${outputClass.name}" +
            ")"
}

/**
 * Result of invoking one logical Diesel action.
 *
 * Availability/rejection/failure are explicit application outcomes. Coroutine
 * cancellation is not an action failure and is deliberately propagated.
 */
sealed interface DieselActionResult<out O : Any> {
    data class Success<O : Any>(
        val value: O,
    ) : DieselActionResult<O>

    data object Unavailable :
        DieselActionResult<Nothing>

    data class Rejected(
        val reason: String,
    ) : DieselActionResult<Nothing>

    data class Failed(
        val errorType: String,
        val message: String?,
    ) : DieselActionResult<Nothing>
}

/**
 * Lifecycle handle for one active logical action handler.
 *
 * Closing a stale registration cannot remove a replacement handler. Closing a
 * current registration prevents future dispatches but does not cancel an
 * invocation that already took a handler snapshot.
 */
interface DieselActionRegistration :
    AutoCloseable {
    val isClosed: Boolean
}

/**
 * Process-local dispatcher for logical Diesel operations.
 *
 * Invariants:
 * - at most one active handler per logical action key;
 * - registration establishes the canonical input/output types for an action ID;
 * - an unavailable dispatch does not create or claim an action ID;
 * - handlers are snapshotted under the dispatcher lock and execute outside it;
 * - closing a registration affects future dispatches only;
 * - stale registrations cannot remove replacement handlers;
 * - the dispatcher does not perform provider arbitration or hidden queueing.
 */
class DieselActionDispatcher {
    private data class Slot(
        val inputClass:
            Class<*>,
        val outputClass:
            Class<*>,
        var ownerToken:
            Long? = null,
        var ownerId:
            String? = null,
        var handler:
            (
                suspend (Any) ->
                    DieselActionResult<Any>
            )? = null,
    )

    private data class HandlerSnapshot(
        val handler:
            suspend (Any) ->
                DieselActionResult<Any>,
    )

    private val lock =
        Any()

    private val slots =
        linkedMapOf<
            String,
            Slot,
        >()

    private var nextOwnerToken =
        1L

    fun <I : Any, O : Any> register(
        key: DieselActionKey<I, O>,
        ownerId: String,
        handler:
            suspend (I) ->
                DieselActionResult<O>,
    ): DieselActionRegistration {
        require(
            ownerId.isNotBlank(),
        ) {
            "Action handler owner id must not be blank"
        }

        val token =
            synchronized(
                lock,
            ) {
                val slot =
                    slotForRegistrationLocked(
                        key,
                    )

                check(
                    slot.ownerToken ==
                        null,
                ) {
                    "Action key '${key.id}' already has active handler " +
                        "'${slot.ownerId}'"
                }

                val assigned =
                    nextOwnerToken++

                @Suppress("UNCHECKED_CAST")
                val erasedHandler:
                    suspend (Any) ->
                        DieselActionResult<Any> =
                    {
                        input ->
                        handler(
                            input as I,
                        ) as
                            DieselActionResult<Any>
                    }

                slot.ownerToken =
                    assigned

                slot.ownerId =
                    ownerId

                slot.handler =
                    erasedHandler

                assigned
            }

        return Registration(
            dispatcher =
                this,
            key =
                key,
            token =
                token,
        )
    }

    suspend fun <I : Any, O : Any> dispatch(
        key: DieselActionKey<I, O>,
        input: I,
    ): DieselActionResult<O> {
        val snapshot =
            synchronized(
                lock,
            ) {
                val slot =
                    slots[
                        key.id
                    ]
                        ?: return@synchronized null

                checkTypesLocked(
                    key =
                        key,
                    slot =
                        slot,
                )

                val handler =
                    slot.handler
                        ?: return@synchronized null

                HandlerSnapshot(
                    handler =
                        handler,
                )
            }
                ?: return DieselActionResult.Unavailable

        val result:
            DieselActionResult<Any> =
            try {
                snapshot.handler(
                    input,
                )
            } catch (
                cancellation:
                    CancellationException,
            ) {
                throw cancellation
            } catch (
                error:
                    Exception,
            ) {
                DieselActionResult.Failed(
                    errorType =
                        error.javaClass.name,
                    message =
                        error.message,
                )
            }

        @Suppress("UNCHECKED_CAST")
        return result as
            DieselActionResult<O>
    }

    private fun <I : Any, O : Any> slotForRegistrationLocked(
        key: DieselActionKey<I, O>,
    ): Slot {
        val existing =
            slots[
                key.id
            ]

        if (
            existing !=
                null
        ) {
            checkTypesLocked(
                key =
                    key,
                slot =
                    existing,
            )

            return existing
        }

        return Slot(
            inputClass =
                key.inputClass,
            outputClass =
                key.outputClass,
        ).also {
            slots[
                key.id
            ] =
                it
        }
    }

    private fun <I : Any, O : Any> checkTypesLocked(
        key: DieselActionKey<I, O>,
        slot: Slot,
    ) {
        check(
            slot.inputClass ==
                key.inputClass &&
                slot.outputClass ==
                    key.outputClass,
        ) {
            "Action key '${key.id}' was already registered with " +
                "input '${slot.inputClass.name}' and " +
                "output '${slot.outputClass.name}', not " +
                "input '${key.inputClass.name}' and " +
                "output '${key.outputClass.name}'"
        }
    }

    private fun <I : Any, O : Any> release(
        key: DieselActionKey<I, O>,
        token: Long,
    ) {
        synchronized(
            lock,
        ) {
            val slot =
                slots[
                    key.id
                ]
                    ?: return

            if (
                slot.inputClass !=
                    key.inputClass ||
                slot.outputClass !=
                    key.outputClass ||
                slot.ownerToken !=
                    token
            ) {
                return
            }

            slot.ownerToken =
                null

            slot.ownerId =
                null

            slot.handler =
                null
        }
    }

    private class Registration<I : Any, O : Any>(
        private val dispatcher:
            DieselActionDispatcher,
        private val key:
            DieselActionKey<I, O>,
        private val token:
            Long,
    ) : DieselActionRegistration {
        private val closed =
            AtomicBoolean(
                false,
            )

        override val isClosed: Boolean
            get() =
                closed.get()

        override fun close() {
            if (
                !closed.compareAndSet(
                    false,
                    true,
                )
            ) {
                return
            }

            dispatcher.release(
                key =
                    key,
                token =
                    token,
            )
        }
    }
}
