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
capability registry, public logical sensor reads and bounded subscriptions, provider-neutral
continuous observation, SensorManager and Health Services observation providers, developer-only
exact sensor diagnostics, bounded sensor experiments and observation smoke campaigns, developer
power controls, build provenance, and Termux/ADB hardware-test tooling.

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
- public bounded `sensor.subscribe` / `sensor.unsubscribe` / `sensor.subscriptions`;
- provider-neutral shared continuous observation with per-consumer bounded queues and cadence;
- process-visible `sensor.list`;
- exact developer route probe;
- asynchronous bounded whole-watch route scanning;
- SensorManager spot-read and continuous-observation provider;
- Health Services spot heart-rate provider through `MeasureClient`;
- Health Services passive heart-rate observation through `PassiveMonitoringClient`;
- automatic provider priority/failover through `CapabilityRegistry`;
- developer observation smoke runner, watch UI and debug ADB harness;
- developer battery/electrical readings;
- 24-hour foreground-usage snapshot where Android grants usage access;
- DieselBridge app power modes.

---

# Architecture

DieselBridge is now a layered watch platform rather than a BLE controller containing feature-specific
logic. Keep three concerns separate:

```text
provider/data acquisition     SensorManager / Health Services / future vendor providers
application protocols         Diesel request/response/events and Bangle/Gadgetbridge schemas
physical transport            BLE/NUS today, other transports later
```

The current internal topology is:

```text
                              CONSUMERS

              Watch UI        Diesel protocol       future local APIs
                 \                 |                       /
                  \                |                      /
                   +----------- Diesel Platform --------+
                               |        |        |
                      CapabilityRegistry |   DieselEventBus
                               |         |    (process-local)
                               |         |
                   logical capability    |
                   provider selection    |
                               |
                   +-----------+-------------+
                   |                         |
             SensorManager             Health Services
              priority 10               priority 20
             spot + observe          spot + passive HR observe
                   |
                   v
          SensorObservationManager
          one runtime / logical sensor
          fastest requested acquisition cadence
          individual bounded consumer queues
                   |
                   +--> local consumers
                   |
                   +--> PublicSensorSubscriptionController
                                  |
                           Diesel event envelope
                                  |
                    GadgetbridgeDieselEventTransport
                                  |
                        bounded NUS line sender
                                  |
                         stock Gadgetbridge
```

## Architectural boundaries

Normal consumers request logical capabilities rather than providers or concrete Android routes.
Examples are:

```text
sensor.accelerometer
sensor.heart_rate
sensor.observe.accelerometer
sensor.observe.heart_rate
```

Exact route IDs such as `android.sensor_manager:1:0:0` remain diagnostic identities only.

The Diesel command path is:

```text
Gadgetbridge/NUS
    -> DieselRequest
    -> DieselProtocolExecutionLane
    -> DieselProtocolEngine
    -> DieselCommandRegistry
    -> command module / platform
    -> DieselCommandResult
    -> DieselResponse
    -> DieselResponseTransport
    -> bounded NUS line sender
```

`DieselProtocolEngine` owns correlation and response-envelope construction. Command success is kept
separate from transport delivery: a command may succeed even when BLE delivery later fails.

Local watch UI/modules call the shared platform/command runtime directly. They should not round-trip
through BLE merely to invoke code in the same APK.

## Continuous observation architecture

Persistent sampling is not implemented as a loop around `sensor.read`.

```text
consumer subscription
    -> SensorObservationManager
    -> one shared runtime per logical sensor
    -> CapabilityRegistry.observeActive(sensor.observe.<logical>)
    -> selected SensorObservationCapability
    -> provider callback stream
```

Multiple consumers of the same logical sensor share one provider acquisition. The fastest active
consumer determines acquisition cadence; each consumer still owns its own delivery cadence, bounded
queue, sequence counter, and queue-loss accounting.

Provider changes or acquisition-period changes restart the provider session through the observation
runtime without changing the public logical subscription.

## Execution contexts

The service deliberately separates execution domains:

