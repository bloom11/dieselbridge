package org.aaustralian.dieselbridge.ipc;
import android.os.Bundle;
import org.aaustralian.dieselbridge.ipc.IDieselActionCallback;
import org.aaustralian.dieselbridge.ipc.IDieselObservationCallback;
interface IDieselSession {
    Bundle getCatalog();
    Bundle getState(String resourceId);
    void invokeAction(String resourceId, in Bundle input, IDieselActionCallback callback);
    Bundle subscribeObservation(String resourceId, long periodMs, IDieselObservationCallback callback);
    boolean unsubscribeObservation(long subscriptionId);
    void close();
}
