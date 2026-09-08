# First SensorManager hardware campaign

This build adds `debug.sensor.probe` (M4.2b2). Its purpose is to discover what this watch actually
exposes, not to assign medical meaning to vendor names. It returns the first event and unregisters;
it does not stream, persist a scan or run every sensor automatically. Whole-watch scan orchestration
is M4.3. Public capability/provider routing is M4.2c.

## Install and confirm

Use the `dieselbridge-bloom-debug` artifact from the successful **DieselBridge CI** run for the probe
commit. Install `watch-debug.apk` with `adb install -r` using the current paired watch connection.
The Bloom debug signing lineage and version 24 / 1.0.0-dev.19 are preserved. Because the version is
unchanged, identify this build by its CI commit/artifact and the presence of `debug.sensor.probe` in
`commands`, rather than the Android version label alone. Do not assume an old ADB address is current.

Gadgetbridge remains the BLE owner. The commands below run in **phone Termux**, not `adb shell` on
the watch. Configure response capture before probing: Gadgetbridge delivers the fixed action
`io.github.bloom11.dieselbridge.DIESEL_MESSAGE`, whose `json` extra is the response envelope. Save
that complete JSON, including the request ID. Broadcast submission result 0 is not proof that the
watch ran the command or that the reply was received.

```sh
cd ~/dieselbridge
am broadcast -a com.banglejs.uart.tx --es line 'GB({"t":"diesel","id":"hw-commands","cmd":"commands"})'
am broadcast -a com.banglejs.uart.tx --es line 'GB({"t":"diesel","id":"hw-access","cmd":"debug.status"})'
am broadcast -a com.banglejs.uart.tx --es line 'GB({"t":"diesel","id":"hw-list-0","cmd":"sensor.list","args":{"offset":0,"limit":4}})'
```

Send requests one at a time and wait for their correlated responses. Advance list offset by the
returned count until `hasMore:false`; retain all pages as the inventory for this campaign. Full
metadata is also available from `debug.export` section `sensors`, once locally authorized.

## Authorization check and one route

First leave **REMOTE DEVELOPER ACCESS** disabled on the watch developer page and submit one valid
probe copied from the current inventory. Expect `unavailable / remote_developer_access_disabled`.
Then enable access locally and repeat with a new request ID. Remote commands cannot enable access.

This interactive block reads an exact route ID from you, safely forms the JSON without shell
interpolation, and sends one request. Copy an accelerometer/light/pressure route from the actual
inventory for the first authorized attempt. It sends no DLE prefix or extra newline at the Intent
boundary. It only submits the request; collect the response through your configured receiver.

```sh
cd ~/dieselbridge
python3 -c '
import json, subprocess, uuid
route = input("Route ID from sensor.list: ").strip()
if route and len(route) <= 128 and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in route):
    request_id = "hw-" + uuid.uuid4().hex[:12]
    request = {"t": "diesel", "id": request_id, "cmd": "debug.sensor.probe",
               "args": {"routeId": route, "timeoutMs": 5000}}
    print("Save response ID:", request_id)
    subprocess.run(["am", "broadcast", "-a", "com.banglejs.uart.tx", "--es", "line",
                    "GB(" + json.dumps(request, separators=(",", ":")) + ")"], check=True)
else:
    print("Invalid route ID; nothing sent.")
'
```

## Evidence to collect

1. Accelerometer, light and pressure: event contents, latency, and clean repeated reads.
2. Steps, then heart rate with the existing permission set. A `permission_denied` outcome is useful
   evidence; do not add/grant new sensor permissions before recording the initial results.
3. Vendor PPG, SpO2/RR-labelled routes and vendor temperature: retain raw vectors and route metadata;
   do not assume units or call vendor temperature skin temperature.
4. One-shot routes: expect `registration.kind:"trigger"`, with null accuracy and sampling period.
   Exercise the relevant physical trigger where understood; a timeout alone does not prove a sensor
   is broken. Test wake-up/vendor routes individually and keep each timeout bounded.
5. Revoke remote access and verify a subsequent probe is refused. Stop the bridge during a pending
   probe if testing lifecycle cancellation, then restart and confirm ordinary requests recover.

For every attempt preserve the complete request and response, CI commit/APK identity, watch build,
whether the watch was worn/moving/charging, and any deliberate trigger. `sensorTimestampNs` is not
a wall-clock time. `permission_denied`, `timeout`, `registration_rejected` and `route_unavailable`
are successful diagnostic observations. `rate_limited` means the command was not admitted; wait
before trying again. A missing response is a transport/lifecycle uncertainty, not a sensor outcome.

The next development decisions depend on these records: which routes are usable, which permissions
are needed, where Health Services adds value, and which vendor APIs actually require investigation.
