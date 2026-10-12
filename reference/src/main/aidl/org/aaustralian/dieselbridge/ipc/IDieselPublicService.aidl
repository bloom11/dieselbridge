package org.aaustralian.dieselbridge.ipc;

import android.os.Bundle;
import org.aaustralian.dieselbridge.ipc.IDieselClientToken;

interface IDieselPublicService {
    Bundle getServiceInfo();

    Bundle openSession(
        int clientMinMajor,
        int clientMaxMajor,
        IDieselClientToken clientToken
    );
}
