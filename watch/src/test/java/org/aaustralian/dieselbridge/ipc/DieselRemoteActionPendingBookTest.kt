// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.ipc

import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselRemoteActionPendingBookTest {
    @Test
    fun boundedCorrelationRejectsFifthPendingRequest() {
        val book = DieselRemoteActionPendingBook(maximum = 4)

        val first = book.tryOpen()
        val second = book.tryOpen()
        val third = book.tryOpen()
        val fourth = book.tryOpen()

        assertNotNull(first)
        assertNotNull(second)
        assertNotNull(third)
        assertNotNull(fourth)
        assertNull(book.tryOpen())
        assertEquals(4, book.size())

        val result =
            DieselActionResult.Success(
                DieselValue.Integer(9L),
            )

        assertTrue(
            book.complete(
                requireNotNull(first).requestId,
                result,
            ),
        )

        book.remove(first.requestId)
        assertNotNull(book.tryOpen())
    }

    @Test
    fun closeCompletesPendingAsUnavailable() =
        runTest {
            val book = DieselRemoteActionPendingBook(maximum = 1)
            val ticket = requireNotNull(book.tryOpen())

            book.closeAll()

            assertEquals(
                DieselActionResult.Unavailable,
                ticket.deferred.await(),
            )
            assertFalse(
                book.complete(
                    ticket.requestId,
                    DieselActionResult.Success(DieselValue.Null),
                ),
            )
        }
}
