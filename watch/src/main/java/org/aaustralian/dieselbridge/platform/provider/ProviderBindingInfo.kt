// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.provider

/**
 * Immutable snapshot describing one provider registered for one capability.
 *
 * Priority and status belong to the provider-capability binding rather than
 * globally to the provider because one provider may later implement several
 * capabilities with different priorities and health.
 */
data class ProviderBindingInfo(
    val capabilityId: String,
    val providerId: String,
    val priority: Int,
    val status: ProviderStatus,
    val providerClass: String,
    val registrationOrder: Long,
    val registeredAtMs: Long,
    val reason: String? = null,
)
