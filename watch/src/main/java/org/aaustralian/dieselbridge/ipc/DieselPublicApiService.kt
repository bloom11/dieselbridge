// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ipc

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.aaustralian.dieselbridge.api.DieselCallerIdentity
import org.aaustralian.dieselbridge.api.DieselPublicAccessPolicy
import org.aaustralian.dieselbridge.api.DieselPublicActionResult
import org.aaustralian.dieselbridge.api.DieselPublicDecodeResult
import org.aaustralian.dieselbridge.api.DieselPublicRuntimeAccess
import org.aaustralian.dieselbridge.api.DieselPublicRuntimeContext
import org.aaustralian.dieselbridge.api.DieselPublicStateResult
import org.aaustralian.dieselbridge.api.DieselResourceKind
import org.aaustralian.dieselbridge.api.DieselResourceOperation

/**
 * Exported Binder adapter for the one DieselBridgeService-owned runtime.
 *
 * It does not construct DieselPlatform, own BLE, select providers, store
 * logical state, or execute platform actions directly.
 */
class DieselPublicApiService :
    Service() {

    private val serviceScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default,
        )

    private val lock =
        Any()

    private val sessions =
        linkedMapOf<Long, RemoteSession>()

    private var nextSessionId =
        1L

    private val accessPolicy =
        DieselPublicAccessPolicy()

    private var observedRuntime:
        DieselPublicRuntimeContext? =
        null

    override fun onCreate() {
        super.onCreate()

        observedRuntime =
            DieselPublicRuntimeAccess
                .runtime
                .value

        serviceScope.launch {
            DieselPublicRuntimeAccess
                .runtime
                .drop(
                    1,
                )
                .collect {
                    current ->
                    val previous =
                        observedRuntime

                    observedRuntime =
                        current

                    if (
                        previous != null &&
                        current !== previous
                    ) {
                        closeAllSessions(
                            "runtime_detached",
                        )
                    }
                }
        }
    }

    override fun onBind(
        intent: Intent?,
    ): IBinder =
        rootBinder

    override fun onDestroy() {
        closeAllSessions(
            "service_destroyed",
        )

        serviceScope.cancel()

        super.onDestroy()
    }

    private val rootBinder =
        object :
            IDieselPublicService.Stub() {

            override fun getServiceInfo():
                Bundle =
                DieselIpcCodec
                    .encodeServiceInfo(
                        runtimeAvailable =
                            DieselPublicRuntimeAccess
                                .runtime
                                .value
                                ?.isActive ==
                                true,
                    )

            override fun openSession(
                clientMinMajor: Int,
                clientMaxMajor: Int,
                clientToken: IDieselClientToken?,
            ): Bundle {
                val runtime =
                    DieselPublicRuntimeAccess
                        .runtime
                        .value
                        ?.takeIf {
                            it.isActive
                        }

                val token =
                    clientToken
                        ?.asBinder()

                val decision =
                    synchronized(lock) {
                        DieselSessionAdmission
                            .decide(
                                clientMinMajor =
                                    clientMinMajor,
                                clientMaxMajor =
                                    clientMaxMajor,
                                clientTokenPresent =
                                    token != null,
                                runtimeAvailable =
                                    runtime != null,
                                activeSessions =
                                    sessions.size,
                            )
                    }

                if (
                    decision is
                    DieselSessionOpenDecision.Rejected
                ) {
                    return DieselIpcCodec
                        .encodeSessionOpenRejected(
                            decision.reason,
                        )
                }

                val requiredRuntime =
                    requireNotNull(
                        runtime,
                    )

                val requiredToken =
                    requireNotNull(
                        token,
                    )

                val uid =
                    Binder.getCallingUid()

                val identity =
                    DieselCallerIdentity(
                        uid =
                            uid,
                        packages =
                            packageManager
                                .getPackagesForUid(
                                    uid,
                                )
                                ?.toList()
                                ?.sorted()
                                .orEmpty(),
                    )

                if (
                    identity.packages.isEmpty()
                ) {
                    return DieselIpcCodec
                        .encodeSessionOpenRejected(
                            DieselSessionOpenRejection.INVALID_CLIENT,
                        )
                }

                val sessionId =
                    synchronized(lock) {
                        nextSessionId++
                    }

                lateinit var session:
                    RemoteSession

                session =
                    RemoteSession(
                        sessionId =
                            sessionId,
                        identity =
                            identity,
                        runtime =
                            requiredRuntime,
                        accessPolicy =
                            accessPolicy,
                        clientToken =
                            requiredToken,
                        onClosed = {
                            id ->
                            synchronized(lock) {
                                if (
                                    sessions[
                                        id
                                    ] ===
                                    session
                                ) {
                                    sessions.remove(
                                        id,
                                    )
                                }
                            }
                        },
                    )

                try {
                    session.linkClientDeath()
                } catch (
                    error:
                        RemoteException,
                ) {
                    session.closeInternal(
                        "client_already_dead",
                    )

                    return DieselIpcCodec
                        .encodeSessionOpenRejected(
                            DieselSessionOpenRejection.INVALID_CLIENT,
                        )
                }

                val finalRejection =
                    synchronized(lock) {
                        when {
                            DieselPublicRuntimeAccess
                                .runtime
                                .value !==
                                requiredRuntime ||
                                !requiredRuntime.isActive ->
                                DieselSessionOpenRejection.RUNTIME_UNAVAILABLE

                            sessions.size >=
                                DieselSessionAdmission.MAX_SESSIONS ->
                                DieselSessionOpenRejection.SESSION_LIMIT

                            else -> {
                                sessions[
                                    sessionId
                                ] =
                                    session

                                null
                            }
                        }
                    }

                if (
                    finalRejection !=
                    null
                ) {
                    session.closeInternal(
                        "session_open_race_rejected",
                    )

                    return DieselIpcCodec
                        .encodeSessionOpenRejected(
                            finalRejection,
                        )
                }

                return DieselIpcCodec
                    .encodeSessionOpenSuccess(
                        session.asBinder(),
                    )
            }
        }

    private fun closeAllSessions(
        reason: String,
    ) {
        val toClose =
            synchronized(lock) {
                sessions
                    .values
                    .toList()
                    .also {
                        sessions.clear()
                    }
            }

        toClose.forEach {
            it.closeInternal(
                reason,
            )
        }
    }

    private class RemoteSession(
        private val sessionId: Long,
        private val identity: DieselCallerIdentity,
        private val runtime: DieselPublicRuntimeContext,
        private val accessPolicy: DieselPublicAccessPolicy,
        private val clientToken: IBinder,
        private val onClosed: (Long) -> Unit,
    ) :
        IDieselSession.Stub(),
        IBinder.DeathRecipient {

        private val closed =
            AtomicBoolean(
                false,
            )

        private val sessionJob =
            SupervisorJob(
                runtime.scope
                    .coroutineContext[
                        Job
                    ],
            )

        private val scope =
            CoroutineScope(
                runtime.scope
                    .coroutineContext +
                    sessionJob,
            )

        private val actionGate =
            DieselActionInvocationGate()

        fun linkClientDeath() {
            clientToken.linkToDeath(
                this,
                0,
            )
        }

        override fun binderDied() {
            closeInternal(
                "client_process_died",
            )
        }

        override fun getCatalog():
            Bundle {
            enforceCaller()

            val snapshot =
                runtime.api.catalog()

            val visible =
                snapshot.resources
                    .filter {
                        descriptor ->
                        descriptor.operations
                            .any {
                                operation ->
                                accessPolicy.canAccess(
                                    identity =
                                        identity,
                                    descriptor =
                                        descriptor,
                                    operation =
                                        operation,
                                )
                            }
                    }

            return DieselIpcCodec
                .encodeCatalog(
                    snapshot =
                        snapshot,
                    resources =
                        visible,
                )
        }

        override fun getState(
            resourceId: String,
        ): Bundle {
            enforceCaller()

            val descriptor =
                runtime.api
                    .descriptor(
                        resourceId,
                    )

            if (
                descriptor == null ||
                descriptor.kind !=
                DieselResourceKind.STATE ||
                !accessPolicy.canAccess(
                    identity =
                        identity,
                    descriptor =
                        descriptor,
                    operation =
                        DieselResourceOperation.READ,
                )
            ) {
                return DieselIpcCodec
                    .encodeStateResult(
                        DieselPublicStateResult.NotFound(
                            resourceId,
                        ),
                    )
            }

            return DieselIpcCodec
                .encodeStateResult(
                    runtime.api
                        .getState(
                            resourceId,
                        ),
                )
        }

        override fun invokeAction(
            resourceId: String,
            input: Bundle,
            callback: IDieselActionCallback?,
        ) {
            enforceCaller()

            val resultCallback =
                callback
                    ?: return

            val descriptor =
                runtime.api
                    .descriptor(
                        resourceId,
                    )

            if (
                descriptor == null ||
                descriptor.kind !=
                DieselResourceKind.ACTION ||
                !accessPolicy.canAccess(
                    identity =
                        identity,
                    descriptor =
                        descriptor,
                    operation =
                        DieselResourceOperation.INVOKE,
                )
            ) {
                deliverAction(
                    resultCallback,
                    DieselPublicActionResult.NotFound(
                        resourceId,
                    ),
                )

                return
            }

            val decoded =
                when (
                    val result =
                        DieselIpcCodec
                            .decodeValue(
                                input,
                            )
                ) {
                    is DieselPublicDecodeResult.Invalid -> {
                        deliverAction(
                            resultCallback,
                            DieselPublicActionResult.InvalidInput(
                                result.reason,
                            ),
                        )

                        return
                    }

                    is DieselPublicDecodeResult.Value ->
                        result.value
                }

            if (
                !actionGate.tryAcquire()
            ) {
                deliverAction(
                    resultCallback,
                    DieselPublicActionResult.Rejected(
                        "session_action_limit",
                    ),
                )

                return
            }

            scope.launch {
                try {
                    val result =
                        withTimeoutOrNull(
                            ACTION_TIMEOUT_MS,
                        ) {
                            runtime.api
                                .invokeAction(
                                    id =
                                        resourceId,
                                    input =
                                        decoded,
                                )
                        }
                            ?: DieselPublicActionResult.Failed(
                                "action_timeout",
                            )

                    deliverAction(
                        resultCallback,
                        result,
                    )
                } catch (
                    cancellation:
                        CancellationException,
                ) {
                    throw cancellation
                } finally {
                    actionGate.release()
                }
            }
        }

        override fun close() {
            enforceCaller()

            closeInternal(
                "client_closed",
            )
        }

        private fun deliverAction(
            callback: IDieselActionCallback,
            result: DieselPublicActionResult,
        ) {
            try {
                callback.onResult(
                    DieselIpcCodec
                        .encodeActionResult(
                            result,
                        ),
                )
            } catch (
                ignored:
                    RemoteException,
            ) {
                // Whole-session cleanup is tied to the client death token.
            }
        }

        private fun enforceCaller() {
            check(
                !closed.get(),
            ) {
                "Diesel public session is closed"
            }

            if (
                Binder.getCallingUid() !=
                identity.uid
            ) {
                throw SecurityException(
                    "Diesel session belongs to another UID",
                )
            }

            if (!runtime.isActive) {
                closeInternal(
                    "runtime_inactive",
                )

                throw IllegalStateException(
                    "Diesel runtime is no longer active",
                )
            }
        }

        fun closeInternal(
            reason: String,
        ) {
            @Suppress("UNUSED_VARIABLE")
            val closeReason =
                reason

            if (
                !closed.compareAndSet(
                    false,
                    true,
                )
            ) {
                return
            }

            runCatching {
                clientToken.unlinkToDeath(
                    this,
                    0,
                )
            }

            sessionJob.cancel()

            onClosed(
                sessionId,
            )
        }

        private companion object {
            const val ACTION_TIMEOUT_MS =
                5_000L
        }
    }
}
