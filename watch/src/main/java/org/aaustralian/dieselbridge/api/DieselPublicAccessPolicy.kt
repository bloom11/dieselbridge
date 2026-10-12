// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api

internal data class DieselCallerIdentity(
    val uid: Int,
    val packages: List<String>,
) {
    val label: String =
        if (packages.isEmpty()) "uid:$uid" else "uid:$uid:${packages.joinToString(",")}"
}

internal fun interface DieselPermissionChecker {
    fun hasPermission(uid: Int, permission: String): Boolean
}

internal class DieselPublicAccessPolicy(
    private val sdkInt: Int = 0,
    private val permissionChecker: DieselPermissionChecker =
        DieselPermissionChecker { _, _ -> false },
) {
    fun canAccess(
        identity: DieselCallerIdentity,
        descriptor: DieselResourceDescriptor,
        operation: DieselResourceOperation,
    ): Boolean {
        if (identity.packages.isEmpty() || operation !in descriptor.operations) return false

        return when (descriptor.id) {
            DieselDefaultPublicApi.COMPANION_NEXT_ALARM.id ->
                descriptor.kind == DieselResourceKind.STATE &&
                    operation == DieselResourceOperation.READ

            DieselDefaultPublicApi.WATCH_VIBRATION.id ->
                descriptor.kind == DieselResourceKind.ACTION &&
                    operation == DieselResourceOperation.INVOKE

            in DieselDefaultPublicApi.NOTIFICATION_STATE_IDS ->
                descriptor.kind == DieselResourceKind.STATE &&
                    operation == DieselResourceOperation.READ

            in DieselDefaultPublicApi.NOTIFICATION_ACTION_IDS ->
                descriptor.kind == DieselResourceKind.ACTION &&
                    operation == DieselResourceOperation.INVOKE

            in DieselDefaultPublicApi.OBSERVATION_IDS ->
                canObserve(identity, descriptor, operation)

            in DieselDefaultPublicApi.EVENT_IDS ->
                descriptor.kind == DieselResourceKind.EVENT &&
                    operation == DieselResourceOperation.SUBSCRIBE

            else ->
                when {
                    descriptor.id.startsWith("plugin.") &&
                        descriptor.kind == DieselResourceKind.STATE ->
                        operation == DieselResourceOperation.READ

                    descriptor.id.startsWith("plugin.") &&
                        descriptor.kind == DieselResourceKind.ACTION ->
                        operation == DieselResourceOperation.INVOKE

                    descriptor.id.startsWith("plugin.") &&
                        descriptor.kind == DieselResourceKind.EVENT ->
                        operation == DieselResourceOperation.SUBSCRIBE

                    descriptor.id.startsWith("sensor.observe.plugin.") &&
                        descriptor.kind == DieselResourceKind.OBSERVATION ->
                        operation == DieselResourceOperation.SUBSCRIBE

                    descriptor.kind == DieselResourceKind.TRANSPORT ->
                        operation == DieselResourceOperation.OPEN

                    else ->
                        false
                }
        }
    }

    private fun canObserve(
        identity: DieselCallerIdentity,
        descriptor: DieselResourceDescriptor,
        operation: DieselResourceOperation,
    ): Boolean {
        if (
            descriptor.kind != DieselResourceKind.OBSERVATION ||
            operation != DieselResourceOperation.SUBSCRIBE
        ) return false

        return when (descriptor.id) {
            "sensor.observe.heart_rate" ->
                permissionChecker.hasPermission(identity.uid, "android.permission.BODY_SENSORS")

            "sensor.observe.step_counter" ->
                sdkInt < 29 ||
                    permissionChecker.hasPermission(identity.uid, "android.permission.ACTIVITY_RECOGNITION")

            else -> true
        }
    }
}
