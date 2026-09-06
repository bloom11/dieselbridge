// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ble

import org.aaustralian.dieselbridge.protocol.DieselResponse
import org.aaustralian.dieselbridge.protocol.DieselResponseCodec
import org.aaustralian.dieselbridge.protocol.DieselResponseTransport

/**
 * Diesel response transport backed by the existing NUS line sender.
 *
 * Keeping the sender as a function makes this class independent from Android
 * Bluetooth classes and directly unit-testable.
 */
class GadgetbridgeDieselResponseTransport(
    private val sendLine: (String) -> Boolean,
) : DieselResponseTransport {

    override fun send(
        response: DieselResponse,
    ): Boolean =
        sendLine(
            DieselResponseCodec
                .encodeGadgetbridgeIntent(
                    response,
                ),
        )
}
