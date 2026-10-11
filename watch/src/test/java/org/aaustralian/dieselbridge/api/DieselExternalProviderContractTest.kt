// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselExternalProviderContractTest {
    private val caller =
        DieselCallerIdentity(
            1234,
            listOf("example.client"),
        )

    @Test
    fun newResourcesAreConfinedToCallerNamespace() {
        val allowed =
            DieselExternalProviderPolicy.decide(
                caller,
                DieselProviderResourceRequest(
                    "plugin.example.client.state",
                    DieselResourceKind.STATE,
                    "plugin.example.client.state.v1",
                    "Plugin state",
                ),
                null,
            )

        assertTrue(
            allowed is DieselProviderRegistrationDecision.Allowed,
        )

        val rejected =
            DieselExternalProviderPolicy.decide(
                caller,
                DieselProviderResourceRequest(
                    "plugin.other.client.state",
                    DieselResourceKind.STATE,
                    "plugin.other.client.state.v1",
                    "Other state",
                ),
                null,
            )

        assertEquals(
            DieselProviderRegistrationDecision.Rejected(
                "resource_id_outside_caller_namespace",
            ),
            rejected,
        )
    }

    @Test
    fun externalObservationPriorityIsServerControlled() {
        val existing =
            DieselResourceDescriptor(
                "sensor.observe.accelerometer",
                DieselResourceKind.OBSERVATION,
                "sensor.reading.v1",
                setOf(
                    DieselResourceOperation.SUBSCRIBE,
                    DieselResourceOperation.PROVIDE,
                ),
                "Accelerometer",
            )

        val decision =
            DieselExternalProviderPolicy.decide(
                caller,
                DieselProviderResourceRequest(
                    existing.id,
                    existing.kind,
                    existing.schema,
                    existing.summary,
                ),
                existing,
            ) as
                DieselProviderRegistrationDecision.Allowed

        assertEquals(
            DieselExternalProviderPolicy.EXTERNAL_OBSERVATION_PRIORITY,
            decision.effectivePriority,
        )
    }

    @Test
    fun dynamicCatalogueIsReferenceCounted() {
        val descriptor =
            DieselResourceDescriptor(
                "plugin.example.client.event",
                DieselResourceKind.EVENT,
                "plugin.example.client.event.v1",
                setOf(
                    DieselResourceOperation.SUBSCRIBE,
                    DieselResourceOperation.PUBLISH,
                ),
                "Plugin event",
            )

        val catalog =
            DieselDynamicResourceCatalog(
                emptySet(),
            )

        val first = catalog.retain(descriptor)
        val second = catalog.retain(descriptor)

        assertNotNull(catalog.find(descriptor.id))
        first.close()
        assertNotNull(catalog.find(descriptor.id))
        second.close()
        assertNull(catalog.find(descriptor.id))
    }
}
