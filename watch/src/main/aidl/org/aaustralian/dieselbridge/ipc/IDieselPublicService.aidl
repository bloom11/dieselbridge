package org.aaustralian.dieselbridge.ipc;
import android.os.Bundle;
import org.aaustralian.dieselbridge.ipc.IDieselClientToken;
import org.aaustralian.dieselbridge.ipc.IDieselSession;
interface IDieselPublicService {
    Bundle getServiceInfo();
    IDieselSession openSession(int clientMinMajor, int clientMaxMajor, IDieselClientToken clientToken);
}
