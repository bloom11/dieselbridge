# DieselBridge

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

DieselBridge is a standalone Wear OS bridge and experimental watch platform built around direct
Bluetooth Low Energy communication with **unmodified Gadgetbridge**. It began as a Wear OS backport
of PixelBridge and has evolved into a broader Diesel platform: notification/call/media bridging,
a generic versioned command protocol, developer diagnostics, logical sensor capabilities,
automatic provider selection, bounded hardware experiments, Health Services integration, power
controls, and tooling for real-watch validation.

The current active development line is `feature/diesel-platform`.

---

## Contents

- [Project status](#project-status)
- [What DieselBridge does today](#what-dieselbridge-does-today)
- [Architecture](#architecture)
- [Phone/watch topology](#phonewatch-topology)
- [Build identity and supported Android range](#build-identity-and-supported-android-range)
- [Setup](#setup)
- [Using DieselBridge](#using-dieselbridge)
- [BLE transport](#ble-transport)
- [Diesel protocol](#diesel-protocol)
- [Command catalogue](#command-catalogue)
- [Diesel platform and capability routing](#diesel-platform-and-capability-routing)
- [Sensors](#sensors)
- [Health Services](#health-services)
- [Developer sensor diagnostics](#developer-sensor-diagnostics)
- [Hardware test tooling](#hardware-test-tooling)
- [Power and battery tools](#power-and-battery-tools)
- [Developer UI and build provenance](#developer-ui-and-build-provenance)
- [Testing and CI](#testing-and-ci)
- [No-Google constraint](#no-google-constraint)
- [Troubleshooting](#troubleshooting)
- [Evidence and validation status](#evidence-and-validation-status)
- [Current limitations](#current-limitations)
- [Roadmap / project hand-off](#roadmap--project-hand-off)
- [Historical branches](#historical-branches)
- [License](#license)

---

# Project status

Current development build configuration on `feature/diesel-platform`:

```text
namespace       org.aaustralian.dieselbridge
applicationId   io.github.bloom11.dieselbridge

compileSdk      36
minSdk          28
targetSdk       28

versionCode     26
versionName     1.0.0-dev.21

Java/Kotlin     17 bytecode
CI JDK          21
```

`minSdk=28` means the application keeps an Android 9 / legacy Wear OS floor. `targetSdk=28` is an
intentional legacy target behavior choice; it does **not** mean the application targets Android 15.
The project still compiles against SDK 36.

The current development architecture is materially newer than the original PixelBridge-style
notification bridge. The active branch contains a generic Diesel protocol engine, a platform
capability registry, public logical sensor reads, developer-only exact sensor diagnostics, a
Health Services provider, bounded sensor experiments, developer power controls, build provenance,
and Termux/ADB hardware-test tooling.

The repository intentionally contains only the Wear OS application. The phone side is stock,
unmodified Gadgetbridge.

---

# What DieselBridge does today

## Core bridge functionality

Implemented bridge features include:

- phone notifications on the watch;
- native watch notification cards and the in-app notification list;
- notification dismiss;
- reply through Android `RemoteInput`;
- clear-all behavior for DieselBridge cards;
- find-my-watch;
- find-phone;
- incoming/outgoing call state and watch call controls;
- phone media metadata and transport controls;
- canned quick replies synchronized from Gadgetbridge;
- watch battery/version reporting to Gadgetbridge;
- notification and music Wear OS tiles;
- long-lived BLE foreground service;
- automatic BLE advertising restart after Bluetooth state changes.

## Diesel platform functionality

The current development branch additionally provides:

- generic versioned Diesel request/response protocol;
- bounded command execution lane;
- command discovery from the live command registry;
- developer build provenance;
- debug response mirroring over watch logcat/ADB;
- public logical `sensor.read`;
- public bounded `sensor.matrix`;
- public bounded `sensor.experiment`;
- process-visible `sensor.list`;
- exact developer route probe;
- asynchronous bounded whole-watch route scanning;
- SensorManager provider;
- optional Health Services heart-rate provider;
- automatic provider priority/failover through `CapabilityRegistry`;
- developer battery/electrical readings;
- 24-hour foreground-usage snapshot where Android grants usage access;
- DieselBridge app power modes.

---

# Architecture

The Diesel platform is the internal center of the watch application.

```text
                           Consumers
              ┌──────────────┼───────────────┐
              │              │               │
          Watch UI      Diesel protocol   future APIs
              │              │
              └──────┬───────┘
                     ▼
               Diesel Platform
                     │
            CapabilityRegistry
                     │
          highest-priority AVAILABLE
                     │
       ┌─────────────┴─────────────┐
       ▼                           ▼
 SensorManager provider      Health Services
     priority 10               priority 20
       │                           │
       └─────────────┬─────────────┘
                     ▼
                 capability
```

The main architectural rule is that a normal consumer asks for a **logical capability**, not a
specific Android sensor route or implementation.

For example:

```text
sensor.read + name=accelerometer
        ↓
SensorReadCoordinator
        ↓
CapabilityRegistry
        ↓
selected SensorCapability
        ↓
provider performs bounded read
        ↓
SensorReading
```

Exact Android route identifiers are diagnostic identities. They are deliberately not the public
selection mechanism.

## Major platform pieces

The platform contains or uses these concepts:

- `DieselPlatform`
- `CapabilityRegistry`
- `DieselCapability`
- provider bindings and provider availability
- platform diagnostics/event state
- `DieselCommandRegistry`
- `DieselProtocolEngine`
- `DieselProtocolExecutionLane`
- `DieselResponseTransport`
- typed internal sensor APIs
- `DieselValue` only at the protocol boundary

Multiple providers may implement the same capability. `CapabilityRegistry` chooses the
highest-priority provider whose state is `AVAILABLE`. If the active provider becomes unavailable or
enters an error state, the next usable implementation can become active without changing the
consumer-facing command.

Local watch UI actions dispatch directly into the shared command/platform objects. They do not
round-trip through BLE merely to call code on the same watch.

---

# Phone/watch topology

The production phone/watch topology is:

```text
 Android phone                                         Wear OS watch
┌─────────────────────────────┐                   ┌─────────────────────────────┐
│ Gadgetbridge                │                   │ DieselBridge                │
│                             │                   │                             │
│ NotificationListener        │                   │ DieselBridgeService         │
│ Bangle.js support           │                   │ NUS GATT server             │
│ BLE CENTRAL                 │◀──── BLE/NUS ────▶│ BLE PERIPHERAL              │
│                             │                   │ Diesel protocol engine      │
└─────────────────────────────┘                   └─────────────────────────────┘
```

The phone side is **not** a custom Gadgetbridge fork and DieselBridge does not create a second BLE
owner. Gadgetbridge owns the central connection.

The watch advertises a Bangle-compatible name; current code uses:

```text
Bangle.js Diesel
```

and exposes Nordic UART Service.

---

# Build identity and supported Android range

The app package is:

```text
io.github.bloom11.dieselbridge
```

The Kotlin/Java namespace is:

```text
org.aaustralian.dieselbridge
```

Development builds contain build provenance through generated BuildConfig fields:

```text
BUILD_GIT_SHA
BUILD_TIMESTAMP_UTC
BUILD_CI_RUN_ID
```

The developer build-information command exposes enough information to distinguish two APKs even
when a developer forgot to increment `versionName`.

The project uses a persistent development signing lineage in CI. Do not replace the development
certificate casually: doing so prevents an in-place `adb install -r` over an APK signed with the
existing key.

---

# Setup

## Requirements

You need:

- a Wear OS watch compatible with the app's API floor;
- an Android phone running Gadgetbridge;
- Bluetooth on both devices;
- ADB for watch development/sideloading;
- JDK 21 and Android SDK when building outside CI.

For this project, GitHub Actions is the canonical compile/unit-test environment.

## Clone

```bash
git clone https://github.com/bloom11/dieselbridge.git
cd dieselbridge
git switch feature/diesel-platform
```

## Build

Typical local Android build:

```bash
./gradlew :watch:testDebugUnitTest :watch:assembleDebug
```

For Termux-only development without an Android SDK, do not treat a local Gradle invocation as the
canonical build. Push the reviewed branch and use the CI artifact.

## Wireless ADB

Modern Wear OS versions normally use wireless debugging with a pairing port and a separate
connection port.

```bash
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:CONNECTION_PORT
adb devices
```

The connection port may rotate after sleep/reboot.

Legacy Wear OS devices can use the older TCP/5555 style where supported.

## Install

Use replacement install so the signing lineage and application data are preserved:

```bash
adb -s WATCH_SERIAL install -r watch-debug.apk
```

Do not use uninstall/reinstall as a normal solution to a signature mismatch.

## Watch permissions

Normal bridge operation may require:

- Bluetooth advertise/connect on modern Android;
- legacy Bluetooth/location permissions on older Android;
- notifications;
- foreground-service permissions.

Optional developer Health Services heart-rate reads require `BODY_SENSORS` on the currently
supported target behavior.

## Gadgetbridge

1. Install unmodified Gadgetbridge.
2. Grant Notification Access.
3. Add the watch as a Bangle.js-compatible device.
4. Connect to `Bangle.js Diesel`.
5. Enable forwarding notifications while the phone screen is on if desired.

---

# Using DieselBridge

## Notifications

Incoming phone notifications are rendered by the watch and kept in the DieselBridge list.

Supported actions include:

```text
DISMISS
DISMISS_ALL
REPLY
```

Reply text is passed back to Gadgetbridge and delivered through the original Android notification's
`RemoteInput`.

## Calls

Gadgetbridge call-state messages can open the watch call UI. Watch controls are relayed back to the
phone; call audio remains on the phone or its normal audio route.

## Music

Gadgetbridge supplies now-playing metadata and state. DieselBridge can send playback/volume control
events back through the existing Bangle.js control path.

## Find phone / find watch

Gadgetbridge can trigger the watch alert. DieselBridge can send `findPhone` back to Gadgetbridge.

## Tiles

The app includes notification and music tiles for glanceable watch access.

---

# BLE transport

DieselBridge uses Nordic UART Service.

| Attribute | UUID | Direction |
|---|---|---|
| NUS service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | service |
| RX | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | phone → watch |
| TX | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | watch → phone |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | TX subscription |

The watch is the BLE peripheral/GATT server. Gadgetbridge is the BLE central.

## Framing

Phone-to-watch Bangle traffic is conventionally:

```text
0x10 + GB(<JSON>) + \n
```

The leading `0x10` is the Espruino echo-control byte.

NUS is a byte stream. Packet boundaries are not message boundaries. The watch reassembles inbound
bytes and splits by newline.

Watch-to-phone JSON lines use CRLF framing compatible with Gadgetbridge's Bangle parser.

## MTU and queueing

The implementation cannot assume one JSON line fits one ATT packet. Outbound data is queued and
chunked according to the active MTU.

The current bounded NUS TX design accepts complete lines only if they fit the configured queued
payload budget. If a whole line cannot be admitted, no partial line should be enqueued.

---

# Diesel protocol

Diesel is a versioned structured command protocol carried through the existing Bangle/NUS
connection.

A canonical v1 request looks like:

```json
{
  "t": "diesel",
  "v": 1,
  "kind": "request",
  "id": "req-42",
  "cmd": "sensor.read",
  "name": "accelerometer",
  "args": {
    "timeoutMs": 5000
  }
}
```

The shorthand form remains accepted where fields are omitted.

Conceptually:

```text
external transport
    ↓
DieselRequest
    ↓
DieselProtocolEngine
    ↓
DieselCommandRegistry
    ↓
registered module
    ↓
DieselCommandResult
    ↓
DieselResponse
    ↓
DieselResponseTransport
```

Command implementations do not serialize Gadgetbridge JSON themselves and do not know about BLE.

## Response

Example:

```json
{
  "v": 1,
  "kind": "response",
  "id": "req-42",
  "cmd": "sensor.read",
  "name": "accelerometer",
  "status": "ok",
  "data": {
    "outcome": "event"
  }
}
```

Current response statuses include:

```text
ok
unavailable
rate_limited
failed
unknown_command
unknown_target
invalid_request
```

## Android response bridge

The normal production response transport serializes the Diesel response inside a fixed
Gadgetbridge `t:"intent"` message.

The Android action is fixed:

```text
io.github.bloom11.dieselbridge.DIESEL_MESSAGE
```

and the structured Diesel response JSON is placed in the `json` extra.

A remote request cannot choose an arbitrary Android Intent action.

## Protocol limits

The Diesel control plane is intentionally bounded.

Important limits include:

- maximum request JSON: 4096 UTF-8 bytes;
- maximum response JSON: 4096 UTF-8 bytes;
- bounded nesting depth;
- bounded collection sizes;
- bounded total structured nodes;
- bounded field/text lengths;
- finite decimal values at the protocol boundary.

Large files/streams do not belong in this control plane.

## Execution lane

Execution is bounded and FIFO:

```text
1 running
4 waiting
```

Overload can produce:

```text
status = rate_limited
reason = execution_queue_full
```

Feedback also has bounded capacity. There is no promise that every admitted request receives a
successful end-to-end BLE delivery: disconnection, cancellation, or TX congestion can prevent it.

Clients should correlate by request ID and use bounded retry/timeouts.

---

# Command catalogue

Current command modules expose the following command families.

## Core developer commands

```text
diagnostics
commands
debug.build.info
test
```

`commands` returns the live command catalogue generated from the same registry used for execution.

`test` currently exposes bounded safe test targets such as vibration.

## Developer export

```text
debug.status
debug.export
```

Developer export is controlled by a watch-local authorization policy. Remote code cannot enable
itself.

## Public sensor commands

```text
sensor.list
sensor.read
sensor.matrix
sensor.experiment
```

`sensor.experiment` is currently deliberately a normal bounded read-only sensor command, not a
`debug.*` command. This README documents current code behavior; changing its namespace should be an
explicit protocol decision rather than a documentation-only rewrite.

## Developer exact-route diagnostics

```text
debug.sensor.probe
debug.sensor.scan.start
debug.sensor.scan.status
debug.sensor.scan.cancel
```

After the authorization consistency fix, syntactically valid remote developer sensor diagnostics
with developer access disabled return:

```text
status = unavailable
reason = remote_developer_access_disabled
```

rather than being mislabeled as malformed requests.

---

# Diesel platform and capability routing

`CapabilityRegistry` supports multiple implementations of one logical capability.

Each binding has:

- capability ID;
- provider ID;
- priority;
- availability/status;
- registration order;
- diagnostic metadata.

Normal consumers resolve by capability ID.

For sensors this means:

```text
sensor.accelerometer
sensor.gyroscope
sensor.heart_rate
...
```

not:

```text
android.sensor_manager:1:0:0
```

Route IDs remain diagnostic identities.

---

# Sensors

## Canonical logical sensor targets

The current standard logical sensor catalogue is:

```text
accelerometer
gyroscope
magnetic_field
light
pressure
ambient_temperature
heart_rate
step_counter
```

The canonical capability ID is:

```text
sensor.<logical-name>
```

Examples:

```text
sensor.accelerometer
sensor.heart_rate
sensor.step_counter
```

## Typed sensor results

Internal reads return one of:

```text
Event
Unavailable
PermissionDenied
Timeout
RegistrationRejected
```

A successful event includes:

- capability ID;
- selected provider ID;
- values;
- sensor timestamp;
- accuracy where available;
- elapsed/time-to-event.

## `sensor.read`

Public read request:

```json
{
  "cmd": "sensor.read",
  "name": "accelerometer",
  "args": {
    "timeoutMs": 5000
  }
}
```

The caller does not select a concrete provider or route.

Example successful response data:

```json
{
  "outcome": "event",
  "capability": "sensor.accelerometer",
  "providerId": "android.sensor_manager",
  "accuracy": 3,
  "sensorTimestampNs": 123456789,
  "timeToEventMs": 42,
  "valueCount": 3,
  "returnedValueCount": 3,
  "valuesTruncated": false,
  "values": [0.1, 9.7, -0.2]
}
```

Non-finite raw floats are not silently clamped. The JSON-safe value becomes `null` and a
`nonFinite` annotation identifies the original category/index.

Unknown targets and known-but-unavailable targets remain distinct:

```text
unknown target
→ unknown_target

known target with no usable provider
→ unavailable
```

## SensorManager provider

The SensorManager provider offers the standard logical capabilities.

When multiple concrete Android sensors match one logical capability, the implementation currently
prefers:

1. non-wakeup sensors before wakeup sensors;
2. lower reported sensor power;
3. deterministic route-ID ordering as a tie breaker.

That selection is internal implementation policy and not part of the public wire API.

## `sensor.matrix`

`sensor.matrix` performs one bounded logical read for each of the eight canonical targets.

The matrix uses the same capability-routing mechanism as individual reads and therefore exercises
provider selection rather than diagnostic route IDs.

## `sensor.experiment`

`sensor.experiment` performs bounded repeated logical reads.

Current constraints:

```text
rounds              1..8
per-read timeout     500..15000 ms
total timeout        1000..30000 ms
```

Experiments are serialized so two sensor experiments do not activate hardware concurrently through
this runner.

---

# Health Services

The current branch contains an optional Wear OS Health Services provider.

Provider ID:

```text
wear.health_services
```

Current implemented logical capability:

```text
sensor.heart_rate
```

The current provider performs bounded on-demand spot measurements through Health Services
`MeasureClient`. It is not yet the old branch's passive always-on health collector.

Provider priorities are currently:

```text
Health Services      20
SensorManager        10
```

Health Services starts as unavailable and becomes selectable only when:

- the required permission is granted; and
- the watch reports support for the requested measurement.

The developer UI can request heart-rate permission and refresh provider availability afterwards.

If Health Services is unavailable, the ordinary SensorManager implementation remains the lower
priority fallback when one exists.

## Steps and passive health

`step_counter` currently remains part of the standard logical sensor surface, with SensorManager as
the implemented normal source.

An older historical `Health` branch experimented with passive Health Services heart-rate and daily
step monitoring. That code is useful design evidence for future passive/streaming health support,
but it is **not** the behavior of the current `feature/diesel-platform` Health Services provider.

Do not document passive Health Services steps as currently implemented.

---

# Developer sensor diagnostics

Public logical sensor APIs and exact-route diagnostics serve different purposes.

```text
Public:
sensor.read
name = accelerometer

Developer diagnostic:
debug.sensor.probe
routeId = android.sensor_manager:...
```

## `sensor.list`

`sensor.list` exposes a paged process-visible SensorManager inventory.

It is primarily useful for diagnostics and hardware discovery.

Route IDs are opaque. Clients should copy them from live inventory and should not manufacture them
from an assumed provider-specific grammar.

Vendor/private sensor string types can remain provisional logical identities such as:

```text
android_type_33171103
```

until hardware experiments establish their meaning.

## Exact probe

`debug.sensor.probe` accepts:

```text
routeId     required
timeoutMs   optional, 500..15000
```

It performs a fresh exact-route resolution and a bounded first-event registration.

Possible operational outcomes include:

```text
event
timeout
permission_denied
registration_rejected
route_unavailable
```

Successful diagnostic events preserve detailed evidence including route metadata, registration kind,
accuracy, sensor timestamp, time to event, and raw values.

## Whole-watch async scan

Developer scan commands:

```text
debug.sensor.scan.start
debug.sensor.scan.status
debug.sensor.scan.cancel
```

The in-watch scanner is deliberately bounded:

```text
one active scan
2 s default maximum per route
60 s total default budget
256 evidence records
8 retained run summaries
```

Terminal reasons include:

```text
finished
cancelled
time_budget_exhausted
```

This is a fast local diagnostic, not a guarantee that every sensor route on a very large inventory
will be attempted before the total time budget expires.

The scan evidence remains a developer-diagnostic facility rather than a public sensor capability.
It is also available through the authorized `debug.export` section `sensor_probes` for collection
and analysis. That export pages concrete route identity, outcome, registration/timing information,
permission or rejection evidence, timestamp/accuracy, and a bounded prefix of raw event values.

---

# Hardware test tooling

Three repository tools support phone/Termux-driven validation.

## `tools/diesel-adb`

`diesel-adb` sends a correlated Diesel request through Gadgetbridge and reads the exact structured
watch response from the debug ADB/logcat mirror.

Typical use:

```bash
cd ~/dieselbridge
tools/diesel-adb '{"cmd":"debug.build.info"}'
```

or:

```bash
tools/diesel-adb   '{"cmd":"sensor.read","name":"accelerometer","args":{"timeoutMs":5000}}'
```

The helper generates a unique request ID and verifies that the decoded response matches that ID.

### Important debug-mirror boundary

The ADB response mirror proves:

```text
watch received request
→ command executed
→ watch generated exact Diesel response JSON
```

It does **not** by itself prove that Gadgetbridge received the watch response and emitted the final
Android `DIESEL_MESSAGE` broadcast.

The production BLE response transport remains active; the debug mirror is only an observation path.

## `tools/diesel-watch-sensor-test`

The hardware-test helper discovers the live inventory and can:

```text
--list
--select INDEX_OR_ROUTE
--all
```

`--all` probes every route from one inventory snapshot sequentially and writes a JSON evidence file.
This can take substantially longer than the 60-second in-watch scanner and is intended for
exhaustive empirical mapping.

Reports are normally written outside the repository under the user's home directory.

## `tools/diesel-ci-watch-install`

This Termux helper closes the normal development loop from a pushed commit to the exact APK
installed on the physical watch.

It:

- requires a clean Git worktree;
- verifies local `HEAD` equals the pushed branch head;
- waits for the GitHub Actions run belonging to that exact SHA;
- requires CI success;
- downloads the exact `dieselbridge-bloom-debug` artifact produced by that run;
- reports the APK SHA-256 when `sha256sum` is available;
- reuses an existing wireless-ADB connection or asks for the current watch endpoint;
- never hard-codes the Wear OS wireless-debugging port;
- installs with `adb install -r` so application data and signing lineage are preserved;
- retries automatically only when the ADB connection is lost;
- reports installed version, install/update timestamps, Git SHA, CI run and APK path.

Typical use after committing and pushing:

    cd ~/dieselbridge
    tools/diesel-ci-watch-install

Required phone-side commands are `git`, `gh`, `adb`, and `find`.

The GitHub CLI must already be authenticated.

---

# Power and battery tools

Developer power tooling is intentionally limited to controls Android actually exposes to an ordinary
application.

## Battery/electrical snapshot

The developer screen can inspect best-effort `BatteryManager`/battery-broadcast values such as:

```text
battery level
voltage
current
charge counter
energy counter
temperature
charging state
```

Some devices may not expose every property.

## UsageStats

When the user grants Android Usage Access, DieselBridge can query recent foreground-usage statistics
for diagnostic comparison.

This is **not** per-package battery attribution.

Real Android BatteryStats attribution requires privileged/system access and is therefore reported as
unavailable rather than guessed.

## App power policy

Current DieselBridge modes:

```text
ACTIVE
OPTIMIZED
SLEEPING
```

The policy controls DieselBridge itself.

`SLEEPING` stops the bridge service. Active/optimized modes start it.

The app does not claim to change:

- other apps;
- hidden system standby buckets;
- Mobvoi/vendor power modes;
- privileged Android battery policy.

---

# Developer UI and build provenance

The watch contains an internal Diesel Developer UI.

The developer UI observes the same runtime/platform objects owned by `DieselBridgeService` and can
dispatch through the shared local Diesel command dispatcher.

It exposes areas including:

- runtime/platform state;
- command catalogue and command details;
- build provenance;
- sensor inventory and tests;
- exact-route scan/probe evidence;
- power/battery information;
- remote developer-access controls;
- Health Services permission/provider refresh.

## Build provenance

`debug.build.info` is intended to identify the actual installed APK even when `versionName` was not
incremented between commits.

Useful fields include the build's:

- version name;
- version code;
- build type/debug flag;
- Git SHA / short SHA;
- build timestamp;
- CI run ID;
- package install/update timestamps where exposed.

---

# Testing and CI

GitHub Actions is the canonical project build/test path for the active branch.

CI performs:

1. checkout;
2. JDK 21 setup;
3. Android SDK setup;
4. Gradle setup;
5. persistent DieselBridge development signing setup;
6. JVM unit tests;
7. debug APK build;
8. APK signing-certificate verification;
9. debug artifact upload.

The debug artifact name is:

```text
dieselbridge-bloom-debug
```

The project has tests covering major pieces of the Diesel platform, including:

- capability registry behavior;
- provider priority and failover;
- battery routes;
- protocol decoding and validation;
- protocol engine/execution lane;
- response encoding;
- Gadgetbridge response transport;
- sensor route catalogue;
- bounded sensor sampler;
- public sensor reads;
- Health Services provider seam;
- developer export;
- developer sensor probes;
- bounded safe tests.

CI passing means the code compiles and unit tests pass. It is not the same as physical-watch proof.

## Release tags

Tagged releases have one authoritative publishing path:

    reviewed main commit
        |
        v
    v<version> Git tag
        |
        v
    .github/workflows/release.yml
        |
        +--> assembleRelease
        +--> release signing
        +--> GitHub release APK

The repository does not use a second manual script that uploads a separately built debug APK as a
GitHub release.

Development APK installation remains a separate workflow handled by CI and
`tools/diesel-ci-watch-install`.

---

# No-Google constraint

The core bridge intentionally does not require:

- Google Play Services;
- Wearable Data Layer;
- a Google phone companion;
- a DieselBridge-owned phone BLE implementation.

The phone side remains unmodified Gadgetbridge.

The project can still use ordinary AndroidX/Wear libraries. The constraint is specifically against
requiring Google's proprietary phone/watch data-layer stack as the transport architecture.

---

# Troubleshooting

| Symptom | Check |
|---|---|
| Gadgetbridge connected but notifications do not arrive | Grant Gadgetbridge Notification Access and verify screen-on forwarding policy. |
| Watch app restarted and Gadgetbridge appears stale | Disconnect/reconnect the Gadgetbridge device. |
| Watch says Bluetooth is off | Enable Bluetooth and verify `adb shell settings get global bluetooth_on`. |
| Wireless ADB connection refused | Re-read the current watch wireless-debugging port. |
| `more than one device/emulator` | Use `adb -s <serial> ...`. |
| `adb install -r` signature mismatch | Verify you are using the persistent Bloom development signing lineage; do not solve this by casually uninstalling. |
| `debug.sensor.probe` returns remote access disabled | Enable Remote Developer Access locally on the watch Developer UI. |
| `sensor.read` says unavailable | The logical target is known but no usable provider is currently selected. |
| Heart-rate Health Services does not activate | Grant the health permission and refresh Health Services availability. |
| Debug ADB helper sees a response but phone broadcast is unverified | The helper observes the watch-side debug response mirror, not the final phone-side broadcast receiver. |

---

# Evidence and validation status

The project uses three distinct evidence levels:

```text
IMPLEMENTED
CI VERIFIED
HARDWARE VERIFIED
```

They should not be treated as synonyms.

## Hardware-proven earlier in development

Real-watch testing on the TicWatch Pro 5 demonstrated:

- phone Termux → stock Gadgetbridge → BLE/NUS → watch Diesel request path;
- exact SensorManager route lookup;
- real accelerometer activation;
- bounded first sensor event;
- actual accelerometer X/Y/Z values returned in structured Diesel data;
- cleanup/disable visible in Android sensor/HAL logs;
- debug watch response mirror visible through ADB.

One confirmed accelerometer diagnostic route at that time was:

```text
android.sensor_manager:1:0:0
```

with a real event containing three floating-point axis values.

That route ID is diagnostic evidence, not a stable public API identifier.

The complete sanitized command transcript for that physical-watch run is preserved at:

    evidence/ticwatch-pro-5/2026-09-09-f876a9b-dev19-all-commands.log

The evidence records the real TicWatch Pro 5 run for:

- versionName: `1.0.0-dev.19`
- versionCode: `24`
- gitSha: `f876a9b0a884464bcdac11a8a61d1c900e7574e2`
- CI run: `34393457000`

It includes:

- successful Diesel requests through stock Gadgetbridge;
- 102 process-visible SensorManager routes;
- real TicWatch Pro 5 device metadata;
- exact route discovery;
- real accelerometer activation and first-event sampling;
- raw three-axis values;
- bounded sampling and response generation.

The temporary local wireless-ADB IP/port was removed before committing the evidence.

This is historical hardware evidence, not output from current HEAD.

## Current-head implemented and CI-verified

The newer current branch additionally contains:

- public `sensor.read`;
- `sensor.matrix`;
- `sensor.experiment`;
- automatic SensorManager provider selection;
- Health Services heart-rate provider;
- Health Services priority/fallback integration;
- asynchronous developer sensor scan;
- exhaustive Termux route-test helper;
- expanded developer sensor UI;
- power/battery developer tooling.

These should only be called current-head **hardware verified** after running them on the physical
watch build corresponding to the current Git SHA.

---

# Current limitations

Not currently implemented as finished platform features:

- DieselBridge phone companion;
- full end-to-end phone-side Diesel response receiver owned by this repo;
- Android Clock alarm synchronization;
- generalized health history/passive health platform;
- Health Services step provider in the current branch;
- VO2 max platform;
- Mobvoi/private sensor provider;
- stable public vendor/private sensor semantics;
- Espruino/Bangle runtime hosted by DieselBridge;
- bounded sensor event subscriptions / streaming API;
- plugin SDK/module packages;
- Wi-Fi Diesel transport;
- secondary ultra-low-power LCD reverse-engineered API for TicWatch Pro 5;
- privileged per-app battery attribution;
- hidden vendor power-management controls.

---

# Roadmap / project hand-off

This section consolidates the development direction that previously lived across discussion,
temporary hand-off notes, and multiple documentation files.

## Architectural invariants

Keep these rules unless there is strong evidence to change them:

1. **Stock Gadgetbridge remains the phone BLE owner.**
   Do not create a second central connection from a DieselBridge phone component.

2. **Transport does not know command semantics.**
   Sensor/alarm/display commands belong in modules/platform capabilities, not in NUS code.

3. **Public consumers use logical capabilities.**
   Exact `routeId`/provider selection stays diagnostic.

4. **Local UI uses the platform directly.**
   Do not introduce a local BLE/wire round trip.

5. **Bound the control plane.**
   Requests, responses, execution queues, scan duration, and diagnostic stores remain bounded.

6. **Do not guess vendor sensor meanings.**
   Preserve provisional identities until empirical evidence exists.

7. **Do not add permissions pre-emptively.**
   Add them when an implementation/evidence requires them.

8. **Separate implementation from hardware evidence.**
   CI success does not prove a vendor watch actually supplies a sensor.

## Upstream and mergeability

Future work should remain reviewable and attributable:

- prefer narrow coherent commits over unrelated mixed refactors;
- separate generic platform/protocol changes from TicWatch-specific work where practical;
- isolate Mobvoi/private functionality behind provider or display boundaries;
- keep vendor-specific behavior out of BLE/NUS transport;
- preserve unmodified Gadgetbridge as the phone BLE owner;
- preserve automatic logical capability routing;
- inspect the complete relevant diff before staging;
- do not rewrite already-pushed milestone history merely for cosmetic cleanup.

This keeps generic Diesel platform work reusable across Wear OS devices while deeper TicWatch
integration remains optional.

## Immediate hardware validation

The highest-value next tests are logical API tests on the current APK.

Start with:

```bash
tools/diesel-adb   '{"cmd":"sensor.read","name":"accelerometer","args":{"timeoutMs":5000}}'
```

Acceptance criteria:

```text
logical name only
→ CapabilityRegistry
→ provider selected internally
→ real sensor event
→ response contains providerId
→ no routeId required
```

Repeat for:

```text
gyroscope
magnetic_field
light
pressure
ambient_temperature
step_counter
heart_rate
```

Then grant the Health Services permission, refresh provider state, and verify whether:

```text
sensor.heart_rate
providerId = wear.health_services
```

is selected on the TicWatch Pro 5.

Use:

```bash
tools/diesel-watch-sensor-test --all
```

for the exhaustive concrete-route campaign.

## Companion / complete phone-side response path

A future optional companion should **not** own BLE.

Intended topology:

```text
Termux / app / automation
        ↓
small phone-side API
        ↓
stock Gadgetbridge intent
        ↓
Gadgetbridge BLE central
        ↓
watch Diesel engine
        ↓
BLE response
        ↓
Gadgetbridge DIESEL_MESSAGE broadcast
        ↓
companion correlates request ID
```

A first companion slice should stay minimal:

- separate package;
- no Bluetooth APIs/permissions;
- dynamic receiver only while a request is in flight;
- fixed broadcast action;
- request ID correlation;
- bounded timeout;
- one in-flight request initially;
- provider/API suitable for Termux or other local clients.

Do not extract a large shared protocol library before the first phone-side path has been proven.

## Alarm synchronization

Desired future work:

- observe real Android phone alarms/default clock state where Android permits it;
- expose alarm state through a logical Diesel alarm capability;
- synchronize phone-to-watch and eventually watch-to-phone;
- avoid notification-only imitation where a real alarm API/integration exists;
- keep vendor-specific clock behavior behind providers.

## Health expansion

Potential next Health Services work:

- evaluate passive background heart-rate/steps separately from bounded `sensor.read`;
- reuse the old `Health` branch only as design evidence;
- avoid forcing passive semantics into the spot-read API;
- add historical/streaming capability types when required;
- investigate SpO2 only when a valid provider and repeatable hardware evidence exist;
- investigate RR/RRI only when a valid provider and repeatable hardware evidence exist;
- add activity/fall capabilities only after their semantics are established;
- expose VO2-related data only when a provider offers a defensible value and contract;
- never call vendor temperature data skin temperature without evidence;
- never infer medical meaning from vendor PPG names or raw type numbers.

## Streaming and subscriptions

`sensor.read` is a bounded spot-read API. Long-lived sampling should eventually use explicit
events and subscriptions rather than repeatedly invoking `sensor.read`.

The intended flow is:

    provider callback
        |
        v
    typed sensor sample
        |
        v
    EventBus / sensor.sample
        |
        v
    bounded subscription queue
        |
        v
    local or remote consumer

A public streaming API must define subscription lifecycle, cancellation, bounded buffering,
backpressure, rate policy, disconnect behavior, provider failover, and authorization before it is
part of the stable protocol.

## Vendor/private sensors

The TicWatch exposes many process-visible Android sensors, including private/vendor types.

Provider investigation order remains:

1. ordinary Android `SensorManager`;
2. Wear OS Health Services where it supplies a better supported semantic API;
3. Mobvoi/private APIs only for a demonstrated gap the public layers cannot fill.

A vendor-looking sensor already exposed through SensorManager is not by itself a reason to reverse
engineer Mobvoi Binder or private services.

Health Services remains a provider behind `CapabilityRegistry`, not a separate public sensor API.

Strategy:

1. census via `sensor.list`;
2. probe exact route;
3. preserve raw evidence;
4. correlate movement/environment/health behavior;
5. identify semantics only after repeatable evidence;
6. then add a provider/capability mapping if useful.

Do not publish `android_type_<n>` as a stable semantic capability merely because the number repeats.

## Espruino / module architecture

A future scripting/plugin architecture can sit **above** the Diesel platform:

```text
script/plugin
    ↓
logical Diesel capability/action APIs
    ↓
CapabilityRegistry / ActionDispatcher
    ↓
provider
```

Scripts should not need to know BLE transport or raw Android route IDs.

## Native APK plugin SDK

A later plugin milestone can allow separately installed APK modules to extend DieselBridge without
putting every hardware-specific integration into the core APK.

The intended local boundary is Binder/AIDL or another explicit Android IPC contract.

Plugins may:

- provide logical capabilities;
- expose provider availability;
- optionally register commands through a controlled bridge;
- publish typed state and events;
- remain replaceable without modifying BLE/NUS transport.

Provider plugins and command plugins remain separate concepts even if one APK implements both.

Normal consumers still request logical capabilities rather than selecting a concrete plugin,
SensorManager route, Health Services implementation, or Mobvoi provider.

## Power expansion

Continue measuring before adding policy.

Useful future areas:

- service/sensor duty-cycle measurements;
- BLE reconnect cost;
- provider-specific power characteristics;
- watch-vendor power behavior if APIs are discovered.

Do not claim system-wide power management without privileged access.

## Secondary low-power display

TicWatch Pro models expose a separate ultra-low-power display path. Earlier generations have
community reverse-engineering work around vendor APIs.

For TicWatch Pro 5, treat this as a separate reverse-engineering track:

- inspect public/vendor packages/APIs;
- compare with known TicWatch Pro 3 work;
- identify Binder/service/native boundaries;
- avoid assuming segment capabilities from appearance alone;
- keep it separate from the generic sensor platform until a real API is found.

---

# Historical branches

## `main`

The fork's baseline/upstream-derived line.

## `develop`

Contains the early Bloom CI/signing identity work and is an ancestor of the active Diesel platform
branch.

## `feature/diesel-platform`

Current authoritative development branch.

## `Health`

An older diverged experiment.

It contains a separate early health abstraction with:

- passive Health Services heart rate;
- passive Health Services daily steps;
- SensorManager heart-rate fallback;
- SensorManager step counter converted to local-day steps;
- API-version factory selection.

The current platform did not merge that architecture directly. Instead it uses the newer
`CapabilityRegistry` provider model and currently implements Health Services as a bounded
heart-rate spot-measurement provider.

The old branch remains useful research/reference material, especially for a future passive health
layer.

---

# License

DieselBridge watch code is licensed under Apache-2.0.

The project communicates with Gadgetbridge through a public wire protocol and does not copy
Gadgetbridge source into this repository.

See:

- `LICENSE`
- `THIRD-PARTY-NOTICES.md`

for the repository's licensing information.
