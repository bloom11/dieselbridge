// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.action

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DieselActionDispatcherTest {
    private val key =
        DieselActionKey(
            id =
                "companion.test.echo",
            inputClass =
                String::class.java,
            outputClass =
                String::class.java,
        )

    @Test
    fun dispatchWithoutHandlerIsUnavailableAndDoesNotClaimType() =
        runTest {
            val dispatcher =
                DieselActionDispatcher()

            assertEquals(
                DieselActionResult.Unavailable,
                dispatcher.dispatch(
                    key =
                        DieselActionKey(
                            id =
                                key.id,
                            inputClass =
                                CharSequence::class.java,
                            outputClass =
                                CharSequence::class.java,
                        ),
                    input =
                        "before-registration",
                ),
            )

            val registration =
                dispatcher.register(
                    key =
                        key,
                    ownerId =
                        "owner",
                ) {
                    value ->
                    DieselActionResult.Success(
                        value,
                    )
                }

            assertEquals(
                DieselActionResult.Success(
                    "ok",
                ),
                dispatcher.dispatch(
                    key =
                        key,
                    input =
                        "ok",
                ),
            )

            registration.close()
        }

    @Test
    fun registeredHandlerReceivesInputAndReturnsTypedResult() =
        runTest {
            val dispatcher =
                DieselActionDispatcher()

            val registration =
                dispatcher.register(
                    key =
                        key,
                    ownerId =
                        "owner",
                ) {
                    value ->
                    DieselActionResult.Success(
                        value.uppercase(),
                    )
                }

            assertEquals(
                DieselActionResult.Success(
                    "DIESEL",
                ),
                dispatcher.dispatch(
                    key =
                        key,
                    input =
                        "diesel",
                ),
            )

            registration.close()

            assertEquals(
                DieselActionResult.Unavailable,
                dispatcher.dispatch(
                    key =
                        key,
                    input =
                        "after-close",
                ),
            )
        }

    @Test
    fun onlyOneActiveHandlerMayOwnAnAction() {
        val dispatcher =
            DieselActionDispatcher()

        val first =
            dispatcher.register(
                key =
                    key,
                ownerId =
                    "first",
            ) {
                value ->
                DieselActionResult.Success(
                    value,
                )
            }

        try {
            dispatcher.register(
                key =
                    key,
                ownerId =
                    "second",
            ) {
                value ->
                DieselActionResult.Success(
                    value,
                )
            }

            fail(
                "Expected duplicate action handler registration to fail",
            )
        } catch (
            expected:
                IllegalStateException,
        ) {
            assertTrue(
                expected.message
                    .orEmpty()
                    .contains(
                        "already has active handler",
                    ),
            )
        } finally {
            first.close()
        }
    }

    @Test
    fun actionIdTypeIdentityPersistsAcrossHandlerReplacement() {
        val dispatcher =
            DieselActionDispatcher()

        dispatcher.register(
            key =
                key,
            ownerId =
                "first",
        ) {
            value ->
            DieselActionResult.Success(
                value,
            )
        }.close()

        val incompatible =
            DieselActionKey(
                id =
                    key.id,
                inputClass =
                    CharSequence::class.java,
                outputClass =
                    String::class.java,
            )

        try {
            dispatcher.register(
                key =
                    incompatible,
                ownerId =
                    "second",
            ) {
                value ->
                DieselActionResult.Success(
                    value.toString(),
                )
            }

            fail(
                "Expected action-key type mismatch to fail",
            )
        } catch (
            expected:
                IllegalStateException,
        ) {
            assertTrue(
                expected.message
                    .orEmpty()
                    .contains(
                        "already registered with",
                    ),
            )
        }
    }

    @Test
    fun staleRegistrationCannotRemoveReplacementHandler() =
        runTest {
            val dispatcher =
                DieselActionDispatcher()

            val first =
                dispatcher.register(
                    key =
                        key,
                    ownerId =
                        "first",
                ) {
                    DieselActionResult.Success(
                        "old",
                    )
                }

            first.close()

            val second =
                dispatcher.register(
                    key =
                        key,
                    ownerId =
                        "second",
                ) {
                    DieselActionResult.Success(
                        "new",
                    )
                }

            repeat(
                16,
            ) {
                first.close()
            }

            assertTrue(
                first.isClosed,
            )

            assertEquals(
                DieselActionResult.Success(
                    "new",
                ),
                dispatcher.dispatch(
                    key =
                        key,
                    input =
                        "ignored",
                ),
            )

            second.close()
        }

    @Test
    fun inFlightInvocationMayFinishAfterRegistrationCloses() =
        runTest {
            val dispatcher =
                DieselActionDispatcher()

            val started =
                CompletableDeferred<
                    Unit
                >()

            val release =
                CompletableDeferred<
                    Unit
                >()

            val registration =
                dispatcher.register(
                    key =
                        key,
                    ownerId =
                        "owner",
                ) {
                    started.complete(
                        Unit,
                    )

                    release.await()

                    DieselActionResult.Success(
                        "finished",
                    )
                }

            val inFlight =
                async {
                    dispatcher.dispatch(
                        key =
                            key,
                        input =
                            "start",
                    )
                }

            started.await()

            registration.close()

            assertEquals(
                DieselActionResult.Unavailable,
                dispatcher.dispatch(
                    key =
                        key,
                    input =
                        "future",
                ),
            )

            release.complete(
                Unit,
            )

            assertEquals(
                DieselActionResult.Success(
                    "finished",
                ),
                inFlight.await(),
            )
        }

    @Test
    fun handlerExceptionBecomesExplicitFailedResult() =
        runTest {
            val dispatcher =
                DieselActionDispatcher()

            val registration =
                dispatcher.register(
                    key =
                        key,
                    ownerId =
                        "owner",
                ) {
                    throw IllegalArgumentException(
                        "bad input",
                    )
                }

            val result =
                dispatcher.dispatch(
                    key =
                        key,
                    input =
                        "value",
                )

            assertTrue(
                result is
                    DieselActionResult.Failed,
            )

            result as
                DieselActionResult.Failed

            assertEquals(
                IllegalArgumentException::class.java.name,
                result.errorType,
            )

            assertEquals(
                "bad input",
                result.message,
            )

            registration.close()
        }

    @Test
    fun coroutineCancellationIsPropagatedNotConvertedToFailure() =
        runTest {
            val dispatcher =
                DieselActionDispatcher()

            val registration =
                dispatcher.register(
                    key =
                        key,
                    ownerId =
                        "owner",
                ) {
                    throw CancellationException(
                        "cancel action",
                    )
                }

            try {
                dispatcher.dispatch(
                    key =
                        key,
                    input =
                        "value",
                )

                fail(
                    "Expected CancellationException",
                )
            } catch (
                expected:
                    CancellationException,
            ) {
                assertEquals(
                    "cancel action",
                    expected.message,
                )
            } finally {
                registration.close()
            }
        }

    @Test
    fun concurrentRegistrationHasExactlyOneWinner() {
        val dispatcher =
            DieselActionDispatcher()

        val ready =
            CountDownLatch(
                16,
            )

        val start =
            CountDownLatch(
                1,
            )

        val done =
            CountDownLatch(
                16,
            )

        val winners =
            AtomicInteger(
                0,
            )

        val winner =
            AtomicReference<
                DieselActionRegistration?
            >(
                null,
            )

        val unexpected =
            AtomicReference<
                Throwable?
            >(
                null,
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
                        "action-register-$worker",
                ) {
                    ready.countDown()

                    try {
                        start.await()

                        try {
                            val registration =
                                dispatcher.register(
                                    key =
                                        key,
                                    ownerId =
                                        "owner-$worker",
                                ) {
                                    value ->
                                    DieselActionResult.Success(
                                        value,
                                    )
                                }

                            winners.incrementAndGet()
                            winner.set(
                                registration,
                            )
                        } catch (
                            expected:
                                IllegalStateException,
                        ) {
                            // Another worker owns the key.
                        }
                    } catch (
                        error:
                            Throwable,
                    ) {
                        unexpected.compareAndSet(
                            null,
                            error,
                        )
                    } finally {
                        done.countDown()
                    }
                }
            }

        workers.forEach {
            it.start()
        }

        assertTrue(
            ready.await(
                5,
                TimeUnit.SECONDS,
            ),
        )

        start.countDown()

        assertTrue(
            done.await(
                5,
                TimeUnit.SECONDS,
            ),
        )

        unexpected.get()
            ?.let {
                throw it
            }

        assertEquals(
            1,
            winners.get(),
        )

        val registration =
            requireNotNull(
                winner.get(),
            )

        assertFalse(
            registration.isClosed,
        )

        registration.close()
    }

    @Test
    fun blockedHandlerDoesNotHoldDispatcherOwnershipLock() =
        runTest {
            val dispatcher =
                DieselActionDispatcher()

            val firstStarted =
                CompletableDeferred<
                    Unit
                >()

            val releaseFirst =
                CompletableDeferred<
                    Unit
                >()

            val first =
                dispatcher.register(
                    key =
                        key,
                    ownerId =
                        "first",
                ) {
                    firstStarted.complete(
                        Unit,
                    )

                    releaseFirst.await()

                    DieselActionResult.Success(
                        "first-result",
                    )
                }

            val firstCall =
                async {
                    dispatcher.dispatch(
                        key =
                            key,
                        input =
                            "first-call",
                    )
                }

            firstStarted.await()

            first.close()

            val second =
                dispatcher.register(
                    key =
                        key,
                    ownerId =
                        "second",
                ) {
                    DieselActionResult.Success(
                        "second-result",
                    )
                }

            assertEquals(
                DieselActionResult.Success(
                    "second-result",
                ),
                dispatcher.dispatch(
                    key =
                        key,
                    input =
                        "second-call",
                ),
            )

            releaseFirst.complete(
                Unit,
            )

            assertEquals(
                DieselActionResult.Success(
                    "first-result",
                ),
                firstCall.await(),
            )

            second.close()
        }
}
