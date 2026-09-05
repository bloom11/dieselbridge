// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ui.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class DiagnosticsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val openCommands =
            intent.getBooleanExtra(
                EXTRA_OPEN_COMMANDS,
                false,
            )

        setContent {
            DiagnosticsScreen(
                openCommandsInitially = openCommands,
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_COMMANDS =
            "org.aaustralian.dieselbridge.OPEN_COMMANDS"
    }
}
