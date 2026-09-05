// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.ui.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class DiagnosticsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DiagnosticsScreen()
        }
    }
}
