// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.gateway

/**
 * One-way M6.0b transport seam.
 *
 * Returning normally means only that local submission completed. It does not
 * mean Gadgetbridge was connected, that the watch received the request, or
 * that any response was acknowledged.
 */
fun interface PhoneDieselTransport {

    fun submit(
        line: String,
    )
}
