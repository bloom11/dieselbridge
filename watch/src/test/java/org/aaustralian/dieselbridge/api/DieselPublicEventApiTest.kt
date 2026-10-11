// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.event.SensorObservationSampleEvent
import org.aaustralian.dieselbridge.platform.sensor.SensorCapabilityId
import org.aaustralian.dieselbridge.platform.sensor.SensorReading
import org.aaustralian.dieselbridge.platform.sensor.observation.SensorSubscriptionSample
import org.aaustralian.dieselbridge.protocol.DieselValue
import org.junit.Assert.*
import org.junit.Test
class DieselPublicEventApiTest {
    @Test fun sampleEventIsProviderNeutral() {
        val scope=CoroutineScope(SupervisorJob()); val api=DieselDefaultPublicApi.create(DieselPlatform(scope))
        val event=SensorObservationSampleEvent(5000L,77L,"heart_rate",SensorSubscriptionSample(
            4L,SensorReading(SensorCapabilityId("sensor.heart_rate"),"private.provider",listOf(72f),123456L,3,5L),2L,1L,
        ))
        val encoded=requireNotNull(api.encodeEvent(DieselDefaultPublicApi.SENSOR_SAMPLE_EVENT.id,event))
        val value=encoded.value as DieselValue.ObjectValue
        assertEquals(5000L,encoded.timestampMs);assertFalse(value.value.containsKey("providerId"))
        assertEquals(DieselValue.Text("heart_rate"),value.value["logicalId"])
        assertEquals(DieselValue.Integer(1L),value.value["providerDroppedTotal"])
        assertEquals(DieselValue.Integer(2L),value.value["subscriptionDroppedTotal"])
        assertTrue(api.hasEvent(DieselDefaultPublicApi.SENSOR_SAMPLE_EVENT.id));scope.coroutineContext[Job]?.cancel()
    }
}
