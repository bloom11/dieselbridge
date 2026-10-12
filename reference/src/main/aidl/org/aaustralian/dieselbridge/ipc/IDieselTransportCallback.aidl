package org.aaustralian.dieselbridge.ipc;

import android.os.Bundle;

oneway interface IDieselTransportCallback {
    void onOpened(in Bundle result);
    void onFrame(long leaseId, in byte[] frame);
    void onClosed(long leaseId, String reason);
}
