// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.debug

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local authorization gate for remote developer-data export.
 *
 * This preference is private to DieselBridge and defaults to disabled.
 * Protocol command modules receive only [DeveloperExportAuthorization], so a
 * remote request has no API through which it can enable this gate.
 */
fun interface DeveloperExportAuthorization {

    fun isEnabled(): Boolean
}

class DeveloperExportPolicy(
    context: Context,
) : DeveloperExportAuthorization {

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

        const val KEY_ENABLED =
            "remote_export_enabled"
    }
}
