package org.aaustralian.dieselbridge.ipc;

import android.os.Bundle;
import org.aaustralian.dieselbridge.ipc.IDieselClientToken;

interface IDieselPublicService {
    Bundle getServiceInfo();

    /**
     * status: ok | invalid_request | unsupported_version | invalid_client |
     *         runtime_unavailable | session_limit
     *
     * On success the Bundle also contains:
     *   session  -> IBinder implementing IDieselSession
     *   apiMajor -> negotiated public API major
     *   apiMinor -> negotiated public API minor
     */
    Bundle openSession(
        int clientMinMajor,
        int clientMaxMajor,
        IDieselClientToken clientToken
    );
}
