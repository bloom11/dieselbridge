// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.platform.action
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.aaustralian.dieselbridge.platform.DieselPlatform
import org.aaustralian.dieselbridge.platform.capability.VibrationCapability
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.junit.Assert.assertEquals
import org.junit.Test
class WatchVibrationActionTest {
    @Test fun delegatesThroughRegistry()=runTest{
        val scope=CoroutineScope(SupervisorJob());val p=DieselPlatform(scope);var observed:Long?=null
        val provider=object:VibrationCapability,DieselProvider{override val providerId="test.vibration";override fun vibrate(durationMs:Long){observed=durationMs}}
        p.capabilities.register(provider,provider);val reg=WatchVibrationAction.register(p)
        assertEquals(DieselActionResult.Success(WatchVibrationResult(250)),p.actions.dispatch(WatchVibrationAction.KEY,WatchVibrationRequest(250)));assertEquals(250L,observed);reg.close();scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
    @Test fun unavailableWithoutProvider()=runTest{val scope=CoroutineScope(SupervisorJob());val p=DieselPlatform(scope);val reg=WatchVibrationAction.register(p);assertEquals(DieselActionResult.Unavailable,p.actions.dispatch(WatchVibrationAction.KEY,WatchVibrationRequest(100)));reg.close();scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()}
    @Test fun rejectsInvalidDuration()=runTest{val scope=CoroutineScope(SupervisorJob());val p=DieselPlatform(scope);val reg=WatchVibrationAction.register(p);assertEquals(DieselActionResult.Rejected("duration_out_of_bounds"),p.actions.dispatch(WatchVibrationAction.KEY,WatchVibrationRequest(0)));reg.close();scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()}
}