```text
Android/Main
    service lifecycle and ordinary platform/UI state

DieselSensorObservation HandlerThread
    raw SensorManager callbacks

Dispatchers.Default observation scope
    provider collection, cadence handling, fan-out, observation state

Dispatchers.Default protocol scope
    serialized Diesel command execution
```

SensorManager callbacks therefore do not run observation fan-out on Main.

## Shutdown ownership

Final service destruction closes remote subscriptions, cancels the observation manager immediately,
then asynchronously joins provider cleanup before retiring the SensorManager callback HandlerThread.
The service intentionally does not use an unbounded `runBlocking` wait on Android's main thread.

## Process-local EventBus

`DieselPlatform` already owns a process-local `DieselEventBus` intended for future native UI,
automation, scripting and plugins. The generic observation-to-EventBus bridge is not yet complete;
current remote sensor events are produced by `PublicSensorSubscriptionController` and sent through
`GadgetbridgeDieselEventTransport`.

The EventBus is a distribution mechanism, not a hardware-resource owner.

## Shared physical line output

Several watch-to-phone features ultimately use `gattServer?.sendLine(...)`. Diesel response/event
transports intentionally remain protocol-specific. When native Gadgetbridge activity/GPS/workout
adapters need the same output primitive, prefer a small line-level seam such as
`BangleLineTransport` rather than turning `DieselResponseTransport` into a universal Bangle API.

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

Bounded reads and experiments:

```text
sensor.list
sensor.read
sensor.matrix
sensor.experiment
```

Long-lived logical observation:

```text
sensor.subscribe
sensor.unsubscribe
sensor.subscriptions
```

The public APIs accept logical sensor names, not exact Android routes or provider IDs. Unsolicited
subscription data uses Diesel `kind:"event"` envelopes rather than fake command responses.

`sensor.experiment` remains deliberately a normal bounded read-only command rather than a `debug.*`
command.

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

SensorManager now implements both bounded spot reads and continuous observation where Android exposes
a suitable route. Spot reads and observations intentionally use different route policies.

### Spot-read route policy

```text
1. matching logical sensor
2. non-wakeup route
3. lower reported power
4. deterministic route ID
```

### Continuous-observation route policy

```text
1. matching logical sensor
2. reject one-shot reporting mode
3. smallest requested-cadence deficit
4. prefer wake-up route when otherwise comparable
5. lower reported power
6. deterministic route ID
```

If no candidate can satisfy the requested cadence, the route with the smallest cadence deficit wins
before power is considered.

### Sampling period

The observation request is converted to SensorManager's finite microsecond period and clamped against
the selected route's `minDelayUs`:

```text
registrationPeriodUs = max(requestedPeriodUs, minDelayUs)
```

`providerConfiguredPeriodMs` reports this configured registration request. It is not an observed rate
guarantee. `providerEffectivePeriodMs` may remain unknown because SensorManager delivery timing is not
assumed to equal the requested period.

The current sampling policy does not yet use `maxDelayUs` or FIFO batching to build a power-oriented
batching strategy.

### Callback and ingress policy

Continuous SensorManager callbacks run on the service-owned `DieselSensorObservation` HandlerThread.
Observation processing then runs on the dedicated `Dispatchers.Default` observation scope.

The provider owns one explicit bounded ingress queue:

```text
capacity = 64 samples
overflow = drop oldest
```

A conflated wake signal avoids an unbounded callback-notification queue. There is deliberately no
second hidden Flow sample backlog. Drop-oldest is intentional for realtime streams so newer sensor
data is preferred over stale backlog.

### Screen-off limitation

Wake-up routes are preferred for continuous observation, but non-wakeup routes remain valid fallback
when hardware exposes no wake-up equivalent. DieselBridge does not currently acquire a partial
wakelock for every active observation. A foreground service alone does not guarantee delivery from a
non-wakeup sensor while the application processor is suspended, so screen-off behavior remains a
physical-watch validation item.

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

## Bounded logical sensor subscriptions

Spot-read and observation capabilities are separate:

```text
sensor.<logical-name>
sensor.observe.<logical-name>
```

This is deliberate: the best provider for a bounded spot measurement does not have to be the best
provider for a long-lived stream.

### Shared observation manager

