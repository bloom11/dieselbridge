// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.capability

/**
 * The provider/capability pair currently selected by the registry.
 *
 * Provider identity is included deliberately so provider transitions remain
 * observable even when two bindings expose equivalent capability objects.
 */
data class ActiveCapabilitySelection(
    val capabilityId: String,
    val providerId: String,
    val capability: DieselCapability,
)
