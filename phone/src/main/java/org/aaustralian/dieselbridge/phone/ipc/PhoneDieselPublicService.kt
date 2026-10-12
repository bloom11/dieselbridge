// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone.ipc

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.aaustralian.dieselbridge.phone.DieselBridgePhoneApplication
import org.aaustralian.dieselbridge.phone.api.PhoneDieselPublicApi

class PhoneDieselPublicService :
    Service() {

    private val sessionsLock =
        Any()

    private val sessions =
        linkedMapOf<Long, RemoteSession>()

    private var nextSessionId =
        1L

    private val serviceBinder =
        object :
            IPhoneDieselPublicService.Stub() {

            override fun getServiceInfo():
                Bundle {
                val api =
                    publicApiOrNull()

                return PhoneDieselIpcCodec.serviceInfo(
                    runtimeAvailable =
                        api != null,
                    version =
                        api?.version
                            ?: org.aaustralian.dieselbridge.phone.api
                                .PhoneDieselPublicApiVersion.CURRENT,
                )
            }

            override fun openSession(
                clientMinMajor: Int,
                clientMaxMajor: Int,
                clientToken:
                    IPhoneDieselClientToken?,
            ): Bundle {
                val callingUid =
                    Binder.getCallingUid()

                val callerPackages =
                    packageManager
                        .getPackagesForUid(
                            callingUid,
                        )
                        ?.toList()
                        .orEmpty()

                val api =
                    publicApiOrNull()

                return synchronized(
                    sessionsLock,
                ) {
                    val decision =
                        PhoneDieselSessionAdmission.decide(
                            clientMinMajor =
                                clientMinMajor,
                            clientMaxMajor =
                                clientMaxMajor,
                            clientTokenPresent =
                                clientToken != null,
                            callerPackagesPresent =
                                callerPackages.isNotEmpty(),
                            runtimeAvailable =
                                api != null,
                            activeSessions =
                                sessions.size,
                            currentVersion =
                                api?.version
                                    ?: org.aaustralian.dieselbridge.phone.api
                                        .PhoneDieselPublicApiVersion.CURRENT,
                        )

                    if (
                        decision is
                        PhoneDieselSessionOpenDecision.Rejected
                    ) {
                        return@synchronized PhoneDieselIpcCodec
                            .sessionOpenRejected(
                                decision.reason,
                            )
                    }

                    val stableApi =
                        requireNotNull(
                            api,
                        )

                    val stableToken =
                        requireNotNull(
                            clientToken,
                        )

                    val sessionId =
                        nextSessionId++

                    val session =
                        RemoteSession(
                            sessionId =
                                sessionId,
                            callerUid =
                                callingUid,
                            clientToken =
                                stableToken,
                            api =
                                stableApi,
                            onClosed =
                                ::removeSession,
                        )

                    try {
                        session.linkClientDeath()
                    } catch (_: RemoteException) {
                        return@synchronized PhoneDieselIpcCodec
                            .sessionOpenRejected(
                                PhoneDieselSessionOpenRejection
                                    .INVALID_CLIENT,
                            )
                    }

                    sessions[
                        sessionId
                    ] =
                        session

                    PhoneDieselIpcCodec.sessionOpenSuccess(
                        sessionBinder =
                            session.asBinder(),
                        version =
                            stableApi.version,
                    )
                }
            }
        }

    override fun onBind(
        intent: Intent?,
    ): IBinder =
        serviceBinder

    override fun onDestroy() {
        val toClose =
            synchronized(
                sessionsLock,
            ) {
                sessions.values
                    .toList()
            }

        toClose.forEach {
            it.closeInternal(
                "service_destroyed",
            )
        }

        super.onDestroy()
    }

    private fun publicApiOrNull():
        PhoneDieselPublicApi? =
        runCatching {
            (
                application as
                    DieselBridgePhoneApplication
            )
                .runtime
                .publicApi
        }
            .getOrNull()

    private fun removeSession(
        sessionId: Long,
    ) {
        synchronized(
            sessionsLock,
        ) {
            sessions.remove(
                sessionId,
            )
        }
    }

    private class RemoteSession(
        private val sessionId: Long,
        private val callerUid: Int,
        private val clientToken:
            IPhoneDieselClientToken,
        private val api:
            PhoneDieselPublicApi,
        private val onClosed:
            (Long) -> Unit,
    ) :
        IPhoneDieselSession.Stub(),
        IBinder.DeathRecipient {

        private val closed =
            AtomicBoolean(
                false,
            )

        private val sessionJob =
            SupervisorJob()

        private val scope =
            CoroutineScope(
                sessionJob +
                Dispatchers.Default,
            )

        private val invocationGate =
            PhoneDieselInvocationGate()

        fun linkClientDeath() {
            clientToken
                .asBinder()
                .linkToDeath(
                    this,
                    0,
                )
        }

        override fun binderDied() {
            closeInternal(
                "client_token_died",
            )
        }

        override fun getCatalog():
            Bundle {
            enforceCaller()

            return PhoneDieselIpcCodec.catalog(
                api,
            )
        }

        override fun execute(
            request: Bundle,
            callback:
                IPhoneDieselResultCallback?,
        ) {
            enforceCaller()

            val resultCallback =
                callback
                    ?: return

            val decoded =
                PhoneDieselIpcCodec.decodeRequest(
                    request,
                )

            if (
                decoded is
                PhoneDieselIpcRequestDecode.Invalid
            ) {
                deliver(
                    resultCallback,
                    PhoneDieselIpcCodec
                        .malformedRequestResult(
                            decoded.reason,
                        ),
                )
                return
            }

            decoded as
                PhoneDieselIpcRequestDecode.Value

            if (
                !invocationGate
                    .tryAcquire()
            ) {
                deliver(
                    resultCallback,
                    PhoneDieselIpcCodec
                        .busyResult(),
                )
                return
            }

            if (closed.get()) {
                invocationGate.release()
                return
            }

            val callbackBinder =
                resultCallback
                    .asBinder()

            val jobReference =
                AtomicReference<Job?>(
                    null,
                )

            val deathRecipient =
                IBinder.DeathRecipient {
                    jobReference
                        .get()
                        ?.cancel()
                }

            try {
                callbackBinder
                    .linkToDeath(
                        deathRecipient,
                        0,
                    )
            } catch (_: RemoteException) {
                invocationGate.release()
                return
            }

            val job =
                scope.launch(
                    start =
                        CoroutineStart.UNDISPATCHED,
                ) {
                    try {
                        val result =
                            api.execute(
                                decoded.request,
                            )

                        deliver(
                            resultCallback,
                            PhoneDieselIpcCodec
                                .encodeResult(
                                    result,
                                ),
                        )
                    } catch (
                        cancellation:
                            CancellationException,
                    ) {
                        throw cancellation
                    } finally {
                        runCatching {
                            callbackBinder
                                .unlinkToDeath(
                                    deathRecipient,
                                    0,
                                )
                        }

                        invocationGate.release()
                    }
                }

            jobReference.set(
                job,
            )

            if (
                !callbackBinder.isBinderAlive
            ) {
                job.cancel()
            }
        }

        override fun close() {
            enforceCaller()

            closeInternal(
                "client_closed",
            )
        }

        private fun deliver(
            callback:
                IPhoneDieselResultCallback,
            result: Bundle,
        ) {
            try {
                callback.onResult(
                    result,
                )
            } catch (_: RemoteException) {
                /*
                 * Callback death has its own DeathRecipient and
                 * therefore cancels the matching gateway coroutine.
                 */
            }
        }

        private fun enforceCaller() {
            if (
                Binder.getCallingUid() !=
                callerUid
            ) {
                throw SecurityException(
                    "Phone Diesel session belongs to another UID",
                )
            }

            check(!closed.get()) {
                "Phone Diesel public session is closed"
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
                clientToken
                    .asBinder()
                    .unlinkToDeath(
                        this,
                        0,
                    )
            }

            /*
             * Cancelling these coroutines reaches
             * PhoneDieselGateway.execute(), whose cancellation path
             * removes the matching pending response-router token.
             */
            scope.cancel()

            onClosed(
                sessionId,
            )
        }
    }

    companion object {
        const val PERMISSION_USE_PHONE_DIESEL_API =
            "io.github.bloom11.dieselbridge.phone.permission.USE_DIESEL_PHONE_API"
    }
}
