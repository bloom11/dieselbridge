package org.aaustralian.dieselbridge.ipc;
import android.os.Bundle;
oneway interface IDieselObservationCallback {
    void onUpdate(in Bundle update);
}
