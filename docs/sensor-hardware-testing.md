# SensorManager hardware campaign

DieselBridge distinguishes normal logical sensor reads from developer hardware research. The normal
`sensor.read` API automatically chooses a provider for a logical capability. This campaign uses
exact SensorManager routes so it can record every process-visible hardware route without assigning
medical meaning to a vendor name or raw vector.

`tools/diesel-watch-sensor-test` is the supported phone-Termux helper. It pages the live
`sensor.list` inventory before selecting anything, so it neither hard-codes TicWatch routes nor
guesses Android IDs. It can print every route, select any one by inventory index or opaque route ID,
or probe every listed route sequentially and export per-route evidence as JSON outside the repository.

## Install and confirm

Use the `dieselbridge-bloom-debug` artifact from the successful **DieselBridge CI** run. Install
`watch-debug.apk` with `adb install -r` using the current paired watch connection. Identify a build
by its in-app version, commit, CI run, and build timestamp; do not assume an old ADB address is current.

Gadgetbridge remains the BLE owner. The commands below run in phone Termux, not in `adb shell` on
the watch. `tools/diesel-adb` sends the request through Gadgetbridge and uses the watch ADB log
mirror only to correlate the response.

## Route discovery and selection

Set `DIESEL_ADB_SERIAL` when more than one ADB device is visible.

```sh
cd ~/dieselbridge
export DIESEL_ADB_SERIAL='watch-ip:wireless-debugging-port'

# Read-only: print every currently process-visible route.
tools/diesel-watch-sensor-test --list

# Interactive: display the same inventory, then enter one displayed index or route ID.
tools/diesel-watch-sensor-test

# Probe one exact live inventory index for the first event.
tools/diesel-watch-sensor-test --select 0 --timeout-ms 5000

# Probe one exact route copied from this invocation's live inventory.
tools/diesel-watch-sensor-test --select 'android.sensor_manager:1:0:0' --timeout-ms 5000

# Probe every route in this inventory once, then save each response under ~/dieselbridge-sensor-tests/.
tools/diesel-watch-sensor-test --all
```

Leave **REMOTE DEVELOPER ACCESS** disabled for one valid selected-route attempt to confirm
`unavailable / remote_developer_access_disabled`. Enable it only on the watch developer page before
the intended probe or whole-watch scan. Remote commands and this helper cannot enable that setting.

## Route and command contract

`sensor.list` is read-only and returns a paged process-visible Android SensorManager census. Each
item contains the stable-in-that-snapshot `index`, opaque `routeId`, logical `id`, provider ID,
Android ID/type, string type, display name, vendor, wake-up flag and reporting mode. The route ID is
`android.sensor_manager:<androidType>:<androidId>:<ordinal>` today, but clients must treat it as
opaque and obtain it from the current list because inventory may change across watch software or
hardware states.

`debug.sensor.probe` accepts only `routeId` and optional `timeoutMs` (500-15000, default 5000).
It resolves the exact current route, registers either a listener or trigger sensor, returns the first
event and always unregisters. It returns `status:"ok"` with one of `event`, `timeout`,
`permission_denied`, `registration_rejected` or `route_unavailable`; each is useful hardware
evidence. Event responses retain raw values, SensorManager monotonic timestamp, accuracy and
registration metadata. They do not infer units or medical meaning.

`tools/diesel-watch-sensor-test --all` invokes `debug.sensor.probe` once for every route
from that single inventory snapshot, waits for each correlated response, and writes the report after
each attempt. It is the complete route campaign, so it may take several minutes when routes time out.

`debug.sensor.scan.start` is a separate quick diagnostic: one active background scan, two seconds
per route, 60 seconds total and at most 256 in-memory records. It can therefore finish with
`time_budget_exhausted` before every route has been attempted. `debug.sensor.scan.status`
reports progress or the terminal state, `debug.sensor.scan.cancel` cancels it, and
`debug.export` section `sensor_probes` pages route identity, outcome, registration kind, timing,
permission or rejection reason, timestamp, accuracy and up to 16 raw values for its captured records.

## Evidence to collect

1. Accelerometer, light and pressure: event contents, latency, and clean repeated reads.
2. Steps, then heart rate with the existing permission set. A `permission_denied` outcome is useful
   evidence; do not add or grant a sensor permission before recording the initial result.
3. Vendor PPG, SpO2/RR-labelled routes and vendor temperature: retain raw vectors and route metadata;
   do not assume units or call a vendor temperature skin temperature.
4. One-shot routes: expect `registration.kind:"trigger"`, with null accuracy and sampling period.
   Exercise the relevant physical trigger where understood; a timeout alone does not prove a sensor
   is broken. Test wake-up and vendor routes individually and keep each timeout bounded.
5. Revoke remote access and verify a subsequent probe is refused. Stop the bridge during a pending
   probe if testing lifecycle cancellation, then restart and confirm ordinary requests recover.

For every attempt preserve the JSON report, CI commit/APK identity, watch build, whether the watch
was worn/moving/charging, and any deliberate trigger. `sensorTimestampNs` is not wall-clock time.
`permission_denied`, `timeout`, `registration_rejected`, and `route_unavailable` are
successful diagnostic observations. `rate_limited` means the command was not admitted; wait before
trying again. A missing response is a transport or lifecycle uncertainty, not a sensor outcome.
