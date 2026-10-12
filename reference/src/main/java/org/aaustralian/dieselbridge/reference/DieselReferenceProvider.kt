// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.reference

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.aaustralian.dieselbridge.ipc.IDieselActionCallback
import org.aaustralian.dieselbridge.ipc.IDieselEventCallback
import org.aaustralian.dieselbridge.ipc.IDieselObservationCallback
import org.aaustralian.dieselbridge.ipc.IDieselProviderCallback

internal data class ProviderProofResult(
    val stateRegistrationId: Long,
    val actionRegistrationId: Long,
    val eventRegistrationId: Long,
    val observationRegistrationId: Long,
    val stateValue: String,
    val actionValue: String,
    val eventObserved: Boolean,
    val observationValueCount: Int,
)

internal class DieselReferenceProviderHarness(
    private val client: DieselReferenceClient,
) : AutoCloseable {

    private val session =
        client.session

    private val scope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO,
        )

    private val registrations =
        mutableListOf<Long>()

    private val actionRegistration =
        AtomicLong(
            -1L,
        )

    private val observationRegistration =
        AtomicLong(
            -1L,
        )

    private val callback =
        object :
            IDieselProviderCallback.Stub() {

            override fun onActionRequest(
                registrationId: Long,
                requestId: Long,
                resourceId: String,
                input: Bundle,
            ) {
                if (
                    registrationId !=
                    actionRegistration.get()
                ) {
                    return
                }

                scope.launch {
                    val completion =
                        Bundle().apply {
                            putString(
                                "status",
                                STATUS_OK,
                            )
                            putBundle(
                                "value",
                                textValue(
                                    ACTION_RESPONSE,
                                ),
                            )
                        }

                    val result =
                        session.completeProviderAction(
                            registrationId,
                            requestId,
                            completion,
                        )

                    if (
                        result.getString(
                            "status",
                        ) !=
                        STATUS_OK
                    ) {
                        Log.e(
                            TAG,
                            "M77_PROVIDER action completion rejected: " +
                                result.getString(
                                    "reason",
                                ),
                        )
                    }
                }
            }

            override fun onObservationStart(
                registrationId: Long,
                acquisitionId: Long,
                resourceId: String,
                preferredSamplePeriodMs: Long,
            ) {
                if (
                    registrationId !=
                    observationRegistration.get()
                ) {
                    return
                }

                scope.launch {
                    val started =
                        Bundle().apply {
                            putString(
                                "type",
                                "started",
                            )
                            putLong(
                                "effectiveSamplePeriodMs",
                                preferredSamplePeriodMs,
                            )
                            putLong(
                                "configuredSamplePeriodMs",
                                preferredSamplePeriodMs,
                            )
                        }

                    val startedResult =
                        session.pushProviderObservation(
                            registrationId,
                            acquisitionId,
                            started,
                        )

                    if (
                        startedResult.getString(
                            "status",
                        ) !=
                        STATUS_OK
                    ) {
                        Log.e(
                            TAG,
                            "M77_PROVIDER observation start rejected: " +
                                startedResult.getString(
                                    "reason",
                                ),
                        )
                        return@launch
                    }

                    val sample =
                        Bundle().apply {
                            putString(
                                "type",
                                "sample",
                            )
                            putDoubleArray(
                                "values",
                                doubleArrayOf(
                                    42.0,
                                    7.0,
                                    1.0,
                                ),
                            )
                            putLong(
                                "timestampNanos",
                                SystemClock.elapsedRealtimeNanos(),
                            )
                            putInt(
                                "accuracy",
                                3,
                            )
                            putLong(
                                "sourceDroppedTotal",
                                0L,
                            )
                        }

                    val sampleResult =
                        session.pushProviderObservation(
                            registrationId,
                            acquisitionId,
                            sample,
                        )

                    if (
                        sampleResult.getString(
                            "status",
                        ) !=
                        STATUS_OK
                    ) {
                        Log.e(
                            TAG,
                            "M77_PROVIDER observation sample rejected: " +
                                sampleResult.getString(
                                    "reason",
                                ),
                        )
                    }
                }
            }

            override fun onObservationStop(
                registrationId: Long,
                acquisitionId: Long,
            ) = Unit
        }

    suspend fun registerForHold():
        List<String> {
        ensureRegistered()
        publishSyntheticState()
        return RESOURCE_IDS
    }

    suspend fun runProof():
        ProviderProofResult {
        val ids =
            ensureRegistered()

        publishSyntheticState()

        val state =
            session.getState(
                STATE_ID,
            )

        requireOk(
            state,
            "state_read",
        )

        require(
            state.getBoolean(
                "present",
                false,
            ),
        ) {
            "provider_state_not_present"
        }

        val stateValue =
            state
                .getBundle(
                    "value",
                )
                ?.getString(
                    "text",
                )
                ?: error(
                    "provider_state_value_missing",
                )

        require(
            stateValue ==
            STATE_VALUE,
        ) {
            "provider_state_value_mismatch"
        }

        val actionDeferred =
            CompletableDeferred<Bundle>()

        session.invokeAction(
            ACTION_ID,
            textValue(
                "ping",
            ),
            object :
                IDieselActionCallback.Stub() {
                override fun onResult(
                    result: Bundle,
                ) {
                    actionDeferred.complete(
                        result,
                    )
                }
            },
        )

        val action =
            withTimeout(
                TIMEOUT_MS,
            ) {
                actionDeferred.await()
            }

        requireOk(
            action,
            "action_invoke",
        )

        val actionValue =
            action
                .getBundle(
                    "value",
                )
                ?.getString(
                    "text",
                )
                ?: error(
                    "provider_action_value_missing",
                )

        require(
            actionValue ==
            ACTION_RESPONSE,
        ) {
            "provider_action_value_mismatch"
        }

        val eventBatches =
            Channel<Bundle>(
                Channel.UNLIMITED,
            )

        val eventCallback =
            object :
                IDieselEventCallback.Stub() {
                override fun onEvents(
                    batch: Bundle,
                ) {
                    eventBatches.trySend(
                        batch,
                    )
                }
            }

        val eventOpen =
            session.subscribeEvents(
                arrayOf(
                    EVENT_ID,
                ),
                eventCallback,
            )

        requireOk(
            eventOpen,
            "event_subscribe",
        )

        val eventSubscriptionId =
            eventOpen.getLong(
                "subscriptionId",
            )

        val eventObserved =
            try {
                val published =
                    session.publishProviderEvent(
                        ids.event,
                        textValue(
                            EVENT_VALUE,
                        ),
                    )

                requireOk(
                    published,
                    "event_publish",
                )

                withTimeout(
                    TIMEOUT_MS,
                ) {
                    while (true) {
                        val batch =
                            eventBatches.receive()

                        @Suppress("DEPRECATION")
                        val events =
                            batch
                                .getParcelableArrayList<Bundle>(
                                    "events",
                                )
                                .orEmpty()

                        if (
                            events.any {
                                it.getString(
                                    "resourceId",
                                ) ==
                                EVENT_ID
                            }
                        ) {
                            return@withTimeout true
                        }
                    }

                    error(
                        "unreachable",
                    )
                }
            } finally {
                session.unsubscribeEvents(
                    eventSubscriptionId,
                )
                eventBatches.close()
            }

        val observationUpdates =
            Channel<Bundle>(
                Channel.UNLIMITED,
            )

        val observationCallback =
            object :
                IDieselObservationCallback.Stub() {
                override fun onUpdate(
                    update: Bundle,
                ) {
                    observationUpdates.trySend(
                        update,
                    )
                }
            }

        val observationOpen =
            session.subscribeObservation(
                OBSERVATION_ID,
                100L,
                observationCallback,
            )

        requireOk(
            observationOpen,
            "observation_subscribe",
        )

        val subscriptionId =
            observationOpen.getLong(
                "subscriptionId",
            )

        val observationValueCount =
            try {
                withTimeout(
                    TIMEOUT_MS,
                ) {
                    while (true) {
                        val update =
                            observationUpdates.receive()

                        @Suppress("DEPRECATION")
                        val samples =
                            update
                                .getParcelableArrayList<Bundle>(
                                    "samples",
                                )

                        val first =
                            samples
                                ?.firstOrNull()

                        if (first != null) {
                            return@withTimeout first
                                .getDoubleArray(
                                    "values",
                                )
                                ?.size
                                ?: 0
                        }
                    }

                    error(
                        "unreachable",
                    )
                }
            } finally {
                session.unsubscribeObservation(
                    subscriptionId,
                )
                observationUpdates.close()
            }

        require(
            observationValueCount ==
            3,
        ) {
            "provider_observation_value_count_mismatch"
        }

        return ProviderProofResult(
            stateRegistrationId =
                ids.state,
            actionRegistrationId =
                ids.action,
            eventRegistrationId =
                ids.event,
            observationRegistrationId =
                ids.observation,
            stateValue =
                stateValue,
            actionValue =
                actionValue,
            eventObserved =
                eventObserved,
            observationValueCount =
                observationValueCount,
        )
    }

    private data class Registrations(
        val state: Long,
        val action: Long,
        val event: Long,
        val observation: Long,
    )

    private var current:
        Registrations? =
        null

    private fun ensureRegistered():
        Registrations {
        current?.let {
            return it
        }

        val state =
            register(
                STATE_ID,
                "state",
                STATE_SCHEMA,
                "M7 reference synthetic state",
            )

        val action =
            register(
                ACTION_ID,
                "action",
                ACTION_SCHEMA,
                "M7 reference synthetic action",
            )

        actionRegistration.set(
            action,
        )

        val event =
            register(
                EVENT_ID,
                "event",
                EVENT_SCHEMA,
                "M7 reference synthetic event",
            )

        val observation =
            register(
                OBSERVATION_ID,
                "observation",
                OBSERVATION_SCHEMA,
                "M7 reference synthetic observation",
            )

        observationRegistration.set(
            observation,
        )

        val availability =
            session.setProviderAvailability(
                observation,
                "available",
                null,
            )

        requireOk(
            availability,
            "observation_availability",
        )

        return Registrations(
            state,
            action,
            event,
            observation,
        ).also {
            current =
                it
        }
    }

    private fun register(
        id: String,
        kind: String,
        schema: String,
        summary: String,
    ): Long {
        val result =
            session.registerProviderResource(
                Bundle().apply {
                    putString(
                        "id",
                        id,
                    )
                    putString(
                        "kind",
                        kind,
                    )
                    putString(
                        "schema",
                        schema,
                    )
                    putString(
                        "summary",
                        summary,
                    )
                },
                callback,
            )

        requireOk(
            result,
            "register_$kind",
        )

        val registrationId =
            result.getLong(
                "registrationId",
            )

        registrations +=
            registrationId

        return registrationId
    }

    private fun publishSyntheticState() {
        val ids =
            requireNotNull(
                current,
            ) {
                "provider_not_registered"
            }

        val result =
            session.publishProviderState(
                ids.state,
                textValue(
                    STATE_VALUE,
                ),
                false,
            )

        requireOk(
            result,
            "state_publish",
        )
    }

    override fun close() {
        registrations
            .asReversed()
            .forEach {
                registrationId ->
                runCatching {
                    session.unregisterProviderResource(
                        registrationId,
                    )
                }
            }

        registrations.clear()
        current =
            null
        actionRegistration.set(
            -1L,
        )
        observationRegistration.set(
            -1L,
        )
        scope.cancel()
    }

    companion object {
        private const val TAG =
            "DieselM77Ref"

        private const val STATUS_OK =
            "ok"

        private const val TIMEOUT_MS =
            7_000L

        private const val PACKAGE_ID =
            "io.github.bloom11.dieselbridge.reference"

        const val STATE_ID =
            "plugin.$PACKAGE_ID.state"

        const val ACTION_ID =
            "plugin.$PACKAGE_ID.action"

        const val EVENT_ID =
            "plugin.$PACKAGE_ID.event"

        /*
         * Exactly 64 characters: the caller-owned observation prefix
         * plus this package leaves room for one suffix character.
         */
        const val OBSERVATION_ID =
            "sensor.observe.plugin.$PACKAGE_ID.x"

        const val STATE_SCHEMA =
            "reference.state.v1"

        const val ACTION_SCHEMA =
            "reference.action.v1"

        const val EVENT_SCHEMA =
            "reference.event.v1"

        const val OBSERVATION_SCHEMA =
            "sensor.reading.v1"

        private const val STATE_VALUE =
            "reference-state"

        private const val ACTION_RESPONSE =
            "pong"

        private const val EVENT_VALUE =
            "reference-event"

        val RESOURCE_IDS =
            listOf(
                STATE_ID,
                ACTION_ID,
                EVENT_ID,
                OBSERVATION_ID,
            )

        private fun textValue(
            value: String,
        ): Bundle =
            Bundle().apply {
                putString(
                    "type",
                    "text",
                )
                putString(
                    "text",
                    value,
                )
            }

        private fun requireOk(
            result: Bundle,
            operation: String,
        ) {
            require(
                result.getString(
                    "status",
                ) ==
                STATUS_OK,
            ) {
                "$operation:" +
                    result.getString(
                        "status",
                    ) +
                    ":" +
                    result.getString(
                        "reason",
                    )
            }
        }
    }
}
