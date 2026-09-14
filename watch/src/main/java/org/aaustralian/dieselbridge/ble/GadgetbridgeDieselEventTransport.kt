// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ble

import org.aaustralian.dieselbridge.protocol.DieselEvent
import org.aaustralian.dieselbridge.protocol.DieselEventCodec
import org.aaustralian.dieselbridge.protocol.DieselEventTransport

/**
 * Diesel event transport backed by the existing bounded NUS line sender.
 */
class GadgetbridgeDieselEventTransport(
    private val sendLine: (String) -> Boolean,
) : DieselEventTransport {

    override fun send(
        event: DieselEvent,
    ): Boolean =
        sendLine(
            DieselEventCodec
                .encodeGadgetbridgeIntent(
                    event,
                ),
        )
}
