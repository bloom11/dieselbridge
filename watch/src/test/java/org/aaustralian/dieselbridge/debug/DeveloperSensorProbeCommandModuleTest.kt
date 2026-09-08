// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.aaustralian.dieselbridge.platform.sensor.AndroidSensorRoute
import org.aaustralian.dieselbridge.platform.sensor.BoundedSensorSampleOutcome
import org.aaustralian.dieselbridge.platform.sensor.SensorInventoryEntry
import org.aaustralian.dieselbridge.platform.sensor.SensorLogicalId
import org.aaustralian.dieselbridge.platform.sensor.SensorRawEvent
import org.aaustralian.dieselbridge.platform.sensor.SensorRegistrationKind
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteDescriptor
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteId
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbe
import org.aaustralian.dieselbridge.platform.sensor.SensorRouteProbeOutcome
import org.aaustralian.dieselbridge.protocol.DieselCommandRegistry
import org.aaustralian.dieselbridge.protocol.DieselCommandResult
import org.aaustralian.dieselbridge.protocol.DieselCommandSpec
import org.aaustralian.dieselbridge.protocol.DieselProtocolEngine
import org.aaustralian.dieselbridge.protocol.DieselProtocolExecutionLane
import org.aaustralian.dieselbridge.protocol.DieselRequest
import org.aaustralian.dieselbridge.protocol.DieselResponse
import org.aaustralian.dieselbridge.protocol.DieselResponseCodec
import org.aaustralian.dieselbridge.protocol.DieselResponseStatus
import org.aaustralian.dieselbridge.protocol.DieselResponseTransport
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeveloperSensorProbeCommandModuleTest {
    private class FakeProbe : SensorRouteProbe {
        var calls = 0
        var lastRoute: SensorRouteId? = null
        var lastTimeout: Long? = null
        var result: SensorRouteProbeOutcome = SensorRouteProbeOutcome.RouteUnavailable
        var failure: Exception? = null
        override suspend fun probe(routeId: SensorRouteId, timeoutMs: Long): SensorRouteProbeOutcome {
            calls++
            lastRoute = routeId
            lastTimeout = timeoutMs
            failure?.let { throw it }
            return result
        }
    }

    @Test
    fun invalidSyntaxIsRejectedBeforeAuthorizationOrResolution(): Unit = runBlocking {
        val probe = FakeProbe()
        val registry = registry(probe, DeveloperRemoteAccessAuthorization { error("Auth must follow syntax") })
        val invalid = listOf(
            request().copy(args = emptyMap()),
            request().copy(args = mapOf("routeId" to DieselValue.Integer(21))),
            request().copy(name = "heart_rate"),
            request().copy(args = request().args + ("extra" to DieselValue.Null)),
        ) + listOf("", " ", "x".repeat(129), "route\n").map {
            request().copy(args = mapOf("routeId" to DieselValue.Text(it)))
        } + listOf<DieselValue>(
            DieselValue.Text("5000"), DieselValue.Decimal(5000.0), DieselValue.Null,
            DieselValue.Integer(499), DieselValue.Integer(15001),
            DieselValue.Integer(Long.MAX_VALUE), DieselValue.Integer(Long.MIN_VALUE),
        ).map { request().copy(args = request().args + ("timeoutMs" to it)) }
        invalid.forEach {
            assertEquals(DieselResponseStatus.INVALID_REQUEST, registry.dispatch(it).status)
        }
        assertEquals(0, probe.calls)
    }

    @Test
    fun disabledAuthHidesBothExistingAndMissingRoutesAndCanBeRevoked(): Unit = runBlocking {
        val probe = FakeProbe()
        var enabled = false
        val registry = registry(probe, DeveloperRemoteAccessAuthorization { enabled })
        val denied = registry.dispatch(request())
        assertEquals(DieselResponseStatus.UNAVAILABLE, denied.status)
        assertEquals(DieselValue.Text("remote_developer_access_disabled"), denied.data["reason"])
        assertEquals(0, probe.calls)
        enabled = true
        val missing = registry.dispatch(request())
        assertEquals(DieselResponseStatus.OK, missing.status)
        assertEquals(DieselValue.Text("route_unavailable"), missing.data["outcome"])
        assertEquals(DieselValue.Text(ROUTE_ID), missing.data["routeId"])
        assertEquals(SensorRouteId(ROUTE_ID), probe.lastRoute)
        assertEquals(5000L, probe.lastTimeout)
        enabled = false
        assertEquals(DieselResponseStatus.UNAVAILABLE, registry.dispatch(request()).status)
        assertEquals(1, probe.calls)
    }

    @Test
    fun inclusiveTimeoutBoundsArePassedToTheSampler(): Unit = runBlocking {
        val probe = FakeProbe()
        val registry = registry(probe)
        listOf(500L, 15000L).forEach {
            assertEquals(DieselResponseStatus.OK, registry.dispatch(request(it)).status)
            assertEquals(it, probe.lastTimeout)
        }
    }

    @Test
    fun eventPreservesRawEvidenceAndAnnotatesAllNonFiniteKinds(): Unit = runBlocking {
        val probe = FakeProbe()
        val raw = listOf(73f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.0f) +
            List(65) { it.toFloat() }
        probe.result = sample(event(raw))
        val result = registry(probe).dispatch(request())
        assertEquals(DieselResponseStatus.OK, result.status)
        assertEquals(DieselValue.Text("event"), result.data["outcome"])
        val route = objectAt(result.data, "route")
        assertEquals(DieselValue.Text(ROUTE_ID), route["routeId"])
        assertEquals(DieselValue.Text("android.sensor_manager"), route["providerId"])
        assertEquals(DieselValue.Text("accelerometer"), route["logicalId"])
        assertEquals(DieselValue.Integer(7), route["androidId"])
        assertEquals(DieselValue.Integer(1), route["androidType"])
        assertEquals(DieselValue.Text("android.sensor.accelerometer"), route["stringType"])
        assertEquals(DieselValue.Null, route["requiredPermission"])
        assertEquals(DieselValue.Flag(false), route["metadataTruncated"])
        val registration = objectAt(result.data, "registration")
        assertEquals(DieselValue.Text("listener"), registration["kind"])
        assertEquals(DieselValue.Integer(5000), registration["timeoutMs"])
        assertEquals(DieselValue.Integer(200000), registration["samplingPeriodUs"])
        val event = objectAt(result.data, "event")
        assertEquals(DieselValue.Integer(123456789), event["sensorTimestampNs"])
        assertEquals(DieselValue.Integer(21), event["timeToEventMs"])
        assertEquals(DieselValue.Integer(3), event["accuracy"])
        assertEquals(DieselValue.Integer(1), event["sampleCount"])
        assertEquals(DieselValue.Integer(70), event["valueCount"])
        assertEquals(DieselValue.Integer(64), event["returnedValueCount"])
        assertEquals(DieselValue.Flag(true), event["valuesTruncated"])
        val values = (event["values"] as DieselValue.ListValue).value
        assertEquals(64, values.size)
        assertEquals(DieselValue.Decimal(73.0), values[0])
        assertEquals(listOf(DieselValue.Null, DieselValue.Null, DieselValue.Null), values.subList(1, 4))
        assertEquals(DieselValue.Decimal(-0.0), values[4])
        val nonFinite = objectAt(event, "nonFinite")
        listOf("nan", "positiveInfinity", "negativeInfinity").forEachIndexed { index, name ->
            assertEquals(DieselValue.ListValue(listOf(DieselValue.Integer(index.toLong() + 1))), nonFinite[name])
        }
    }

    @Test
    fun ordinaryHardwareOutcomesRemainOkAndTriggerTimingIsExplicit(): Unit = runBlocking {
        val probe = FakeProbe()
        val registry = registry(probe)
        val outcomes = listOf(
            "timeout" to BoundedSensorSampleOutcome.Timeout(SensorRegistrationKind.TRIGGER, 5001),
            "permission_denied" to BoundedSensorSampleOutcome.PermissionDenied(SensorRegistrationKind.LISTENER, 3, null),
            "registration_rejected" to BoundedSensorSampleOutcome.RegistrationRejected(
                SensorRegistrationKind.LISTENER, 2, "registration_returned_false",
            ),
        )
        outcomes.forEach { (name, outcome) ->
            probe.result = sample(outcome)
            val result = registry.dispatch(request())
            assertEquals(DieselResponseStatus.OK, result.status)
            assertEquals(DieselValue.Text(name), result.data["outcome"])
            assertFalse(result.data.containsKey("event"))
            if (name == "timeout") {
                val registration = objectAt(result.data, "registration")
                assertEquals(DieselValue.Text("trigger"), registration["kind"])
                assertEquals(DieselValue.Null, registration["samplingPeriodUs"])
                assertEquals(DieselValue.Integer(5001), registration["elapsedMs"])
            }
            if (name == "registration_rejected") {
                assertEquals(DieselValue.Text("registration_returned_false"), result.data["reason"])
            }
        }
        probe.result = sample(event(emptyList()).copy(registrationKind = SensorRegistrationKind.TRIGGER,
            event = SensorRawEvent(123, null, emptyList())))
        val trigger = objectAt(registry.dispatch(request()).data, "event")
        assertEquals(DieselValue.Null, trigger["accuracy"])
        assertEquals(DieselValue.Integer(0), trigger["returnedValueCount"])
        assertEquals(DieselValue.Flag(false), trigger["valuesTruncated"])
        assertFalse(trigger.containsKey("nonFinite"))
    }

    @Test
    fun permissionFailureIsEvidenceAndUnexpectedFailureDoesNotLeakDetails(): Unit = runBlocking {
        val probe = FakeProbe()
        val registry = registry(probe)
        probe.failure = SecurityException("private permission detail")
        val denied = registry.dispatch(request())
        assertEquals(DieselResponseStatus.OK, denied.status)
        assertEquals(DieselValue.Text("permission_denied"), denied.data["outcome"])
        assertEquals(DieselValue.Null, denied.data["requiredPermission"])
        probe.failure = IllegalStateException("private backend detail")
        val failed = registry.dispatch(request())
        assertEquals(DieselResponseStatus.FAILED, failed.status)
        assertEquals(mapOf("reason" to DieselValue.Text("probe_failed")), failed.data)
        probe.failure = CancellationException("stopping")
        try {
            registry.dispatch(request())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected lifecycle control flow.
        }
    }

    @Test
    fun maximumEvidenceFits4096BytesWithoutDroppingValuesOrIdentity(): Unit = runBlocking {
        val probe = FakeProbe()
        val registry = registry(probe)
        val longText = "\uD83D\uDE00".repeat(200)
        val route = route().let {
            it.copy(
                descriptor = SensorRouteDescriptor(SensorRouteId("界".repeat(128)),
                    SensorLogicalId("a".repeat(64)), "界".repeat(128)),
                inventory = it.inventory.copy(logicalId = "a".repeat(64), name = longText,
                    vendor = longText, stringType = longText, androidId = Int.MIN_VALUE,
                    androidType = Int.MAX_VALUE, reportingMode = Int.MAX_VALUE),
            )
        }
        val req = request(15000).copy(requestId = "\u0001".repeat(64))
        val vectors = listOf(
            List(64) { -java.lang.Float.MIN_NORMAL },
            List(64) { -Float.MAX_VALUE },
            List(64) { Float.MIN_VALUE },
            List(64) { when (it % 4) {
                0 -> Float.NaN
                1 -> Float.POSITIVE_INFINITY
                2 -> Float.NEGATIVE_INFINITY
                else -> -Float.MAX_VALUE
            } },
        )
        vectors.forEach { values ->
            probe.result = SensorRouteProbeOutcome.Sample(route,
                event(values).copy(elapsedMs = Long.MAX_VALUE,
                    event = SensorRawEvent(Long.MIN_VALUE, Int.MIN_VALUE, values)))
            val result = registry.dispatch(req)
            assertEquals(DieselResponseStatus.OK, result.status)
            val response = DieselResponse(req.requestId, req.command, status = result.status, data = result.data)
            val json = DieselResponseCodec.encodeResponseJson(response)
            assertTrue("${json.toByteArray(Charsets.UTF_8).size} bytes", json.toByteArray(Charsets.UTF_8).size <= 4096)
            assertTrue(DieselResponseCodec.encodeGadgetbridgeIntent(response).isNotEmpty())
            val encodedRoute = objectAt(result.data, "route")
            assertEquals(DieselValue.Text(route.descriptor.routeId.value), encodedRoute["routeId"])
            assertEquals(DieselValue.Text(route.descriptor.providerId), encodedRoute["providerId"])
            assertEquals(DieselValue.Flag(true), encodedRoute["metadataTruncated"])
            val event = objectAt(result.data, "event")
            assertEquals(DieselValue.Integer(64), event["returnedValueCount"])
            assertEquals(DieselValue.Flag(false), event["valuesTruncated"])
        }
    }

    @Test
    fun metadataControlsAreNormalizedAndUnicodePairsRemainWhole(): Unit = runBlocking {
        val probe = FakeProbe()
        val route = route()
        probe.result = SensorRouteProbeOutcome.Sample(
            route.copy(inventory = route.inventory.copy(name = "A\nB\uD83D\uDE00")),
            event(listOf(1f)),
        )
        val metadata = objectAt(registry(probe).dispatch(request()).data, "route")
        assertEquals(DieselValue.Text("A B\uD83D\uDE00"), metadata["name"])
        assertEquals(DieselValue.Flag(true), metadata["metadataTruncated"])
    }

    @Test(timeout = 5000)
    fun queuedProbeChecksAuthorizationWhenItActuallyExecutes(): Unit = runBlocking {
        val job = SupervisorJob()
        try {
            val probe = FakeProbe()
            var authorized = true
            val registry = registry(probe, DeveloperRemoteAccessAuthorization { authorized })
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            registry.register(DieselCommandSpec("slow", "Hold the execution lane")) {
                entered.complete(Unit)
                release.await()
                DieselCommandResult.ok()
            }
            val responses = mutableListOf<DieselResponse>()
            val lane = DieselProtocolExecutionLane(CoroutineScope(coroutineContext + job),
                DieselProtocolEngine(registry, DieselResponseTransport { responses += it; true }))
            lane.submit(DieselRequest("slow", "slow"))
            entered.await()
            val queued = lane.submit(request())
            authorized = false
            release.complete(Unit)
            queued.completion.join()
            assertEquals(DieselResponseStatus.UNAVAILABLE, responses.last().status)
            assertEquals(0, probe.calls)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun commandIsDiscoverableWithAuthorizationAndBoundedEffect() {
        val spec = registry(FakeProbe()).specs().single()
        assertEquals("debug.sensor.probe", spec.name)
        assertEquals(DieselValue.Text("safe_action"), spec.metadata["effect"])
        assertEquals(DieselValue.Text("local_developer_ui"), spec.metadata["authorization"])
        assertEquals(DieselValue.Flag(true), spec.metadata["bounded"])
    }

    private fun registry(probe: SensorRouteProbe,
        auth: DeveloperRemoteAccessAuthorization = DeveloperRemoteAccessAuthorization { true }) =
        DieselCommandRegistry().apply { install(DeveloperSensorProbeCommandModule(auth, probe)) }

    private fun request(timeout: Long? = null) = DieselRequest("probe", "debug.sensor.probe",
        args = buildMap {
            put("routeId", DieselValue.Text(ROUTE_ID))
            timeout?.let { put("timeoutMs", DieselValue.Integer(it)) }
        })

    private fun sample(outcome: BoundedSensorSampleOutcome) = SensorRouteProbeOutcome.Sample(route(), outcome)
    private fun event(values: List<Float>) = BoundedSensorSampleOutcome.Event(
        SensorRegistrationKind.LISTENER, 21, SensorRawEvent(123456789, 3, values))
    private fun objectAt(data: Map<String, DieselValue>, key: String) = (data[key] as DieselValue.ObjectValue).value
    private fun route() = AndroidSensorRoute(
        SensorRouteDescriptor(SensorRouteId(ROUTE_ID), SensorLogicalId("accelerometer"), "android.sensor_manager"),
        SensorInventoryEntry("accelerometer", 7, 1, "android.sensor.accelerometer", "Test Accelerometer",
            "Test Vendor", 1, 20f, 0.01f, 0.2f, 10000, 1000000, 0, 0, 0, false),
    )
    private companion object { const val ROUTE_ID = "android.sensor_manager:1:7:0" }
}
