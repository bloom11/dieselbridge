// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

/**
 * Safety classification exposed as metadata on generic DieselCommandSpec
 * registrations.
 */
enum class DeveloperCommandEffect(
    val wireName: String,
) {
    READ_ONLY("read_only"),
    SAFE_ACTION("safe_action"),
}