`SensorObservationManager` shares one provider session per logical sensor. If consumers request 1000,
500 and 250 ms, acquisition runs at the fastest active request (250 ms), while each subscriber keeps
its own delivery cadence and bounded queue. When the fastest subscriber leaves, acquisition may be
reconfigured to the next required cadence.

### Internal observation limits

```text
maximum clients                    16
maximum subscriptions              32
maximum subscriptions/client        8
maximum subscriptions/sensor        8
maximum consumer buffer             64
minimum requested period            20 ms
maximum requested period       3600000 ms
```

Internal consumers may use latest-value or finite bounded queues.

### Public Diesel subscription limits

Remote Diesel clients intentionally receive stricter limits:

```text
maximum active remote subscriptions    4
periodMs                               250..60000
leaseMs                                5000..300000
buffer policy                          latest only
```

This is a safety/policy boundary: the remote control plane must not become an unrestricted high-rate
BLE telemetry interface merely because the internal runtime supports faster local consumers.

Subscriptions close on explicit unsubscribe, lease expiry, BLE/session shutdown, service destruction,
or client destruction.

### Public event topics

```text
sensor.sample
sensor.subscription.state
```

These use normal Diesel event envelopes:

```json
{"v":1,"kind":"event","topic":"sensor.sample","data":{}}
```

### Drop accounting

Losses are separated into provider ingress, per-subscription queue loss, and transport loss.

Explicit fields are:

```text
providerDroppedTotal
subscriptionDroppedTotal
transportDroppedTotal
```

Diesel v1 already exposed `sourceDroppedTotal` and `droppedTotal` before provider-ingress accounting
was added. Their meanings are therefore frozen for compatibility:

```text
sourceDroppedTotal       legacy alias for subscriptionDroppedTotal
providerDroppedTotal     provider-ingress loss
subscriptionDroppedTotal subscription queue loss
transportDroppedTotal    event transport loss
droppedTotal             subscription + transport
allDroppedTotal          provider + subscription + transport
```

Do not silently redefine `sourceDroppedTotal` in Diesel v1. A future protocol version may clean up
these names explicitly.

Provider ingress loss has a dedicated observation update, and subscription queue loss also updates
state independently, so loss accounting normally does not require another successful sample.
There remains a narrow terminal diagnostic race where a final provider-loss update can be cancelled
at exactly the same time as provider Flow cancellation; this is intentionally accepted for now rather
than adding a second metrics subsystem before hardware evidence shows one is needed.

The shared observation runtime and public subscription path are implemented and CI verified, but the
latest observation-hardening HEAD is not yet declared hardware verified until the M5.0b physical-watch
campaign succeeds.

---

# Health Services

The current branch contains an optional Wear OS Health Services provider with separate spot-read and
continuous-observation paths.

Provider ID:

```text
wear.health_services
```

Implemented logical capabilities:

```text
sensor.heart_rate
sensor.observe.heart_rate
```

The two paths deliberately use different Health Services APIs:

```text
sensor.heart_rate
    -> Health Services MeasureClient
    -> bounded on-demand spot measurement

sensor.observe.heart_rate
    -> Health Services PassiveMonitoringClient
    -> PassiveListenerCallback
    -> bounded provider ingress
    -> provider-neutral SensorObservationUpdate
```

Provider priorities are currently:

```text
Health Services      20
SensorManager        10
```

Health Services starts unavailable and becomes selectable only when the required permission is
granted and the watch reports support for the requested measurement/observation type. The developer
UI can request heart-rate permission and explicitly refresh Health Services availability.

For passive heart-rate observation, Health Services owns the physical acquisition cadence.
`preferredSamplePeriodMs` is therefore a consumer delivery preference, not a promise that Health
Services was configured to that period. The provider reports configured/effective acquisition
periods as unknown rather than inventing values.

The Android passive adapter has an explicit 64-sample drop-oldest ingress queue. Callback threads do
not block; provider-side loss is propagated through the common source-drop accounting. Registration
or the first data callback maps to `Started`; heart-rate data maps to ordinary typed
`SensorReading` values with provider ID `wear.health_services`.

