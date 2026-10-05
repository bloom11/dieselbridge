// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.module

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.action.DieselActionDispatcher
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.state.DieselStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DieselModuleManagerTest {
    private fun context() =
        DieselModuleContext(
            capabilities =
                CapabilityRegistry(),
            events =
                DieselEventBus(),
            states =
                DieselStateStore(),
            actions =
                DieselActionDispatcher(),
        )

    @Test
    fun platformModuleManagerSuppliesExactLogicalPlatformPrimitives() =
        runTest {
            val platform =
                DieselPlatform(
                    scope =
                        this,
                )

            var received:
                DieselModuleContext? =
                null

            val module =
                object :
                    DieselModule {
                    override val moduleId =
                        "test.platform-context"

                    override suspend fun start(
                        context: DieselModuleContext,
                    ) {
                        received =
                            context
                    }

                    override suspend fun stop() =
                        Unit
                }

            platform.modules.start(
                module,
            )

            val actual =
                requireNotNull(
                    received,
                )

            assertSame(
                platform.capabilities,
                actual.capabilities,
            )
            assertSame(
                platform.events,
                actual.events,
            )
            assertSame(
                platform.states,
                actual.states,
            )
            assertSame(
                platform.actions,
                actual.actions,
            )

            platform.modules.stopAll()
        }

    @Test
    fun startAndStopTrackModuleOwnership() =
        runTest {
            val events =
                mutableListOf<String>()

            val manager =
                DieselModuleManager(
                    context(),
                )

            manager.start(
                recordingModule(
                    id =
                        "alpha",
                    events =
                        events,
                ),
            )

            assertEquals(
                listOf(
                    "alpha",
                ),
                manager.startedModuleIds(),
            )

            assertTrue(
                manager.stop(
                    "alpha",
                ),
            )

            assertEquals(
                listOf(
                    "start:alpha",
                    "stop:alpha",
                ),
                events,
            )

            assertTrue(
                !manager.stop(
                    "alpha",
                ),
            )
        }

    @Test
    fun duplicateModuleIdIsRejectedWithoutStartingSecondModule() =
        runTest {
            val events =
                mutableListOf<String>()

            val manager =
                DieselModuleManager(
                    context(),
                )

            manager.start(
                recordingModule(
                    id =
                        "same",
                    events =
                        events,
                ),
            )

            try {
                manager.start(
                    recordingModule(
                        id =
                            "same",
                        events =
                            events,
                    ),
                )
                fail(
                    "Expected duplicate module id to fail",
                )
            } catch (
                expected: IllegalStateException,
            ) {
                assertTrue(
                    expected.message
                        .orEmpty()
                        .contains(
                            "already started",
                        ),
                )
            }

            assertEquals(
                listOf(
                    "start:same",
                ),
                events,
            )

            manager.stopAll()
        }

    @Test
    fun stopAllUsesReverseSuccessfulStartOrder() =
        runTest {
            val events =
                mutableListOf<String>()

            val manager =
                DieselModuleManager(
                    context(),
                )

            manager.startAll(
                listOf(
                    recordingModule(
                        id =
                            "one",
                        events =
                            events,
                    ),
                    recordingModule(
                        id =
                            "two",
                        events =
                            events,
                    ),
                    recordingModule(
                        id =
                            "three",
                        events =
                            events,
                    ),
                ),
            )

            manager.stopAll()

            assertEquals(
                listOf(
                    "start:one",
                    "start:two",
                    "start:three",
                    "stop:three",
                    "stop:two",
                    "stop:one",
                ),
                events,
            )
        }

    @Test
    fun failedStartGetsCleanupAndIsNeverRetained() =
        runTest {
            val events =
                mutableListOf<String>()

            val manager =
                DieselModuleManager(
                    context(),
                )

            val failure =
                IllegalStateException(
                    "start failed",
                )

            try {
                manager.start(
                    recordingModule(
                        id =
                            "broken",
                        events =
                            events,
                        startFailure =
                            failure,
                    ),
                )
                fail(
                    "Expected start failure",
                )
            } catch (
                actual: IllegalStateException,
            ) {
                assertSame(
                    failure,
                    actual,
                )
            }

            assertEquals(
                listOf(
                    "start:broken",
                    "stop:broken",
                ),
                events,
            )
            assertEquals(
                emptyList<String>(),
                manager.startedModuleIds(),
            )
        }

    @Test
    fun startAllRollsBackOnlyModulesStartedByThatCall() =
        runTest {
            val events =
                mutableListOf<String>()

            val manager =
                DieselModuleManager(
                    context(),
                )

            manager.start(
                recordingModule(
                    id =
                        "existing",
                    events =
                        events,
                ),
            )

            val failure =
                IllegalArgumentException(
                    "second start failed",
                )

            try {
                manager.startAll(
                    listOf(
                        recordingModule(
                            id =
                                "first",
                            events =
                                events,
                        ),
                        recordingModule(
                            id =
                                "second",
                            events =
                                events,
                            startFailure =
                                failure,
                        ),
                    ),
                )
                fail(
                    "Expected batch start failure",
                )
            } catch (
                actual: IllegalArgumentException,
            ) {
                assertSame(
                    failure,
                    actual,
                )
            }

            assertEquals(
                listOf(
                    "existing",
                ),
                manager.startedModuleIds(),
            )

            assertEquals(
                listOf(
                    "start:existing",
                    "start:first",
                    "start:second",
                    "stop:second",
                    "stop:first",
                ),
                events,
            )

            manager.stopAll()
        }

    @Test
    fun stopAllAttemptsRemainingModulesAfterFailureAndKeepsFailedOwnerTracked() =
        runTest {
            val events =
                mutableListOf<String>()

            val manager =
                DieselModuleManager(
                    context(),
                )

            val failure =
                IllegalStateException(
                    "stop failed",
                )

            manager.startAll(
                listOf(
                    recordingModule(
                        id =
                            "one",
                        events =
                            events,
                    ),
                    recordingModule(
                        id =
                            "two",
                        events =
                            events,
                        stopFailure =
                            failure,
                    ),
                    recordingModule(
                        id =
                            "three",
                        events =
                            events,
                    ),
                ),
            )

            try {
                manager.stopAll()
                fail(
                    "Expected stopAll failure",
                )
            } catch (
                actual: IllegalStateException,
            ) {
                assertSame(
                    failure,
                    actual,
                )
            }

            assertEquals(
                listOf(
                    "two",
                ),
                manager.startedModuleIds(),
            )

            assertEquals(
                listOf(
                    "start:one",
                    "start:two",
                    "start:three",
                    "stop:three",
                    "stop:two",
                    "stop:one",
                ),
                events,
            )
        }

    @Test
    fun failedStartPreservesPrimaryErrorAndSuppressesCleanupFailure() =
        runTest {
            val manager =
                DieselModuleManager(
                    context(),
                )

            val startFailure =
                IllegalArgumentException(
                    "start",
                )

            val stopFailure =
                IllegalStateException(
                    "cleanup",
                )

            try {
                manager.start(
                    recordingModule(
                        id =
                            "broken",
                        events =
                            mutableListOf(),
                        startFailure =
                            startFailure,
                        stopFailure =
                            stopFailure,
                    ),
                )
                fail(
                    "Expected start failure",
                )
            } catch (
                actual: IllegalArgumentException,
            ) {
                assertSame(
                    startFailure,
                    actual,
                )
                assertEquals(
                    listOf(
                        stopFailure,
                    ),
                    actual.suppressed.toList(),
                )
            }
        }

    @Test
    fun lifecycleMutationWaitsForInFlightStartAndRemainsSerialized() =
        runTest {
            val manager =
                DieselModuleManager(
                    context(),
                )

            val startEntered =
                CompletableDeferred<Unit>()

            val releaseStart =
                CompletableDeferred<Unit>()

            val first =
                object :
                    DieselModule {
                    override val moduleId =
                        "first"

                    override suspend fun start(
                        context: DieselModuleContext,
                    ) {
                        startEntered.complete(
                            Unit,
                        )
                        releaseStart.await()
                    }

                    override suspend fun stop() =
                        Unit
                }

            val firstStart =
                async {
                    manager.start(
                        first,
                    )
                }

            startEntered.await()

            val secondStart =
                async {
                    manager.start(
                        recordingModule(
                            id =
                                "second",
                            events =
                                mutableListOf(),
                        ),
                    )
                }

            assertTrue(
                !secondStart.isCompleted,
            )

            releaseStart.complete(
                Unit,
            )

            firstStart.await()
            secondStart.await()

            assertEquals(
                listOf(
                    "first",
                    "second",
                ),
                manager.startedModuleIds(),
            )

            manager.stopAll()
        }

    @Test
    fun cancellationFromStartStillRunsCleanupAndPropagates() =
        runTest {
            val events =
                mutableListOf<String>()

            val manager =
                DieselModuleManager(
                    context(),
                )

            val cancellation =
                CancellationException(
                    "cancel start",
                )

            try {
                manager.start(
                    recordingModule(
                        id =
                            "cancelled",
                        events =
                            events,
                        startFailure =
                            cancellation,
                    ),
                )
                fail(
                    "Expected cancellation",
                )
            } catch (
                actual: CancellationException,
            ) {
                assertSame(
                    cancellation,
                    actual,
                )
            }

            assertEquals(
                listOf(
                    "start:cancelled",
                    "stop:cancelled",
                ),
                events,
            )
        }

    private fun recordingModule(
        id: String,
        events: MutableList<String>,
        startFailure: Throwable? = null,
        stopFailure: Throwable? = null,
    ): DieselModule =
        object :
            DieselModule {
            override val moduleId =
                id

            override suspend fun start(
                context: DieselModuleContext,
            ) {
                events +=
                    "start:$id"

                startFailure
                    ?.let {
                        throw it
                    }
            }

            override suspend fun stop() {
                events +=
                    "stop:$id"

                stopFailure
                    ?.let {
                        throw it
                    }
            }
        }
}
