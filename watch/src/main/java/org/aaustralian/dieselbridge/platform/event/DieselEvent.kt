// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.event

/**
 * Base type for asynchronous events exposed by the Diesel platform.
 *
 * Events will eventually include notification, media, call, connection,
 * battery and sensor changes.
 */
interface DieselEvent {
    val type: String
    val timestampMs: Long
}
