package org.aaustralian.dieselbridge.ipc;

import android.os.Bundle;

/*
 * M7.4b2 uses these callbacks for remote action and observation
 * providers. State/event providers in M7.4b1 need no callback, but the
 * registration signature is stabilized now.
 */
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
