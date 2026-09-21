// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.integration.gadgetbridge

/**
 * One complete newline-framed Bangle/Gadgetbridge protocol line.
 *
 * Production delegates to NusGattServer.sendLine(), which already owns
 * finite admission, CRLF framing and ATT-MTU chunking.
 */
fun interface BangleLineTransport {
    fun sendLine(
        line: String,
    ): Boolean
}
