// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc
import android.os.Bundle
import org.aaustralian.dieselbridge.api.DieselPublicEncodedEvent
internal object DieselIpcEventCodec {
    fun opened(id: Long) = Bundle().apply {
        putString("status", DieselIpcCodec.STATUS_OK); putLong("subscriptionId", id)
    }
    fun rejected(reason: String) = Bundle().apply {
        putString("status", DieselIpcCodec.STATUS_REJECTED); putString("reason", reason)
    }
    fun batch(id: Long, events: List<DieselPublicEncodedEvent>, drops: Long) = Bundle().apply {
        putString("kind", "event_batch"); putLong("subscriptionId", id); putLong("ipcDroppedTotal", drops)
        putParcelableArrayList("events", ArrayList(events.map { event -> Bundle().apply {
            putString("resourceId", event.resourceId); putLong("timestampMs", event.timestampMs)
            putBundle("value", DieselIpcCodec.encodeValue(event.value))
        }}))
    }
}
