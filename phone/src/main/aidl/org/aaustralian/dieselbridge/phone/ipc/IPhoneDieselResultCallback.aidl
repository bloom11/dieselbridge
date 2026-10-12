package org.aaustralian.dieselbridge.phone.ipc;

import android.os.Bundle;

oneway interface IPhoneDieselResultCallback {
    void onResult(in Bundle result);
}
