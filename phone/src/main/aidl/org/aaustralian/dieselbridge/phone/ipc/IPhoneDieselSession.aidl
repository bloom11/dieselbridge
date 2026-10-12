package org.aaustralian.dieselbridge.phone.ipc;

import android.os.Bundle;
import org.aaustralian.dieselbridge.phone.ipc.IPhoneDieselResultCallback;

interface IPhoneDieselSession {
    Bundle getCatalog();

    void execute(
        in Bundle request,
        IPhoneDieselResultCallback callback
    );

    void close();
}
