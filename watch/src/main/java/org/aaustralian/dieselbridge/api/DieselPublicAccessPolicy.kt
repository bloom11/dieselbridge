// SPDX-License-Identifier: Apache-2.0
package org.aaustralian.dieselbridge.api
import android.content.Context
internal data class DieselCallerIdentity(val uid: Int, val packages: List<String>)
internal class DieselPublicAccessPolicy(private val context: Context) {
    fun canAccess(identity: DieselCallerIdentity, descriptor: DieselResourceDescriptor, operation: DieselResourceOperation): Boolean {
        @Suppress("UNUSED_VARIABLE") val caller=identity
        return operation in descriptor.operations
    }
}
