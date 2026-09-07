// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local authorization gate for remote developer operations.
 *
 * This policy covers developer commands that expose runtime information or
 * actively exercise hardware. It can only be changed from the local watch
 * developer UI; remote protocol clients receive no API for enabling it.
 *
 * The gate defaults to disabled.
 */
fun interface DeveloperRemoteAccessAuthorization {

    fun isEnabled(): Boolean
}

/**
 * Persistent watch-local implementation of [DeveloperRemoteAccessAuthorization].
 *
 * The preference key intentionally keeps its historical M4.0c name so an
 * existing user's authorization choice survives this API generalization.
 */
class DeveloperRemoteAccessPolicy(
    context: Context,
) : DeveloperRemoteAccessAuthorization {

    private val preferences =
        context.applicationContext
            .getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )

    private val mutableEnabled =
        MutableStateFlow(
            preferences.getBoolean(
                KEY_ENABLED,
                false,
            ),
        )

    val enabled: StateFlow<Boolean> =
        mutableEnabled.asStateFlow()

    override fun isEnabled(): Boolean =
        mutableEnabled.value

    fun setEnabled(
        enabled: Boolean,
    ) {
        preferences
            .edit()
            .putBoolean(
                KEY_ENABLED,
                enabled,
            )
            .apply()

        mutableEnabled.value =
            enabled
    }

    private companion object {
        const val PREFERENCES_NAME =
            "diesel_developer"

        /*
         * Historical persisted key from the original remote-export-only gate.
         * Do not rename without an explicit SharedPreferences migration.
         */
        const val KEY_ENABLED =
            "remote_export_enabled"
    }
}
