// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.module

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.aaustralian.dieselbridge.platform.action.DieselActionDispatcher
import org.aaustralian.dieselbridge.platform.capability.CapabilityRegistry
import org.aaustralian.dieselbridge.platform.event.DieselEventBus
import org.aaustralian.dieselbridge.platform.state.DieselStateStore

data class DieselModuleContext(
    val capabilities: CapabilityRegistry,
    val events: DieselEventBus,
    val states: DieselStateStore,
    val actions: DieselActionDispatcher,
)

interface DieselModule {
    val moduleId: String

    suspend fun start(
        context: DieselModuleContext,
    )

    suspend fun stop()
}

class DieselModuleManager(
    private val context: DieselModuleContext,
) {
    private val lifecycleMutex = Mutex()

    private val started =
        linkedMapOf<String, DieselModule>()

    suspend fun start(
        module: DieselModule,
    ) {
        lifecycleMutex.withLock {
            startLocked(
                module,
            )
        }
    }

    suspend fun startAll(
        modules: Iterable<DieselModule>,
    ) {
        lifecycleMutex.withLock {
            val startedByCall =
                mutableListOf<String>()

            try {
                modules.forEach {
                    module ->
                    startLocked(
                        module,
                    )
                    startedByCall +=
                        module.moduleId
                }
            } catch (
                error: Throwable,
            ) {
                rollbackLocked(
                    moduleIds =
                        startedByCall.asReversed(),
                    primary =
                        error,
                )
                throw error
            }
        }
    }

    suspend fun stop(
        moduleId: String,
    ): Boolean =
        lifecycleMutex.withLock {
            val module =
                started[moduleId]
                    ?: return@withLock false

            module.stop()
            started.remove(
                moduleId,
            )
            true
        }

    suspend fun stopAll() {
        lifecycleMutex.withLock {
            val failures =
                mutableListOf<Throwable>()

            started.keys
                .toList()
                .asReversed()
                .forEach {
                    moduleId ->
                    val module =
                        started[moduleId]
                            ?: return@forEach

                    try {
                        withContext(
                            NonCancellable,
                        ) {
                            module.stop()
                        }
                        started.remove(
                            moduleId,
                        )
                    } catch (
                        error: Throwable,
                    ) {
                        failures +=
                            error
                    }
                }

            if (
                failures.isNotEmpty()
            ) {
                val primary =
                    failures.first()

                failures
                    .drop(
                        1,
                    )
                    .forEach {
                        later ->
                        if (
                            later !==
                                primary
                        ) {
                            primary.addSuppressed(
                                later,
                            )
                        }
                    }

                throw primary
            }
        }
    }

    suspend fun startedModuleIds():
        List<String> =
        lifecycleMutex.withLock {
            started.keys.toList()
        }

    private suspend fun startLocked(
        module: DieselModule,
    ) {
        val moduleId =
            module.moduleId

        require(
            moduleId.isNotBlank(),
        ) {
            "Module id must not be blank"
        }

        check(
            moduleId !in
                started,
        ) {
            "Module '$moduleId' is already started"
        }

        try {
            module.start(
                context,
            )
        } catch (
            error: Throwable,
        ) {
            cleanupFailedStart(
                module =
                    module,
                primary =
                    error,
            )
            throw error
        }

        started[moduleId] =
            module
    }

    private suspend fun cleanupFailedStart(
        module: DieselModule,
        primary: Throwable,
    ) {
        try {
            withContext(
                NonCancellable,
            ) {
                module.stop()
            }
        } catch (
            cleanup: Throwable,
        ) {
            if (
                cleanup !==
                    primary
            ) {
                primary.addSuppressed(
                    cleanup,
                )
            }
        }
    }

    private suspend fun rollbackLocked(
        moduleIds: List<String>,
        primary: Throwable,
    ) {
        moduleIds.forEach {
            moduleId ->
            val module =
                started[moduleId]
                    ?: return@forEach

            try {
                withContext(
                    NonCancellable,
                ) {
                    module.stop()
                }
                started.remove(
                    moduleId,
                )
            } catch (
                cleanup: Throwable,
            ) {
                if (
                    cleanup !==
                        primary
                ) {
                    primary.addSuppressed(
                        cleanup,
                    )
                }
            }
        }
    }
}
