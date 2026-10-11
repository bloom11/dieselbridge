// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.companion.CompanionAlarmKind
import org.aaustralian.dieselbridge.companion.CompanionAlarmStateOwner
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.action.DieselActionDispatcher
import org.aaustralian.dieselbridge.platform.action.DieselActionKey
import org.aaustralian.dieselbridge.platform.action.DieselActionResult
import org.aaustralian.dieselbridge.platform.state.DieselStateKey
import org.aaustralian.dieselbridge.platform.state.DieselStateStore
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselPublicApiTest {
    private val stateKey =
        DieselStateKey(
            id = "test.public.state",
            valueClass = String::class.java,
        )
    private val stateDescriptor =
        DieselResourceDescriptor(
            id = stateKey.id,
            kind = DieselResourceKind.STATE,
            schema = "test.public.state.v1",
            operations = setOf(DieselResourceOperation.READ),
            summary = "Test public state",
        )
    private val actionKey =
        DieselActionKey(
            id = "test.public.action",
            inputClass = String::class.java,
            outputClass = String::class.java,
        )
    private val actionDescriptor =
        DieselResourceDescriptor(
            id = actionKey.id,
            kind = DieselResourceKind.ACTION,
            schema = "test.public.action.v1",
            operations = setOf(DieselResourceOperation.INVOKE),
            summary = "Test public action",
        )

    @Test
    fun publicStateReadsExistingStateStoreWithoutExposingOwner() {
        val states = DieselStateStore(clock = { 1234L })
        val publicApi =
            DieselPublicApi(
                stateBindings =
                    listOf(
                        DieselPublicStateBinding.fromStateStore(
                            states = states,
                            descriptor = stateDescriptor,
                            key = stateKey,
                        ) { value -> DieselValue.Text(value) },
                    ),
            )

        assertEquals(
            DieselPublicStateResult.Success(
                DieselPublicStateSnapshot(value = null, revision = null, updatedAtMs = null),
            ),
            publicApi.getState(stateKey.id),
        )

        val publisher = states.registerPublisher(key = stateKey, ownerId = "internal-owner-not-public")
        assertTrue(publisher.set("hello"))

        assertEquals(
            DieselPublicStateResult.Success(
                DieselPublicStateSnapshot(
                    value = DieselValue.Text("hello"),
                    revision = 1L,
                    updatedAtMs = 1234L,
                ),
            ),
            publicApi.getState(stateKey.id),
        )
        publisher.close()
    }

    @Test
    fun publicActionDelegatesToExistingActionDispatcher() =
        runTest {
            val actions = DieselActionDispatcher()
            val registration =
                actions.register(key = actionKey, ownerId = "internal-owner") { value ->
                    DieselActionResult.Success(value.uppercase())
                }
            val publicApi = actionApi(actions)

            assertEquals(
                DieselPublicActionResult.Success(DieselValue.Text("DIESEL")),
                publicApi.invokeAction(actionKey.id, DieselValue.Text("diesel")),
            )
            registration.close()
            assertEquals(
                DieselPublicActionResult.Unavailable,
                publicApi.invokeAction(actionKey.id, DieselValue.Text("after-close")),
            )
        }

    @Test
    fun publicActionRejectsInvalidInputBeforeCoreDispatch() =
        runTest {
            assertEquals(
                DieselPublicActionResult.InvalidInput("text_required"),
                actionApi(DieselActionDispatcher()).invokeAction(
                    actionKey.id,
                    DieselValue.Integer(5L),
                ),
            )
        }

    @Test
    fun publicActionMapsCoreRejectionAndFailureWithoutLeakingHandlerType() =
        runTest {
            val rejectedActions = DieselActionDispatcher()
            val rejectedRegistration =
                rejectedActions.register(key = actionKey, ownerId = "rejector") {
                    DieselActionResult.Rejected("not_allowed")
                }
            assertEquals(
                DieselPublicActionResult.Rejected("not_allowed"),
                actionApi(rejectedActions).invokeAction(actionKey.id, DieselValue.Text("value")),
            )
            rejectedRegistration.close()

            val failedActions = DieselActionDispatcher()
            val failedRegistration =
                failedActions.register(key = actionKey, ownerId = "thrower") {
                    throw IllegalStateException("private implementation detail")
                }
            assertEquals(
                DieselPublicActionResult.Failed("handler_failed"),
                actionApi(failedActions).invokeAction(actionKey.id, DieselValue.Text("value")),
            )
            failedRegistration.close()
        }

    @Test
    fun missingIdsAreExplicit() =
        runTest {
            val publicApi = DieselPublicApi()
            assertEquals(
                DieselPublicStateResult.NotFound("missing.state"),
                publicApi.getState("missing.state"),
            )
            assertEquals(
                DieselPublicActionResult.NotFound("missing.action"),
                publicApi.invokeAction("missing.action", DieselValue.Null),
            )
        }

    @Test
    fun defaultPublicApiExposesCompanionAlarmThroughStateStore() {
        val scope = CoroutineScope(SupervisorJob())
        val platform = DieselPlatform(scope = scope)
        val owner = CompanionAlarmStateOwner(states = platform.states, clockMs = { 3000L })

        owner.apply(
            kind = CompanionAlarmKind.SCHEDULED,
            triggerAtMs = 9_000L,
            observedAtMs = 2_000L,
            reason = null,
        )

        val api = DieselDefaultPublicApi.create(platform)
        assertEquals(DieselDefaultPublicApi.COMPANION_NEXT_ALARM, api.catalog().resources.single())

        val result = api.getState(CompanionAlarmStateOwner.KEY.id)
        assertTrue(result is DieselPublicStateResult.Success)
        result as DieselPublicStateResult.Success
        assertEquals(1L, result.snapshot.revision)
        assertTrue(requireNotNull(result.snapshot.updatedAtMs) > 0L)

        val value = result.snapshot.value as DieselValue.ObjectValue
        assertEquals(DieselValue.Text("scheduled"), value.value["kind"])
        assertEquals(DieselValue.Integer(9_000L), value.value["triggerAtMs"])
        assertEquals(DieselValue.Integer(3_000L), value.value["receivedAtMs"])
        assertNull(value.value["reason"])

        owner.close()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun actionApi(actions: DieselActionDispatcher): DieselPublicApi =
        DieselPublicApi(
            actionBindings =
                listOf(
                    DieselPublicActionBinding.fromActionDispatcher(
                        actions = actions,
                        descriptor = actionDescriptor,
                        key = actionKey,
                        decode = { input ->
                            val value = (input as? DieselValue.Text)?.value
                            if (value == null) {
                                DieselPublicDecodeResult.Invalid("text_required")
                            } else {
                                DieselPublicDecodeResult.Value(value)
                            }
                        },
                        encode = { value -> DieselValue.Text(value) },
                    ),
                ),
        )
}
