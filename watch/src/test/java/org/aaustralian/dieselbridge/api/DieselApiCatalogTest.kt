// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselApiCatalogTest {
    @Test
    fun snapshotIsDeterministicAndLookupUsesStableIds() {
        val action =
            DieselResourceDescriptor(
                id = "test.action",
                kind = DieselResourceKind.ACTION,
                schema = "test.action.v1",
                operations = setOf(DieselResourceOperation.INVOKE),
                summary = "Test action",
            )
        val state =
            DieselResourceDescriptor(
                id = "test.state",
                kind = DieselResourceKind.STATE,
                schema = "test.state.v1",
                operations = setOf(DieselResourceOperation.READ),
                summary = "Test state",
            )
        val catalog = DieselApiCatalog(descriptors = listOf(state, action))

        assertEquals(DieselApiVersion.CURRENT, catalog.snapshot().apiVersion)
        assertEquals(listOf(action, state), catalog.snapshot().resources)
        assertEquals(state, catalog.find("test.state"))
        assertNull(catalog.find("missing"))
    }

    @Test
    fun duplicatePublicIdsAreRejected() {
        val first =
            DieselResourceDescriptor(
                id = "test.duplicate",
                kind = DieselResourceKind.STATE,
                schema = "test.duplicate.state.v1",
                operations = setOf(DieselResourceOperation.READ),
                summary = "First",
            )
        val second =
            DieselResourceDescriptor(
                id = "test.duplicate",
                kind = DieselResourceKind.ACTION,
                schema = "test.duplicate.action.v1",
                operations = setOf(DieselResourceOperation.INVOKE),
                summary = "Second",
            )

        try {
            DieselApiCatalog(descriptors = listOf(first, second))
            throw AssertionError("Expected duplicate public id rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Duplicate"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidOperationForResourceKindIsRejected() {
        DieselResourceDescriptor(
            id = "test.bad",
            kind = DieselResourceKind.STATE,
            schema = "test.bad.v1",
            operations = setOf(DieselResourceOperation.OPEN),
            summary = "Invalid state",
        )
    }
}
