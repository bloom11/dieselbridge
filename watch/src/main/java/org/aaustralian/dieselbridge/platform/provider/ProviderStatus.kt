// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.provider

/**
 * Effective state of a registered provider-capability binding.
 */
enum class ProviderStatus {
    ACTIVE,
    STANDBY,
    UNAVAILABLE,
    ERROR,
}