Runtime permission loss or passive-registration failure marks the Health Services observation binding
`UNAVAILABLE`. Because `SensorObservationManager` follows `CapabilityRegistry.observeActive()`,
an already-owned logical subscription can move to the lower-priority SensorManager observation
provider when a usable fallback exists.

There is currently **no autonomous periodic re-probe** that promotes Health Services again after such
a runtime failure. Availability is refreshed during service setup and through the explicit
developer/runtime refresh path. Automatic recovery/re-promotion is tracked separately as M5.0c2.

Passive callback registration is app-global in Health Services, so cancellation performs bounded
non-cancellable cleanup through `clearPassiveListenerCallbackAsync()` before the provider session is
retired.

## Steps and passive health

`step_counter` currently remains part of the standard logical sensor surface, with SensorManager as
the implemented normal source.

Passive Health Services heart rate is implemented. Passive Health Services steps, daily/history
semantics and a broader passive-health state model are **not** implemented yet. The older historical
`Health` branch remains useful design evidence for those later capabilities, but its architecture
was not merged directly into the current provider-neutral runtime.

Do not document raw Android `TYPE_STEP_COUNTER` as daily steps: it remains cumulative since boot.

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

Four repository tools support phone/Termux-driven validation.

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

## `tools/diesel-observation-smoke`

This debug-build helper drives the service-owned provider-neutral observation smoke runner over ADB
and saves correlated JSON evidence. It talks to a debug-only broadcast receiver; it does not depend
on the BLE request/response path.

Supported bounded profiles are:

```text
basic
cadence
screen_off
step_counter
health_services_hr
sharing
lifecycle
```

The profiles validate distinct runtime contracts:

- `basic`: real accelerometer samples, advancing sequence/timestamps, ACTIVE -> CLOSED;
- `cadence`: an internal 20 ms request and truthful SensorManager min-delay clamping;
- `screen_off`: a 30-second capture with no ADB polling during the measurement window;
- `step_counter`: real on-change cumulative step-counter delivery while walking;
- `health_services_hr`: requires `wear.health_services`, reaches ACTIVE, waits for a passive HR
  sample and treats SensorManager fallback as failure;
- `sharing`: proves one shared acquisition follows 1000 -> 250 -> 1000 ms consumer demand;
- `lifecycle`: repeats five subscribe/sample/close cycles.

Typical exact-build use:

```bash
tools/diesel-observation-smoke \
  --expect-sha <full-installed-build-sha> \
  run health_services_hr
```

The helper verifies the installed APK SHA before mutating smoke-runner state when `--expect-sha` is
provided. Reports are written under `~/dieselbridge-observation-smoke/` by default unless
`--output` is supplied.

`screen_off` intentionally reports `completed` rather than claiming pass/fail for power behavior;
the saved evidence must be interpreted using sample continuity, observed gaps and route wake-up
metadata. `health_services_hr` only passes when a real sample from `wear.health_services` is
observed; reaching ACTIVE without a sample is recorded as incomplete proof.

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
- public bounded sensor subscription controller and commands;
- Diesel event encoding and Gadgetbridge event transport;
- shared sensor observation runtime;
- SensorManager observation route selection;
- observation smoke-runner profiles, cadence/sharing/lifecycle semantics and terminal-state logic;
- Health Services spot-read provider seam;
- Health Services passive-observation capability mapping;
- Health Services passive Android source, bounded ingress, registration/permission failure mapping
  and cleanup lifecycle;
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
- bounded `sensor.subscribe` / `sensor.unsubscribe` / `sensor.subscriptions`;
- Diesel unsolicited event envelopes and bounded Gadgetbridge event transport;
- shared logical sensor observation runtime with SensorManager observation capabilities;
- automatic SensorManager provider selection;
- Health Services spot heart-rate provider;
- Health Services passive `sensor.observe.heart_rate` provider;
- Health Services priority/fallback integration on runtime permission/registration failure;
- service-owned observation smoke runner with developer UI and debug ADB control;
- observation smoke profiles for basic sampling, cadence, screen-off, step counter, provider sharing,
  lifecycle and Health Services HR;
- asynchronous developer sensor scan;
- exhaustive Termux route-test helper;
- expanded developer sensor UI;
- power/battery developer tooling.

