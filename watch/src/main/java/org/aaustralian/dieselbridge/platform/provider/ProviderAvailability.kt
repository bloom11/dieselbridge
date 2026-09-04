// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.provider

/**
 * Health/availability reported for one provider-capability binding.
 *
 * Selection state (ACTIVE/STANDBY) is computed separately by the registry.
 */
enum class ProviderAvailability {
    AVAILABLE,
    UNAVAILABLE,
    ERROR,
}
