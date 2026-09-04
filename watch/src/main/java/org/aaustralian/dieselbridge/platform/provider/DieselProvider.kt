// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.platform.provider

/**
 * Stable identity for an implementation that can expose one or more
 * Diesel capabilities.
 *
 * Provider identity is independent from capability identity. A single
 * provider may later expose several capabilities.
 */
interface DieselProvider {
    val providerId: String
}