These should only be called current-head **hardware verified** after running them on the physical
watch build corresponding to the current Git SHA.

## Health Services passive HR hardware proof

Four committed TicWatch Pro 5 runs physically validated the new passive heart-rate path on the exact
runtime build:

```text
runtime SHA   861bc4a4d8d04575af250a8b01f520b3b9457378
versionName   1.0.0-dev.21
versionCode   26
CI run        35272142702
provider      wear.health_services
profile       health_services_hr
```

All four runs reached:

```text
waiting_for_provider -> starting -> active -> closed
```

and each delivered one real Health Services heart-rate sample. The recorded values were 75, 76, 85
and 86 bpm. Provider and subscription drop counters were zero in all four runs.

The raw reports are committed under:

```text
evidence/ticwatch-pro-5/2026-09-17-861bc4a-health-services-hr-01.json
evidence/ticwatch-pro-5/2026-09-17-861bc4a-health-services-hr-02.json
evidence/ticwatch-pro-5/2026-09-17-861bc4a-health-services-hr-03.json
evidence/ticwatch-pro-5/2026-09-17-861bc4a-health-services-hr-04.json
```

Repository commit `77703921f704975f5a0b8b87dbd31942b2424a2b` adds those evidence files. It
must not be described as the SHA of the physically tested APK: the installed/tested runtime was
`861bc4a4d8d04575af250a8b01f520b3b9457378`.

---

## Current observation baseline

This documentation synchronization is based on `feature/diesel-platform` repository state
`77703921f704975f5a0b8b87dbd31942b2424a2b`, whose parent runtime commit
`861bc4a4d8d04575af250a8b01f520b3b9457378` is the exact build used for the committed Health
Services HR hardware campaign.

The distinction is intentional:

```text
861bc4a...   application/runtime code physically tested on TicWatch Pro 5
7770392...   repository commit that records the resulting evidence files
```

The September 14 logical-sensor campaign remains valid hardware evidence for SensorManager spot reads
for accelerometer, gyroscope, magnetic field, light, pressure, ambient temperature and step counter.
Heart-rate attempts in that earlier SensorManager campaign timed out.

M5.0b1 SensorManager continuous observation is implemented and CI verified, but its dedicated
continuous-observation hardware closure campaign is still pending. The smoke runner and
`tools/diesel-observation-smoke` already provide the required campaign tooling.

The repository evidence files remain the source of truth for physical runs.

# Current limitations

Not every limitation below is a bug. Some are deliberate architecture, safety, compatibility or
truthfulness constraints.

## Intentional architectural constraints

- Stock Gadgetbridge remains the phone BLE central; DieselBridge must not create a second BLE owner.
- Public consumers use logical capabilities, not exact providers/routes.
- Exact SensorManager route IDs remain diagnostic-only.
- Local watch consumers do not round-trip through BLE to call local code.
- BLE/NUS transport does not own sensor/alarm/display semantics.
- `DieselResponseTransport` remains response-specific rather than a generic Gadgetbridge sender.
- The process-local EventBus distributes events but does not own hardware registrations.
- Vendor/private sensor meanings remain provisional until repeatable evidence exists.
- The Diesel control plane remains bounded.
- The architecture does not require Google Wearable Data Layer.

## Intentional compatibility constraints

Diesel v1 preserves:

```text
sourceDroppedTotal = subscription queue loss
droppedTotal       = subscription queue loss + transport loss
```

Provider-ingress accounting was added through new fields instead of redefining v1.

`targetSdk=28` with `compileSdk=36` is also intentional for the current Android 9/legacy Wear
compatibility strategy.

## Temporarily accepted implementation limits

- Remote subscriptions are stricter than internal observation limits: four latest-value subscriptions,
  minimum 250 ms cadence and finite leases.
- Continuous route selection currently applies one background-oriented wake-up preference rather than
  accepting a per-consumer foreground/background requirement.
- Non-wakeup observation routes are valid fallback but cannot guarantee screen-off delivery.
- No observation-wide partial wakelock is currently acquired; adding one must be evidence-driven due
  to battery cost.
