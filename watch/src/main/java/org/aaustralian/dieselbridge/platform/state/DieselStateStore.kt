// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.state

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Typed identity for one piece of current Diesel process state. */
class DieselStateKey<T : Any>(
    val id: String,
    val valueClass: Class<T>,
) {
    init {
        require(id.isNotBlank()) { "State key id must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is DieselStateKey<*> && id == other.id && valueClass == other.valueClass

    override fun hashCode(): Int = 31 * id.hashCode() + valueClass.hashCode()

    override fun toString(): String = "DieselStateKey(id=$id, valueClass=${valueClass.name})"
}

/** One current-state publication. Revision is monotonic for the lifetime of the logical key. */
data class DieselStateSnapshot<out T : Any>(
    val value: T,
    val revision: Long,
    val updatedAtMs: Long,
    val ownerId: String,
)

/** Lifecycle handle for the single active publisher of one state key. */
interface DieselStatePublisher<T : Any> : AutoCloseable {
    val isClosed: Boolean
    fun set(value: T): Boolean
    fun clear(): Boolean
}

/**
 * Process-local current-state store.
 *
 * State is current truth only: not an event queue, provider selector, or history database.
 * Observers keep a stable StateFlow across publisher replacement. Closing an owner clears its
 * value, and stale handles cannot overwrite a replacement owner.
 */
class DieselStateStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class Slot(
        val valueClass: Class<*>,
        val mutable: MutableStateFlow<DieselStateSnapshot<Any>?>,
        val state: StateFlow<DieselStateSnapshot<Any>?>,
        var ownerToken: Long? = null,
        var ownerId: String? = null,
        var nextRevision: Long = 0L,
    )

    private val lock = Any()
    private val slots = linkedMapOf<String, Slot>()
    private var nextOwnerToken = 1L

    fun <T : Any> observe(key: DieselStateKey<T>): StateFlow<DieselStateSnapshot<T>?> =
        synchronized(lock) {
            val slot = slotForLocked(key)
            @Suppress("UNCHECKED_CAST")
            slot.state as StateFlow<DieselStateSnapshot<T>?>
        }

    fun <T : Any> current(key: DieselStateKey<T>): DieselStateSnapshot<T>? = observe(key).value

    fun <T : Any> registerPublisher(
        key: DieselStateKey<T>,
        ownerId: String,
    ): DieselStatePublisher<T> {
        require(ownerId.isNotBlank()) { "State publisher owner id must not be blank" }
        val token = synchronized(lock) {
            val slot = slotForLocked(key)
            check(slot.ownerToken == null) {
                "State key '${key.id}' already has active publisher '${slot.ownerId}'"
            }
            val assigned = nextOwnerToken++
            slot.ownerToken = assigned
            slot.ownerId = ownerId
            assigned
        }
        return Publisher(this, key, token)
    }

    private fun <T : Any> slotForLocked(key: DieselStateKey<T>): Slot {
        val existing = slots[key.id]
        if (existing != null) {
            check(existing.valueClass == key.valueClass) {
                "State key '${key.id}' was already registered with type " +
                    "'${existing.valueClass.name}', not '${key.valueClass.name}'"
            }
            return existing
        }
        val mutable = MutableStateFlow<DieselStateSnapshot<Any>?>(null)
        return Slot(key.valueClass, mutable, mutable.asStateFlow()).also { slots[key.id] = it }
    }

    private fun <T : Any> publish(key: DieselStateKey<T>, token: Long, value: T): Boolean =
        synchronized(lock) {
            val slot = slots[key.id] ?: return@synchronized false
            if (slot.valueClass != key.valueClass || slot.ownerToken != token) return@synchronized false
            val ownerId = slot.ownerId ?: return@synchronized false
            slot.mutable.value = DieselStateSnapshot(
                value = value,
                revision = ++slot.nextRevision,
                updatedAtMs = clock(),
                ownerId = ownerId,
            )
            true
        }

    private fun <T : Any> clear(key: DieselStateKey<T>, token: Long): Boolean =
        synchronized(lock) {
            val slot = slots[key.id] ?: return@synchronized false
            if (slot.valueClass != key.valueClass || slot.ownerToken != token) return@synchronized false
            if (slot.mutable.value != null) {
                ++slot.nextRevision
                slot.mutable.value = null
            }
            true
        }

    private fun <T : Any> release(key: DieselStateKey<T>, token: Long) {
        synchronized(lock) {
            val slot = slots[key.id] ?: return
            if (slot.valueClass != key.valueClass || slot.ownerToken != token) return
            if (slot.mutable.value != null) {
                ++slot.nextRevision
                slot.mutable.value = null
            }
            slot.ownerToken = null
            slot.ownerId = null
        }
    }

    private class Publisher<T : Any>(
        private val store: DieselStateStore,
        private val key: DieselStateKey<T>,
        private val token: Long,
    ) : DieselStatePublisher<T> {
        private val closed = AtomicBoolean(false)
        override val isClosed: Boolean get() = closed.get()

        override fun set(value: T): Boolean =
            !closed.get() && store.publish(key, token, value)

        override fun clear(): Boolean =
            !closed.get() && store.clear(key, token)

        override fun close() {
            if (closed.compareAndSet(false, true)) store.release(key, token)
        }
    }
}
