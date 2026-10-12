package org.aaustralian.dieselbridge.ipc;

import android.os.Bundle;

oneway interface IDieselProviderCallback {
    void onActionRequest(
        long registrationId,
        long requestId,
        String resourceId,
        in Bundle input
    );

    void onObservationStart(
        long registrationId,
        long acquisitionId,
        String resourceId,
        long preferredSamplePeriodMs
    );

    void onObservationStop(
        long registrationId,
        long acquisitionId
    );
}