- SensorManager cadence is configured, not guaranteed; `providerEffectivePeriodMs` may remain unknown.
- No FIFO/max-delay batching policy exists yet.
- SensorManager provider ingress is fixed at 64 samples with drop-oldest freshness behavior.
- Final provider-loss telemetry has a narrow cancellation race accepted as a diagnostic edge.
- A Health Services passive registration/permission failure can demote the provider and allow
  SensorManager fallback, but there is no autonomous periodic re-probe/re-promotion yet.
- Android `TYPE_STEP_COUNTER` is still raw cumulative-since-boot data, not daily/history semantics.

## Not yet implemented

```text
automatic Health Services recovery/re-promotion after runtime failure
generic observation -> DieselEventBus bridge
Gadgetbridge-native t:"act" HR/step adapter
Gadgetbridge step delta/history semantics
passive health/history platform
sleep/activity classification
VO2 max platform
validated SpO2/RR/RRI providers
Mobvoi/private capability provider
stable public vendor/private sensor semantics
Android Clock alarm synchronization
Espruino/Bangle runtime hosted by DieselBridge
native APK provider/plugin SDK
StateStore
ActionDispatcher
ModuleManager
Wi-Fi Diesel transport
TicWatch Pro 5 secondary low-power display API
privileged per-app battery attribution
hidden vendor power-management controls
full phone-side end-to-end Diesel response consumer owned by this repo
```

A future phone companion may consume Gadgetbridge broadcasts and expose a convenient local API, but
it must not become another BLE implementation.

---

# Roadmap / project hand-off

The provider-neutral observation foundation is implemented. The remaining work is now split into
separately demonstrable implementation, hardware-closure and consumer-integration steps so that a
milestone cannot be marked complete by code alone when physical proof is still missing.

## Current milestone status

| Milestone | Goal | Current status |
|---|---|---|
| M5.0a | provider-neutral observation contracts + shared manager | **Implemented + CI verified** |
| M5.0b1 | SensorManager continuous observation implementation | **Implemented + CI verified** |
| M5.0b2 | SensorManager physical-watch closure campaign | **Pending hardware campaign; tooling implemented** |
| M5.0c1 | Health Services passive continuous HR | **Implemented + CI + TicWatch Pro 5 hardware verified** |
| M5.0c2 | automatic Health Services recovery/re-promotion after runtime failure | **Pending** |
| M5.0d1 | typed process-local sensor observation events | **Next** |
| M5.0d2 | externally owned subscription -> `DieselEventBus` bridge | **Next** |
| M5.0d3 | bounded real-watch EventBus proof | **Next** |
| M5.1a | Gadgetbridge `t:"act"` request/session ownership | **Planned** |
| M5.1b | native activity HR output + shared Bangle line transport | **Planned** |
| M5.1c | step-counter delta/session semantics | **Planned** |
| M5.1d | stock-Gadgetbridge activity hardware proof | **Planned** |
| M5.2a | `StateStore` | **Planned** |
| M5.2b | `ActionDispatcher` | **Planned** |
| M5.2c | `ModuleManager` / module lifecycle | **Planned** |
| M6 | real Android Clock alarm synchronization | **Planned** |
| M7 | scripting / local module API | **Planned** |
| M8 | APK provider/plugin IPC | **Planned** |
| M9 | health/history expansion | **Planned** |
| M10 | vendor/TicWatch-specific providers + ULP display research | **Planned** |

M5.0c1 is complete because the passive Health Services path is implemented, CI-tested and backed by
four physical TicWatch Pro 5 runs. M5.0c2 is deliberately separate: runtime demotion/fallback exists,
but automatic future re-probing and promotion does not.

Immediate sequence:

```text
M5.0b2 SensorManager physical-watch closure
    -> M5.0d1 typed observation events
    -> M5.0d2 explicit subscription -> EventBus bridge
    -> M5.0d3 real-watch EventBus smoke proof
    -> M5.1 Gadgetbridge t:"act"
```

M5.0c2 remains open but does not block M5.0d/M5.1 unless physical testing shows transient Health
Services loss is common enough to require recovery first.

