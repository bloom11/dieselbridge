// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselPublicTransportContractTest {
    @Test
    fun retainedTransportIsDiscoverableAndOpenableByPolicy() {
        val api =
            DieselPublicApi()

        val descriptor =
            DieselResourceDescriptor(
                id =
                    "gadgetbridge.bangle.console",
                kind =
                    DieselResourceKind.TRANSPORT,
                schema =
                    "transport.bytes.v1",
                operations =
                    setOf(
                        DieselResourceOperation.OPEN,
                    ),
                summary =
                    "Bangle console transport",
            )

        val registration =
            api.retainTransportDescriptor(
                descriptor,
            )

        assertTrue(
            api.hasTransport(
                descriptor.id,
            ),
        )
        assertNotNull(
            api.catalog()
                .resources
                .firstOrNull {
                    it.id ==
                        descriptor.id
                },
        )

        val policy =
            DieselPublicAccessPolicy()

        assertTrue(
            policy.canAccess(
                DieselCallerIdentity(
                    1234,
                    listOf(
                        "example.client",
                    ),
                ),
                descriptor,
                DieselResourceOperation.OPEN,
            ),
        )

        registration.close()

        assertFalse(
            api.hasTransport(
                descriptor.id,
            ),
        )
        assertNull(
            api.descriptor(
                descriptor.id,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun transportRegistrationRejectsAnotherResourceKind() {
        DieselPublicApi()
            .retainTransportDescriptor(
                DieselResourceDescriptor(
                    id =
                        "plugin.example.state",
                    kind =
                        DieselResourceKind.STATE,
                    schema =
                        "plugin.example.state.v1",
                    operations =
                        setOf(
                            DieselResourceOperation.READ,
                        ),
                    summary =
                        "Not a transport",
                ),
            )
    }
}
