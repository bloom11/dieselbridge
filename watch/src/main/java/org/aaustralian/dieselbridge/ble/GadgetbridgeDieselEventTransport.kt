// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ble

import org.aaustralian.dieselbridge.protocol.DieselEvent
import org.aaustralian.dieselbridge.protocol.DieselEventCodec
import org.aaustralian.dieselbridge.protocol.DieselEventTransport

class GadgetbridgeDieselEventTransport(
    private val androidPackage: String? = null,
    private val androidClass: String? = null,
    private val sendLine: (String) -> Boolean,
) : DieselEventTransport {
    override fun send(event: DieselEvent): Boolean =
        sendLine(
            DieselEventCodec.encodeGadgetbridgeIntent(
                event = event,
                androidPackage = androidPackage,
                androidClass = androidClass,
            ),
        )
}
