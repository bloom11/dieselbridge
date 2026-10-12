package org.aaustralian.dieselbridge.ipc;
import android.os.Bundle;
oneway interface IDieselEventCallback {
    void onEvents(in Bundle batch);
}
