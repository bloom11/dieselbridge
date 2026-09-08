# BLE protocol spec

The watch is the **peripheral / GATT server**. The phone (Gadgetbridge, BLE central) writes to RX and
subscribes to TX. The wire format is Gadgetbridge's **Bangle.js JSON-over-Nordic-UART** dialect, so an
**unmodified Gadgetbridge** speaks it. Constants live in `watch/.../ble/BleUuids.kt` — **keep this doc
and that file in sync.**

## Transport: Nordic UART Service (NUS)

| Attribute | UUID | Properties | Direction |
|-----------|------|------------|-----------|
| **NUS service** | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | primary service | — |
| **RX** | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | `WRITE` / `WRITE_NO_RESPONSE` | phone → watch |
| **TX** | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | `NOTIFY` | watch → phone |
| **CCCD** | `00002902-0000-1000-8000-00805f9b34fb` | descriptor on TX | phone subscribes |

The phone enables TX notifications by writing `ENABLE_NOTIFICATION_VALUE` to the CCCD.

**Advertising:** the watch advertises the NUS service UUID (primary packet) and a device name
matching Gadgetbridge's Bangle.js coordinator regex **`Bangle\.js.*`** — we use
**`Bangle.js PixelBridge`**, carried in the scan-response packet (a 128-bit UUID + a 21-char name
won't both fit in one 31-byte advertisement).

## Framing

- **phone → watch:** bytes are `0x10` + `"GB("` + `<JSON>` + `")"` + `"\n"`. The leading **`0x10`**
  is Espruino's DLE/echo-off control byte — **strip it**. The shorthand `GB({...})` omits it.
- **watch → phone:** raw JSON terminated with **`\r\n`** (CRLF), one object per line. ⚠️ Gadgetbridge's
  line splitter does `substring(0, p-1)` on the `\n` index (it expects a trailing `\r`), so a bare
  `\n` would eat your closing `}` → "Malformed JSON". Gadgetbridge parses any line beginning with `{`.
- NUS is an **opaque byte stream** → **reassemble by the `\n` delimiter, never by packet boundary.**
  The GATT server buffers inbound RX bytes and only parses a message when it sees `0x0A`. Our
  `NusGattServer` does exactly this.

## MTU & chunking

- Default ATT MTU is **23 bytes** (20 usable). The central negotiates via `requestMtu` (max 517) and
  reads `onMtuChanged`; Gadgetbridge's Bangle.js driver requests **MTU 131** when `allowHighMTU` is
  on, otherwise **chunks at 20 bytes**.
- **Never assume a notification fits one packet.** On the watch, buffer + split on `\n`. On outbound,
  emit each JSON object followed by `\n` and let the stack/chunking carry it.
- Use **NOTIFY** (unacknowledged) for the notification firehose; use write-with-response / indicate
  only where delivery must not drop.

## Message set (JSON, `t` = type)

### Inbound (phone → watch) — ✅ = implemented
- ✅ `{"t":"notify","id":…,"src":…,"title":…,"subject":…,"body":…,"sender":…,"reply":<bool>?}` — show/create.
  `reply:true` (Gadgetbridge 0.80+) means the source notification has a `RemoteInput`; the watch shows
  the **Reply** action only when it's present. Cards are always dismissible.
- ✅ `{"t":"notify-","id":…}` — dismiss on the watch
- ✅ `{"t":"find","n":<boolean>}` — start (`true`) / stop (`false`) the full-screen find-my-watch
  alert. Gadgetbridge's *Find Device* sends this; `n` is a JSON **boolean**.
- ✅ `{"t":"vibrate","n":<int>}` — single buzz (handled defensively; Espruino sends it, Gadgetbridge
  does not).
- ✅ `{"t":"call","cmd":"incoming|outgoing|accept|start|end|reject|ignore","name":…,"number":…}` —
  full-screen call screen (ring on `incoming`, In-call on `accept`/`start`, clear on `end`/`reject`/`ignore`).
- ✅ `{"t":"musicinfo","artist":…,"album":…,"track":…,"dur":<ms>,"c":<count>,"n":<nr>}` — now-playing metadata.
- ✅ `{"t":"musicstate","state":"play|pause|stop|","position":…,"shuffle":…,"repeat":…}` — transport (empty/`stop` clears).
- ✅ `{"t":"canned_responses_sync","d":[{"text":…,"disp":…?}]}` — synced quick-reply choices.
- ✅ `{"t":"diesel","cmd":"diagnostics"}` — **DieselBridge extension**, not part of the
  standard Bangle.js protocol. Posts one developer notification on the watch; tapping it opens the
  internal developer console.
- ✅ `{"t":"diesel","cmd":"commands"}` — discovers the commands registered by the developer command
  dispatcher. The catalog is generated from the same registrations used for execution, so discovery
  cannot silently drift away from the actual allow-list.
- ✅ `{"t":"diesel","cmd":"test","name":"vibration"}` — runs the same bounded safe-test runner
  exposed by the internal TOOLS page. The current vibration target is fixed at 250 ms and shares
  the runner's rate limit with local execution.
- Diesel command families may use an optional `name` target. `cmd` selects an explicitly registered
  command family and `name` selects a target inside that family's own allow-list. Missing or unknown
  test targets perform no action. Unknown commands or targets never fall through to shell,
  reflection, arbitrary Intent or arbitrary method execution.
- Diesel requests are versioned generic protocol requests. The legacy form remains valid:
  `{"t":"diesel","cmd":"test","name":"vibration"}`.
- The full v1 request envelope may contain `v`, `kind`, `id`, `cmd`, `name`, and generic `args`:
  `{"t":"diesel","v":1,"kind":"request","id":"req-42","cmd":"sensor.read","name":"accelerometer","args":{"rateHz":25,"raw":true}}`.
  Missing `v` defaults to version 1, missing `kind` defaults to `request`, missing `id` is valid, and
  missing `args` defaults to an empty object.
- `cmd` and `name` use Diesel protocol identifiers. Structured argument keys have a separate rule
  that permits camelCase names such as `rateHz`.
- Diesel request JSON is bounded to 4096 UTF-8 bytes. Nested argument objects/lists are also bounded
  by depth, collection size, total value-node count, field-name length, and individual text-value
  size. Large files and streams do not belong in the request/response control plane.
- later: `{"t":"alarm",…}`, `{"t":"weather",…}`

### Diesel request / response transport

- Structured Diesel responses are serialized inside a fixed Gadgetbridge Bangle.js `t:"intent"`
  message targeting an Android broadcast receiver. The action is fixed to
  `io.github.bloom11.dieselbridge.DIESEL_MESSAGE`; remote requests cannot choose an arbitrary
  Android Intent target or action.
- The `json` Intent extra contains a versioned Diesel response envelope with
  `"kind":"response"`, for example:
  `{"v":1,"kind":"response","id":"req-42","cmd":"test","name":"vibration","status":"ok","data":{...}}`.
  `id`, `name`, and `data` are omitted when absent.
- Diesel response JSON is bounded to 4096 UTF-8 bytes before the Gadgetbridge wrapper is added.
- Valid requests are decoded into transport-independent `DieselRequest` objects and dispatched by
  `DieselProtocolEngine`. Generic `args` survive the Gadgetbridge adapter unchanged.
- Invalid Diesel envelopes never enter the command registry. They produce
  `status:"invalid_request"` through the same response transport, with a stable
  `data.reason` such as `invalid_args`, `unsupported_version`, or `payload_too_large`.
- Only validated correlation fields are reflected. If the inbound command is absent or invalid,
  the response uses the reserved command `"protocol"`. Detailed parser diagnostics remain local
  and are never copied into the remote response.

### Outbound (watch → phone) — the action back-channel — ✅ = implemented
- ✅ `{"t":"notify","id":…,"n":"DISMISS"|"DISMISS_ALL"|"REPLY","msg":"<text>"}`
  ⚠️ The reply text goes in **`msg`** (not `reply`) — Gadgetbridge's `handleNotificationControl`
  reads `json.getString("msg")` and maps `id`→the stored RemoteInput handle for REPLY. Gadgetbridge
  upper-cases `n` and does `Event.valueOf(n)`. The watch UI emits only these three; Gadgetbridge also
  accepts `OPEN`/`MUTE` if ever sent, but the notification card no longer exposes them.
- ✅ `{"t":"status","bat":<0-100>,"volt":<double>,"chg":<0|1>}` — battery (on subscribe + on change).
- ✅ `{"t":"ver","fw":"<app-version>","hw":"<Build.MODEL>"}` — version handshake (on subscribe).
- ✅ `{"t":"findPhone","n":<boolean>}` — ring the phone (`n` a JSON **boolean**).
- ✅ `{"t":"call","n":"ACCEPT|REJECT|IGNORE|END"}` — call control (`Event.valueOf(n.uppercase())`).
- ✅ `{"t":"music","n":"play|pause|next|previous|volumeup|volumedown"}` — media remote (lowercase).

**Canonical examples**
```
phone→watch:  GB({"t":"notify","id":1575479849,"src":"Signal","title":"Alice","body":"hi"})
phone→watch:  GB({"t":"notify-","id":1575479849})
watch→phone:  {"t":"notify","id":1575479849,"n":"DISMISS"}
```

## Actions on the phone side (no Google stack)

Gadgetbridge maps a watch-sent **DISMISS** → `NotificationListenerService.cancelNotification(key)`
and **REPLY** → the notification's `Notification.Action` + `RemoteInput` `PendingIntent` — full
dismiss/reply parity on stock Android.

## Correlation, bonding, robustness

- **Always carry and echo the notification `id`.** `NotificationListener` doesn't always set stable
  `NotificationSpec` IDs, so the watch keys its UI and back-channel off the `id` it received (the
  Bangle.js dialect already uses `id`).
- **Bonding** is optional for NUS data (no encryption required) but makes
  `connectGatt(autoConnect=true)` reconnection durable across BT-cache clears. Choose the
  coordinator's bonding style (`NONE` / `ASK`) accordingly.
- **Serialize GATT ops** through one queue to avoid `status 133`.

## Sources

[Bangle.js protocol (Gadgetbridge)](https://gadgetbridge.org/internals/specifics/banglejs-protocol/) ·
[Espruino Gadgetbridge protocol](https://github.com/espruino/EspruinoDocs/blob/master/info/Gadgetbridge.md) ·
[Nordic UART Service](https://docs.nordicsemi.com/bundle/ncs-3.2.0/page/nrf/libraries/bluetooth/services/nus.html) ·
[Android BLE transfer](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data) ·
[Android BLE guide (Punch Through)](https://punchthrough.com/android-ble-guide/)

### D5.4 generic command migration

- D5.4 generic command migration: `diagnostics`, `commands`, and bounded `test`
  are installed through `DeveloperCommandModule` into the generic
  `DieselCommandRegistry`.
- `GbProtocol` delegates Diesel envelopes to the bounded generic request decoder. Valid requests
  reach `DieselProtocolEngine` as transport-independent `DieselRequest` objects; rejected requests
  remain explicit protocol failures rather than falling through to an unrelated message type.
- Command implementations return `DieselCommandResult`; they do not serialize
  JSON or write to BLE.
- The `commands` response is generated from the live generic registry, so
  commands installed later by sensor, alarm, health, display, or plugin modules
  are discovered automatically.
- Unknown commands return `unknown_command` without executing any action.
- Safe-test results map to stable response statuses: `ok`, `unavailable`,
  `rate_limited`, `failed`, and `unknown_target`.
- Watch→phone protocol messages use the fixed Android broadcast action
  `io.github.bloom11.dieselbridge.DIESEL_MESSAGE`. The JSON extra contains
  `"kind":"response"` so the same Android delivery boundary can later carry
  Diesel events without introducing command-specific Intent actions.


### D5.5 bounded generic request boundary

- D5.5 adds exact wire decoding for protocol version, message kind, correlation id, command, target,
  and recursively typed generic arguments.
- Supported generic values are text, signed 64-bit integer, finite decimal, boolean, explicit JSON
  null, nested objects, and lists.
- Canonical Gadgetbridge Diesel requests place `"t":"diesel"` first. This allows the adapter to
  recognize the Diesel namespace before general JSON dispatch so malformed or oversized canonical
  requests reach the bounded Diesel rejection path instead of being silently dropped.
- Valid non-canonical JSON field ordering remains supported.
- The generic engine owns both valid-command responses and `invalid_request` responses, including
  transport-delivery error accounting.
- Adding a future command such as `sensor.read` does not require command-specific changes to NUS,
  Gadgetbridge response transport, response correlation, or the Diesel protocol engine.

### M4.2b0b bounded execution and overload feedback

- Diesel command execution remains FIFO: one running request plus four waiting. Valid and invalid
  decoded requests consume the same budget; submission never waits for execution or transport.
- A full execution queue offers a generic `status:"rate_limited"` response with
  `data.reason:"execution_queue_full"`. The engine echoes the same validated correlation fields
  as ordinary responses; an invalid/missing command uses `"protocol"`. Rejected commands do not run.
- Feedback has its own single worker and four waiting slots, shared by valid and invalid requests.
  It may overtake a slow admitted request. Clients must correlate by request ID, not arrival order.
  When feedback is full, newest feedback is dropped. No per-rejection coroutine or unlimited retry
  is created. `FULL` still returns an immediately cancelled submission completion; that completion
  does not track feedback delivery.
- Shutdown reports `CLOSED`, cancels queued work/feedback, and does not schedule rejection replies.
  An already-started synchronous transport send cannot be recalled. Transport failures are recorded
  through the existing dispatch callbacks and do not kill the execution worker.
- NUS TX now accepts whole lines only while they fit its 64 KiB queued-payload budget, plus at most
  one already-dequeued MTU chunk in flight. A rejected line contributes no partial chunks. The bound
  applies to all outgoing NUS lines, including legacy actions; callers receive `false` on congestion.
  Disconnect/close clears the queue. NUS does not inspect commands or response statuses.
- An admitted request normally receives one response attempt, not guaranteed end-to-end delivery.
  Cancellation, disconnection or TX congestion can prevent delivery. Unlimited ingress cannot have
  both bounded buffering and guaranteed replies without ingress backpressure. Clients should use
  bounded request rates/timeouts and avoid blindly retrying actions whose outcome is unknown.

### M4.2b2 exact developer sensor probe

`debug.sensor.probe` is a diagnostic first-event read, not the public `sensor.read` API.
It is installed through the command registry and runs in the existing bounded execution lane.
The only accepted args are required text `routeId` and optional integer `timeoutMs` (default 5000,
range 500–15000 inclusive). `name` must be absent. Route IDs use the opaque `SensorRouteId` contract;
clients should copy them from `sensor.list`, not manufacture provider-specific identifiers.

Syntax validation precedes local remote-developer authorization, which precedes fresh exact-route
resolution and sensor activation. Disabled access returns `unavailable` with
`remote_developer_access_disabled`; no sensor census or registration occurs. Authorization is
checked when queued work executes. Turning it off does not abort an already-started bounded probe;
stopping the service cancels sampling and cleans up registrations.

Successful diagnostic outcomes use `status:"ok"` with `data.outcome` equal to `event`, `timeout`,
`permission_denied`, `registration_rejected` or `route_unavailable`. Unexpected exceptions return
`failed / probe_failed`; cancellation propagates. A missing route returns the requested `routeId`.
A permission exception before resolution likewise returns that ID and `requiredPermission:null`.

Resolved results include:

- `route`: unchanged route/provider/logical IDs, Android ID/type, string type, name, vendor,
  reporting mode, wake-up flag, required permission (null when unknown), and `metadataTruncated`.
- `registration`: `kind` (`listener` or `trigger`), requested `timeoutMs`, `elapsedMs`, and
  `samplingPeriodUs` (200000 for listeners; null for one-shot triggers).
- For an event: `accuracy` (null for triggers), raw `sensorTimestampNs` in the monotonic sensor
  clock domain, `timeToEventMs`, `sampleCount:1`, actual `valueCount`, `returnedValueCount`,
  `valuesTruncated`, and up to 64 raw values. No wall-clock conversion or unit inference is made.

Non-finite values become JSON null, with zero-based positions recorded in `event.nonFinite.nan`,
`positiveInfinity`, and `negativeInfinity`. Missing categories are omitted. Returned finite values
are not clamped or rounded. Positions beyond the 64-value prefix are not annotated;
`valueCount` and `valuesTruncated` explicitly identify that loss.

Responses are measured with the actual request correlation against the 4096-byte JSON budget.
Descriptive metadata starts at 64 code points and is shortened further if needed; control characters
are normalized and changes are flagged. Identities, returned values and non-finite annotations are
never shortened to make a response fit. Full descriptions remain in the passive census/export.
No BODY_SENSORS or ACTIVITY_RECOGNITION permission was added for the first hardware campaign.
