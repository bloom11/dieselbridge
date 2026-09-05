// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.aaustralian.dieselbridge.platform.provider.DieselProvider
import org.aaustralian.dieselbridge.platform.provider.ProviderAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryRouteTest {

    private class FakeBatteryProvider(
        override val providerId: String,
        initialPercent: Int,
    ) : BatteryCapability, DieselProvider {

        private val mutableState =
            MutableStateFlow<BatteryState?>(
                state(initialPercent),
            )

        override val state: StateFlow<BatteryState?> =
            mutableState.asStateFlow()

        fun update(percent: Int) {
            mutableState.value = state(percent)
        }

        private fun state(percent: Int) =
            BatteryState(
                percent = percent,
                voltageVolts = 4.0,
                charging = false,
            )
    }

    @Test
    fun routeTracksFallbackRecoveryAndRemovalWithoutResubscription() =
        runBlocking {
            val registry = CapabilityRegistry()
            val scope =
                CoroutineScope(
                    SupervisorJob() + Dispatchers.Unconfined,
                )

            try {
                val route =
                    BatteryRoute(
                        registry = registry,
                        scope = scope,
                    )

                assertNull(route.state.value)

                val low =
                    FakeBatteryProvider(
                        providerId = "battery.low",
                        initialPercent = 40,
                    )

                val high =
                    FakeBatteryProvider(
                        providerId = "battery.high",
                        initialPercent = 70,
                    )

                registry.register(
                    capability = low,
                    provider = low,
                    priority = 10,
                )

                awaitPercent(route.state, 40)

                registry.register(
                    capability = high,
                    provider = high,
                    priority = 100,
                )

                awaitPercent(route.state, 70)

                registry.setAvailability(
                    capabilityId = BatteryCapability.ID,
                    providerId = high.providerId,
                    availability = ProviderAvailability.ERROR,
                    reason = "simulated failure",
                )

                awaitPercent(route.state, 40)

                low.update(41)

                awaitPercent(route.state, 41)

                registry.setAvailability(
                    capabilityId = BatteryCapability.ID,
                    providerId = high.providerId,
                    availability = ProviderAvailability.AVAILABLE,
                )

                awaitPercent(route.state, 70)

                registry.unregister(
                    capabilityId = BatteryCapability.ID,
                    providerId = high.providerId,
                )

                awaitPercent(route.state, 41)

                registry.unregister(
                    capabilityId = BatteryCapability.ID,
                    providerId = low.providerId,
                )

                awaitNull(route.state)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun providerSwitchIsObservableEvenWhenBatteryValueIsEqual() =
        runBlocking {
            val registry = CapabilityRegistry()
            val scope =
                CoroutineScope(
                    SupervisorJob() + Dispatchers.Unconfined,
                )

            try {
                val low =
                    FakeBatteryProvider(
                        providerId = "battery.low",
                        initialPercent = 40,
                    )

                val high =
                    FakeBatteryProvider(
                        providerId = "battery.high",
                        initialPercent = 40,
                    )

                registry.register(
                    capability = low,
                    provider = low,
                    priority = 10,
                )

                registry.register(
                    capability = high,
                    provider = high,
                    priority = 100,
                )

                val route =
                    BatteryRoute(
                        registry = registry,
                        scope = scope,
                    )

                awaitPercent(route.state, 40)

                assertEquals(
                    high.providerId,
                    registry
                        .observeActive(BatteryCapability.ID)
                        .value
                        ?.providerId,
                )

                registry.setAvailability(
                    capabilityId = BatteryCapability.ID,
                    providerId = high.providerId,
                    availability = ProviderAvailability.ERROR,
                )

                assertEquals(
                    low.providerId,
                    registry
                        .observeActive(BatteryCapability.ID)
                        .value
                        ?.providerId,
                )

                // Canonical data can legitimately remain equal while the
                // provider-selection stream still records the transition.
                assertEquals(40, route.state.value?.percent)

                // Prove the route actually moved to the low provider rather
                // than merely retaining the previous equal value.
                low.update(41)
                awaitPercent(route.state, 41)

                // The inactive failed provider must no longer drive the route.
                high.update(99)
                assertEquals(41, route.state.value?.percent)

                // Recovery should reconnect the same route to high and expose
                // high's latest state immediately.
                registry.setAvailability(
                    capabilityId = BatteryCapability.ID,
                    providerId = high.providerId,
                    availability = ProviderAvailability.AVAILABLE,
                )

                awaitPercent(route.state, 99)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun currentReadsSelectedProviderSynchronously() =
        runBlocking {
            val registry = CapabilityRegistry()
            val scope =
                CoroutineScope(
                    SupervisorJob() + Dispatchers.Unconfined,
                )

            try {
                val low =
                    FakeBatteryProvider(
                        providerId = "battery.low",
                        initialPercent = 20,
                    )

                val high =
                    FakeBatteryProvider(
                        providerId = "battery.high",
                        initialPercent = 80,
                    )

                val route =
                    BatteryRoute(
                        registry = registry,
                        scope = scope,
                    )

                assertNull(route.current())

                registry.register(
                    capability = low,
                    provider = low,
                    priority = 10,
                )

                assertEquals(20, route.current()?.percent)

                registry.register(
                    capability = high,
                    provider = high,
                    priority = 100,
                )

                assertEquals(80, route.current()?.percent)

                registry.setAvailability(
                    capabilityId = BatteryCapability.ID,
                    providerId = high.providerId,
                    availability = ProviderAvailability.ERROR,
                )

                assertEquals(20, route.current()?.percent)
            } finally {
                scope.cancel()
            }
        }

    private suspend fun awaitPercent(
        state: StateFlow<BatteryState?>,
        expected: Int,
    ) {
        withTimeout(1_000) {
            state
                .filter { it?.percent == expected }
                .first()
        }
    }

    private suspend fun awaitNull(
        state: StateFlow<BatteryState?>,
    ) {
        withTimeout(1_000) {
            state
                .filter { it == null }
                .first()
        }
    }
}
