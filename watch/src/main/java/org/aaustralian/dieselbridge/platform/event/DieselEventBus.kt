// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local event bus for Diesel platform events.
 *
 * The bus is intentionally independent of Android components so the same
 * event stream can later feed native UI, automation, Espruino and plugins.
 */
class DieselEventBus(
    extraBufferCapacity: Int = 64,
) {

    private val mutableEvents = MutableSharedFlow<DieselEvent>(
        replay = 0,
        extraBufferCapacity = extraBufferCapacity,
    )

    val events: SharedFlow<DieselEvent> =
        mutableEvents.asSharedFlow()

    suspend fun emit(event: DieselEvent) {
        mutableEvents.emit(event)
    }

    fun tryEmit(event: DieselEvent): Boolean =
        mutableEvents.tryEmit(event)
}
