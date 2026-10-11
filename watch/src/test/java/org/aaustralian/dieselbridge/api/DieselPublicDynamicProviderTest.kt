// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import org.aaustralian.dieselbridge.platform.state.DieselStateKey
import org.aaustralian.dieselbridge.platform.state.DieselStateStore
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DieselPublicDynamicProviderTest {
    @Test
    fun dynamicStateReadsFromExistingStateStore() {
        val states =
            DieselStateStore(
                clock = { 900L },
            )

        val api =
            DieselPublicApi(
                dynamicStates = states,
            )

        val descriptor =
            DieselResourceDescriptor(
                "plugin.example.client.state",
                DieselResourceKind.STATE,
                "plugin.example.client.state.v1",
                setOf(
                    DieselResourceOperation.READ,
                    DieselResourceOperation.PROVIDE,
                ),
                "Plugin state",
            )

        val descriptorRegistration =
            api.retainDynamicDescriptor(
                descriptor,
            )

        val publisher =
            states.registerPublisher(
                DieselStateKey(
                    descriptor.id,
                    DieselValue::class.java,
                ),
                "provider-test",
            )

        publisher.set(
            DieselValue.Text(
                "hello",
            ),
        )

        val result =
            api.getState(
                descriptor.id,
            ) as
                DieselPublicStateResult.Success

        assertEquals(
            DieselValue.Text(
                "hello",
            ),
            result.snapshot.value,
        )
        assertEquals(
            900L,
            result.snapshot.updatedAtMs,
        )

        publisher.close()
        descriptorRegistration.close()

        assertNull(
            api.descriptor(
                descriptor.id,
            ),
        )
    }

    @Test
    fun dynamicEventEncodesOnlyItsOwnIngressType() {
        val api =
            DieselPublicApi()

        val descriptor =
            DieselResourceDescriptor(
                "plugin.example.client.tick",
                DieselResourceKind.EVENT,
                "plugin.example.client.tick.v1",
                setOf(
                    DieselResourceOperation.SUBSCRIBE,
                    DieselResourceOperation.PUBLISH,
                ),
                "Plugin tick",
            )

        val registration =
            api.retainDynamicDescriptor(
                descriptor,
            )

        val event =
            DieselPublicIngressEvent(
                descriptor.id,
                123L,
                DieselValue.Integer(
                    7L,
                ),
            )

        val encoded =
            api.encodeEvent(
                descriptor.id,
                event,
            )

        assertNotNull(
            encoded,
        )
        assertEquals(
            DieselValue.Integer(
                7L,
            ),
            encoded?.value,
        )

        assertNull(
            api.encodeEvent(
                descriptor.id,
                DieselPublicIngressEvent(
                    "plugin.example.client.other",
                    124L,
                    DieselValue.Integer(
                        8L,
                    ),
                ),
            ),
        )

        registration.close()
    }
}
