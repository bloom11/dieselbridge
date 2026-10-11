// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.api

/**
 * Identity captured once when a Binder session opens.
 *
 * A UID can resolve to multiple packages, so retain the full package set
 * instead of guessing one package name.
 */
internal data class DieselCallerIdentity(
    val uid: Int,
    val packages: List<String>,
) {
    val label: String =
        if (packages.isEmpty()) {
            "uid:$uid"
        } else {
            "uid:$uid:${packages.joinToString(",")}"
        }
}

/**
 * M7.2 external-consumer authorization.
 *
 * The manifest USE_DIESEL_API permission is only an explicit application
 * opt-in. This policy is a separate default-deny boundary: adding a resource
 * descriptor does not automatically expose it to another process.
 */
internal class DieselPublicAccessPolicy {
    fun canAccess(
        identity: DieselCallerIdentity,
        descriptor: DieselResourceDescriptor,
        operation: DieselResourceOperation,
    ): Boolean {
        if (identity.packages.isEmpty()) {
            return false
        }

        if (operation !in descriptor.operations) {
            return false
        }

        return when (descriptor.id) {
            DieselDefaultPublicApi.COMPANION_NEXT_ALARM.id ->
                descriptor.kind == DieselResourceKind.STATE &&
                    operation == DieselResourceOperation.READ

            DieselDefaultPublicApi.WATCH_VIBRATION.id ->
                descriptor.kind == DieselResourceKind.ACTION &&
                    operation == DieselResourceOperation.INVOKE

            else ->
                false
        }
    }
}