Do not implement Gadgetbridge activity acquisition as another sensor provider or another BLE stack.
It should consume logical observation capabilities.

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

## Immediate hardware validation — M5.0b closure

The open validation target is continuous observation, not another spot-read-only campaign.
Spot reads have already been physically demonstrated on the TicWatch Pro 5.

Use the exact current/newer CI APK and validate:

1. `sensor.subscribe` for accelerometer reaches ACTIVE and produces repeated advancing samples.
2. An impossible internal cadence (for example 20 ms against a route previously observed around
   `minDelayUs=200000`) reports requested/acquisition/configured/effective periods truthfully rather
   than pretending hardware delivers 20 ms.
3. Sensor timestamps and sequence advance and values are not a repeated stale cache.
4. Screen-off behavior is measured and correlated with wake-up vs non-wakeup route type.
5. `providerDroppedTotal`, `subscriptionDroppedTotal`, `transportDroppedTotal` and `allDroppedTotal`
   are inspected independently while the Diesel v1 aliases keep their documented meanings.
6. Explicit unsubscribe reaches CLOSED and releases the hardware listener.
7. Lease expiry closes the subscription automatically.
8. BLE disconnect/reconnect does not leave remote subscriptions half-open.
9. Service stop/restart unregisters the old listener, retires the callback thread and does not create
   duplicate streams.
10. Subscribe/unsubscribe is repeated several times to catch listener/thread/runtime leaks.
11. At least one second logical sensor such as `step_counter` is validated so closure is not
    accelerometer-specific.

M5.0b is complete when implementation and CI remain green and the physical campaign proves real
subscription delivery, cadence reporting, unsubscribe/lease behavior, service cleanup and documents
screen-off behavior. A hardware limitation such as non-wakeup delivery stopping during AP suspend is
a valid result; completion does not require inventing a wakelock/vendor API just to hide it.

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

- extend passive Health Services beyond the current heart-rate observation where supported;
- evaluate passive steps and define daily/history semantics separately from raw
  `TYPE_STEP_COUNTER`;
- add historical/streaming capability types when a consumer actually requires them;
- investigate SpO2 only when a valid provider and repeatable hardware evidence exist;
- investigate RR/RRI only when a valid provider and repeatable hardware evidence exist;
- add activity/fall capabilities only after their semantics are established;
- expose VO2-related data only when a provider offers a defensible value and contract;
- never call vendor temperature data skin temperature without evidence;
- never infer medical meaning from vendor PPG names or raw type numbers.

## Observation roadmap after M5.0b

Long-lived observation is now a working provider-neutral runtime with both SensorManager and Health
Services providers. The next work adds a typed local distribution layer and then consumes it from the
Gadgetbridge activity protocol.

### M5.0c1 — Health Services passive continuous HR — complete

`sensor.observe.heart_rate` is registered through `wear.health_services` at priority 20 with
`android.sensor_manager` priority 10 as fallback. The implementation uses
`PassiveMonitoringClient`/`PassiveListenerCallback`, maps provider lifecycle into the common
observation contract, has bounded source ingress/loss accounting and has physical TicWatch Pro 5
proof at runtime SHA `861bc4a4...`.

### M5.0c2 — automatic Health Services recovery/re-promotion — pending

Runtime permission loss or passive-registration failure marks the Health Services observation binding
unavailable, which lets `CapabilityRegistry` select a SensorManager fallback. What is still missing
is autonomous future re-probing that can make Health Services available again after a transient
runtime failure.

The existing explicit availability refresh remains valid; M5.0c2 should add recovery without turning
a failed provider into an uncontrolled polling loop.

### M5.0d1 — typed process-local observation events

Add typed platform events, approximately:

```text
SensorObservationStateEvent(
    state: SensorSubscriptionState,
    timestampMs: Long,
)

SensorObservationSampleEvent(
    subscriptionId: Long,
    sample: SensorSubscriptionSample,
    timestampMs: Long,
)
```

They implement the existing `platform.event.DieselEvent`. Keep the typed
`SensorSubscriptionSample`/`SensorReading` structure rather than duplicating values into a generic
map.

