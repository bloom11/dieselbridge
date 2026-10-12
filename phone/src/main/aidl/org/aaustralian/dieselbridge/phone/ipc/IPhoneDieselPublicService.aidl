package org.aaustralian.dieselbridge.phone.ipc;

import android.os.Bundle;
import org.aaustralian.dieselbridge.phone.ipc.IPhoneDieselClientToken;

interface IPhoneDieselPublicService {
    Bundle getServiceInfo();

    Bundle openSession(
        int clientMinMajor,
        int clientMaxMajor,
        IPhoneDieselClientToken clientToken
    );
}
