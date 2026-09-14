// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

/**
 * Transport boundary for unsolicited Diesel protocol events.
 *
 * Implementations must keep downstream buffering bounded and admit complete
 * event frames atomically. Returning false means the event was not accepted;
 * callers may account for the drop but must not block an observation runtime
 * waiting for transport capacity.
 */
fun interface DieselEventTransport {

    fun send(
        event: DieselEvent,
    ): Boolean
}
