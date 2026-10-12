package org.aaustralian.dieselbridge.ipc;

import android.os.Bundle;
import org.aaustralian.dieselbridge.ipc.IDieselActionCallback;
import org.aaustralian.dieselbridge.ipc.IDieselEventCallback;
import org.aaustralian.dieselbridge.ipc.IDieselObservationCallback;
import org.aaustralian.dieselbridge.ipc.IDieselProviderCallback;
import org.aaustralian.dieselbridge.ipc.IDieselTransportCallback;
import org.aaustralian.dieselbridge.ipc.IDieselTransportSendCallback;

interface IDieselSession {
    Bundle getCatalog();
    Bundle getState(String resourceId);

    void invokeAction(
        String resourceId,
        in Bundle input,
        IDieselActionCallback callback
    );

    Bundle subscribeObservation(
        String resourceId,
        long periodMs,
        IDieselObservationCallback callback
    );

    boolean unsubscribeObservation(long subscriptionId);

    Bundle subscribeEvents(
        in String[] resourceIds,
        IDieselEventCallback callback
    );

    boolean unsubscribeEvents(long subscriptionId);

    Bundle registerProviderResource(
        in Bundle request,
        IDieselProviderCallback callback
    );

    boolean unregisterProviderResource(long registrationId);

    Bundle publishProviderState(
        long registrationId,
        in Bundle value,
        boolean clear
    );

    Bundle publishProviderEvent(
        long registrationId,
        in Bundle value
    );

    Bundle completeProviderAction(
        long registrationId,
        long requestId,
        in Bundle result
    );

    Bundle pushProviderObservation(
        long registrationId,
        long acquisitionId,
        in Bundle update
    );

    Bundle setProviderAvailability(
        long registrationId,
        String availability,
        String reason
    );

    void openTransport(
        String resourceId,
        IDieselTransportCallback callback
    );

    void sendTransport(
        long leaseId,
        in byte[] frame,
        IDieselTransportSendCallback callback
    );

    boolean closeTransport(long leaseId);

    void close();
}
