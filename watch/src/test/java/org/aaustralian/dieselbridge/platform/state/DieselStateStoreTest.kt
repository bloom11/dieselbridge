// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.state

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DieselStateStoreTest {
    private val key =
        DieselStateKey(
            "companion.test.state",
            String::class.java,
        )

    @Test
    fun observerIsStableAcrossPublisherReplacement() {
        var now =
            1_000L

        val store =
            DieselStateStore {
                now
            }

        val observed =
            store.observe(
                key,
            )

        assertSame(
            observed,
            store.observe(
                key,
            ),
        )

        assertNull(
            observed.value,
        )

        val first =
            store.registerPublisher(
                key,
                "first",
            )

        assertTrue(
            first.set(
                "alpha",
            ),
        )

        val alpha =
            requireNotNull(
                observed.value,
            )

        assertEquals(
            "first",
            alpha.ownerId,
        )

        assertEquals(
            1_000L,
            alpha.updatedAtMs,
        )

        first.close()

        assertNull(
            observed.value,
        )

        now =
            2_000L

        val second =
            store.registerPublisher(
                key,
                "second",
            )

        assertTrue(
            second.set(
                "beta",
            ),
        )

        val beta =
            requireNotNull(
                observed.value,
            )

        assertEquals(
            "beta",
            beta.value,
        )

        assertEquals(
            "second",
            beta.ownerId,
        )

        assertTrue(
            beta.revision >
                alpha.revision,
        )

        second.close()
    }

    @Test
    fun onlyOnePublisherMayOwnAKey() {
        val store =
            DieselStateStore()

        val first =
            store.registerPublisher(
                key,
                "first",
            )

        try {
            store.registerPublisher(
                key,
                "second",
            )

            fail(
                "Expected duplicate publisher registration to fail",
            )
        } catch (
            expected:
                IllegalStateException,
        ) {
            assertTrue(
                expected.message
                    .orEmpty()
                    .contains(
                        "already has active publisher",
                    ),
            )
        } finally {
            first.close()
        }
    }

    @Test
    fun stalePublisherCannotOverwriteReplacementOwner() {
        val store =
            DieselStateStore()

        val first =
            store.registerPublisher(
                key,
                "first",
            )

        assertTrue(
            first.set(
                "old",
            ),
        )

        first.close()

        val second =
            store.registerPublisher(
                key,
                "second",
            )

        assertTrue(
            second.set(
                "new",
            ),
        )

        assertFalse(
            first.set(
                "stale",
            ),
        )

        assertFalse(
            first.clear(),
        )

        assertEquals(
            "new",
            store.current(
                key,
            )?.value,
        )

        second.close()
    }

    @Test
    fun clearRetainsPublisherOwnership() {
        val store =
            DieselStateStore()

        val publisher =
            store.registerPublisher(
                key,
                "owner",
            )

        assertTrue(
            publisher.set(
                "first",
            ),
        )

        val firstRevision =
            requireNotNull(
                store.current(
                    key,
                ),
            ).revision

        assertTrue(
            publisher.clear(),
        )

        assertNull(
            store.current(
                key,
            ),
        )

        assertTrue(
            publisher.set(
                "second",
            ),
        )

        assertTrue(
            requireNotNull(
                store.current(
                    key,
                ),
            ).revision >
                firstRevision,
        )

        publisher.close()
    }

    @Test
    fun logicalKeyCannotChangeType() {
        val store =
            DieselStateStore()

        store.observe(
            key,
        )

        val incompatible =
            DieselStateKey(
                key.id,
                java.lang.Integer::class.java,
            )

        try {
            store.observe(
                incompatible,
            )

            fail(
                "Expected state-key type mismatch to fail",
            )
        } catch (
            expected:
                IllegalStateException,
        ) {
            assertTrue(
                expected.message
                    .orEmpty()
                    .contains(
                        "already registered with type",
                    ),
            )
        }
    }

    @Test
    fun closingEmptyPublisherLeavesKeyAvailable() {
        val store =
            DieselStateStore()

        store.registerPublisher(
            key,
            "first",
        ).close()

        val second =
            store.registerPublisher(
                key,
                "second",
            )

        assertFalse(
            second.isClosed,
        )

        assertTrue(
            second.set(
                "value",
            ),
        )

        second.close()
    }

    @Test
    fun concurrentSetAndCloseAlwaysLeavesNoStateOwnedByClosedPublisher() {
        repeat(
            64,
        ) {
            iteration ->
            val store =
                DieselStateStore()

            val publisher =
                store.registerPublisher(
                    key,
                    "owner-$iteration",
                )

            val ready =
                CountDownLatch(
                    2,
                )

            val start =
                CountDownLatch(
                    1,
                )

            val done =
                CountDownLatch(
                    2,
                )

            val failure =
                AtomicReference<
                    Throwable?
                >(
                    null,
                )

            val setter =
                thread(
                    start =
                        false,
                    name =
                        "state-setter-$iteration",
                ) {
                    ready.countDown()

                    try {
                        start.await()

                        repeat(
                            128,
                        ) {
                            sample ->
                            publisher.set(
                                "value-$sample",
                            )
                        }
                    } catch (
                        error:
                            Throwable,
                    ) {
                        failure.compareAndSet(
                            null,
                            error,
                        )
                    } finally {
                        done.countDown()
                    }
                }

            val closer =
                thread(
                    start =
                        false,
                    name =
                        "state-closer-$iteration",
                ) {
                    ready.countDown()

                    try {
                        start.await()
                        publisher.close()
                    } catch (
                        error:
                            Throwable,
                    ) {
                        failure.compareAndSet(
                            null,
                            error,
                        )
                    } finally {
                        done.countDown()
                    }
                }

            setter.start()
            closer.start()

            assertTrue(
                "workers failed to become ready",
                ready.await(
                    5,
                    TimeUnit.SECONDS,
                ),
            )

            start.countDown()

            assertTrue(
                "workers failed to complete",
                done.await(
                    5,
                    TimeUnit.SECONDS,
                ),
            )

            failure.get()
                ?.let {
                    throw it
                }

            assertTrue(
                publisher.isClosed,
            )

            assertNull(
                store.current(
                    key,
                ),
            )

            assertFalse(
                publisher.set(
                    "late",
                ),
            )
        }
    }

    @Test
    fun closeAndConcurrentReplacementEventuallyTransfersExclusiveOwnership() {
        repeat(
            64,
        ) {
            iteration ->
            val store =
                DieselStateStore()

            val first =
                store.registerPublisher(
                    key,
                    "first-$iteration",
                )

            assertTrue(
                first.set(
                    "old",
                ),
            )

            val start =
                CountDownLatch(
                    1,
                )

            val done =
                CountDownLatch(
                    2,
                )

            val replacement =
                AtomicReference<
                    DieselStatePublisher<String>?
                >(
                    null,
                )

            val failure =
                AtomicReference<
                    Throwable?
                >(
                    null,
                )

            val closer =
                thread(
                    start =
                        false,
                    name =
                        "state-handoff-close-$iteration",
                ) {
                    try {
                        start.await()
                        first.close()
                    } catch (
                        error:
                            Throwable,
                    ) {
                        failure.compareAndSet(
                            null,
                            error,
                        )
                    } finally {
                        done.countDown()
                    }
                }

            val registrar =
                thread(
                    start =
                        false,
                    name =
                        "state-handoff-register-$iteration",
                ) {
                    try {
                        start.await()

                        val deadline =
                            System.nanoTime() +
                                TimeUnit.SECONDS
                                    .toNanos(
                                        5,
                                    )

                        while (
                            replacement.get() ==
                                null &&
                            System.nanoTime() <
                                deadline
                        ) {
                            try {
                                val second =
                                    store.registerPublisher(
                                        key,
                                        "second-$iteration",
                                    )

                                replacement.set(
                                    second,
                                )
                            } catch (
                                expected:
                                    IllegalStateException,
                            ) {
                                Thread.yield()
                            }
                        }

                        checkNotNull(
                            replacement.get(),
                        ) {
                            "replacement publisher never acquired ownership"
                        }
                    } catch (
                        error:
                            Throwable,
                    ) {
                        failure.compareAndSet(
                            null,
                            error,
                        )
                    } finally {
                        done.countDown()
                    }
                }

            closer.start()
            registrar.start()
            start.countDown()

            assertTrue(
                "handoff workers failed to complete",
                done.await(
                    7,
                    TimeUnit.SECONDS,
                ),
            )

            failure.get()
                ?.let {
                    throw it
                }

            val second =
                requireNotNull(
                    replacement.get(),
                )

            assertTrue(
                second.set(
                    "new",
                ),
            )

            val current =
                requireNotNull(
                    store.current(
                        key,
                    ),
                )

            assertEquals(
                "new",
                current.value,
            )

            assertEquals(
                "second-$iteration",
                current.ownerId,
            )

            second.close()
        }
    }

    @Test
    fun concurrentCallsOnStaleOwnerCannotClearReplacementState() {
        val store =
            DieselStateStore()

        val first =
            store.registerPublisher(
                key,
                "first",
            )

        assertTrue(
            first.set(
                "old",
            ),
        )

        first.close()

        val second =
            store.registerPublisher(
                key,
                "second",
            )

        assertTrue(
            second.set(
                "new",
            ),
        )

        val staleMutationSucceeded =
            AtomicBoolean(
                false,
            )

        val workers =
            List(
                16,
            ) {
                worker ->
                thread(
                    start =
                        false,
                    name =
                        "state-stale-owner-$worker",
                ) {
                    repeat(
                        128,
                    ) {
                        first.close()

                        if (
                            first.set(
                                "stale",
                            ) ||
                            first.clear()
                        ) {
                            staleMutationSucceeded.set(
                                true,
                            )
                        }
                    }
                }
            }

        workers.forEach {
            it.start()
        }

        workers.forEach {
            it.join(
                5_000,
            )

            assertFalse(
                "stale-owner worker did not finish",
                it.isAlive,
            )
        }

        assertFalse(
            staleMutationSucceeded.get(),
        )

        val current =
            requireNotNull(
                store.current(
                    key,
                ),
            )

        assertEquals(
            "new",
            current.value,
        )

        assertEquals(
            "second",
            current.ownerId,
        )

        second.close()
    }

    @Test
    fun concurrentPublicationsNeverRegressObservedRevision() {
        val store =
            DieselStateStore()

        val publisher =
            store.registerPublisher(
                key,
                "writer",
            )

        val observedRevisions =
            CopyOnWriteArrayList<
                Long
            >()

        val collectorReady =
            CountDownLatch(
                1,
            )

        val collectorFailure =
            AtomicReference<
                Throwable?
            >(
                null,
            )

        val collector =
            thread(
                start =
                    true,
                name =
                    "state-revision-collector",
            ) {
                try {
                    runBlocking {
                        store.observe(
                            key,
                        )
                            .onStart {
                                collectorReady.countDown()
                            }
                            .filterNotNull()
                            .onEach {
                                snapshot ->
                                observedRevisions +=
                                    snapshot.revision
                            }
                            .first {
                                snapshot ->
                                snapshot.value ==
                                    "final"
                            }
                    }
                } catch (
                    error:
                        Throwable,
                ) {
                    collectorFailure.compareAndSet(
                        null,
                        error,
                    )
                }
            }

        assertTrue(
            "collector failed to subscribe",
            collectorReady.await(
                5,
                TimeUnit.SECONDS,
            ),
        )

        val writersReady =
            CountDownLatch(
                8,
            )

        val writersStart =
            CountDownLatch(
                1,
            )

        val writersDone =
            CountDownLatch(
                8,
            )

        val writerFailure =
            AtomicReference<
                Throwable?
            >(
                null,
            )

        val writers =
            List(
                8,
            ) {
                worker ->
                thread(
                    start =
                        false,
                    name =
                        "state-revision-writer-$worker",
                ) {
                    writersReady.countDown()

                    try {
                        writersStart.await()

                        repeat(
                            128,
                        ) {
                            sample ->
                            check(
                                publisher.set(
                                    "writer-$worker-$sample",
                                ),
                            )
                        }
                    } catch (
                        error:
                            Throwable,
                    ) {
                        writerFailure.compareAndSet(
                            null,
                            error,
                        )
                    } finally {
                        writersDone.countDown()
                    }
                }
            }

        writers.forEach {
            it.start()
        }

        assertTrue(
            "writers failed to become ready",
            writersReady.await(
                5,
                TimeUnit.SECONDS,
            ),
        )

        writersStart.countDown()

        assertTrue(
            "writers failed to finish",
            writersDone.await(
                10,
                TimeUnit.SECONDS,
            ),
        )

        writerFailure.get()
            ?.let {
                throw it
            }

        assertTrue(
            publisher.set(
                "final",
            ),
        )

        collector.join(
            5_000,
        )

        assertFalse(
            "collector did not observe final publication",
            collector.isAlive,
        )

        collectorFailure.get()
            ?.let {
                throw it
            }

        assertTrue(
            observedRevisions.isNotEmpty(),
        )

        assertTrue(
            observedRevisions
                .zipWithNext()
                .all {
                    (
                        previous,
                        next,
                    ) ->
                    next >
                        previous
                },
        )

        val final =
            requireNotNull(
                store.current(
                    key,
                ),
            )

        assertEquals(
            observedRevisions.last(),
            final.revision,
        )

        assertEquals(
            "final",
            final.value,
        )

        publisher.close()
    }
}
