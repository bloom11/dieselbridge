// SPDX-License-Identifier: Apache-2.0

package org.aaustralian.dieselbridge.phone

import android.app.Application

class DieselBridgePhoneApplication : Application() {
    lateinit var runtime: PhoneCompanionRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime =
            PhoneCompanionRuntime(this).also {
                it.start()
            }
    }
}
