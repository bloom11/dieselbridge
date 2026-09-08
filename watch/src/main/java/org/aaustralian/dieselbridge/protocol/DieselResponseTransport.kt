// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.protocol

/**
 * Transport boundary for asynchronous Diesel responses.
 *
 * Command execution does not need to know whether a response is carried over
 * BLE/Gadgetbridge, Binder, a future companion transport, or a test fake.
 */
fun interface DieselResponseTransport {

    /**
     * Returns true when the response was accepted by the underlying transport.
     *
     * This does not imply that an Android receiver consumed the response.
     * Execution and overload feedback may call this concurrently. Implementations
     * must admit complete frames atomically and keep any downstream buffering
     * bounded; return false when no capacity is available.
     */
    fun send(
        response: DieselResponse,
    ): Boolean
}