`SensorReading.timestampNanos` remains the sensor/provider timestamp. `DieselEvent.timestampMs`
is the wall-clock publication time; the two clocks must not be conflated.

### M5.0d2 — explicit subscription -> EventBus bridge

Do **not** refactor `PublicSensorSubscriptionController` into the process-local EventBus. The remote
path owns leases and BLE-transport drop accounting that local consumers should not inherit.

The EventBus bridge should consume an already caller-owned `SensorSubscription`:

```text
local module
    |
    | owns
    v
SensorObservationClient
    |
    v
SensorSubscription
    |
    +--------------------+
    |                    |
    v                    v
SensorObservation     external owner
EventBridge
    |
    v
DieselPlatform.events
    |
    +--> UI
    +--> automation
    +--> scripting
    +--> future plugins
```

The EventBus must remain a distribution layer, not a hardware-resource owner. The bridge should use
suspending `events.emit(event)` rather than introduce a new unaccounted `tryEmit()` loss layer.
Slow local consumers can then backpressure the already bounded subscription queue, whose loss is
measured by the observation system.

`SensorSubscription.samples` is intentionally single-consumer. Attaching a subscription to the
EventBus makes the bridge that subscription's sample consumer. Another local direct consumer should
open another logical subscription; `SensorObservationManager` will still share the underlying
provider acquisition.

Closing a bridge registration cancels only forwarding jobs. It must not close the
`SensorSubscription`; the caller owns that lifecycle.

### M5.0d3 — bounded real-watch EventBus proof

Extend the existing smoke framework with an `event_bus` profile:

```text
open accelerometer logical subscription
    -> attach to DieselPlatform.events
    -> observe typed state event
    -> reach ACTIVE
    -> observe typed sample event
    -> verify advancing real sensor timestamp/value
    -> detach EventBus forwarding
    -> close subscription
    -> verify CLOSED
```

This closes M5.0d with physical proof of the new layer rather than only re-testing the observation
manager below it.

### M5.1 — Gadgetbridge activity adapter

M5.1 should remain an application-protocol adapter above the observation platform:

```text
Gadgetbridge
{"t":"act","hrm":true,"stp":true,"int":10}
                ↓
Gb activity adapter
                ↓
SensorObservationClient "gadgetbridge-activity"
                ↓
logical heart_rate / step_counter subscriptions
                ↓
provider selection remains automatic
                ↓
typed observation events
                ↓
activity codec
                ↓
{"t":"act", ...}
                ↓
existing BLE/NUS physical connection
```

M5.1a owns request/session lifetime. M5.1b adds HR output and a small shared physical line seam such
as `BangleLineTransport.sendLine()`; Diesel response/event transports remain Diesel-specific.
M5.1c defines step-counter session deltas from an explicit baseline instead of publishing Android's
cumulative-since-boot value as today's steps. M5.1d closes the milestone with stock-Gadgetbridge
hardware proof.

Android accelerometer values remain m/s^2 internally; any conversion to Bangle/Gadgetbridge `g`
units belongs in the protocol codec layer.

### M5.2 — Diesel runtime core

After M5.1, add the general runtime abstractions only where real consumers now require them:

```text
M5.2a StateStore
M5.2b ActionDispatcher
M5.2c ModuleManager / lifecycle
```

`DieselEventBus` is intentionally ephemeral (`replay=0`); current state belongs in a future
`StateStore`, not in EventBus replay. Cross-module actions should converge on
`ActionDispatcher` once alarm/activity/plugin work creates enough pressure, and service construction
should move toward `ModuleManager` only when those modules exist.

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

The current platform did not merge that architecture directly. It uses the newer
`CapabilityRegistry` and provider-neutral observation runtime. The active branch now independently
implements both bounded Health Services spot heart-rate reads and passive Health Services heart-rate
observation; passive steps/daily-history behavior from the old branch is still only reference
material.

The old branch remains useful research/reference material for broader passive health/history work.

---

# License

DieselBridge watch code is licensed under Apache-2.0.

The project communicates with Gadgetbridge through a public wire protocol and does not copy
Gadgetbridge source into this repository.

See:

- `LICENSE`
- `THIRD-PARTY-NOTICES.md`

for the repository's licensing information.
