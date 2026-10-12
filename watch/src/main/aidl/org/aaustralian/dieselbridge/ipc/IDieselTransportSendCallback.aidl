package org.aaustralian.dieselbridge.ipc;

import android.os.Bundle;

oneway interface IDieselTransportSendCallback {
    void onResult(long leaseId, in Bundle result);
}
