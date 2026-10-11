package org.aaustralian.dieselbridge.ipc;
import android.os.Bundle;
import org.aaustralian.dieselbridge.ipc.IDieselActionCallback;
interface IDieselSession {
    Bundle getCatalog();
    Bundle getState(String resourceId);
    void invokeAction(String resourceId, in Bundle input, IDieselActionCallback callback);
    void close();
}
