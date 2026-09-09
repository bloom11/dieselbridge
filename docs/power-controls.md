# Developer power and sleep controls

The Diesel Developer **Power** screen reports electrical readings from Android `BatteryManager`:
level, voltage, instantaneous current when available, charge counter, energy counter, temperature and
charging state. It also queries `UsageStatsManager` for the last 24 hours when the watch grants the
special Usage Access permission.

Android does not grant a normal application the privileged `BATTERY_STATS` permission required to
attribute consumed battery energy to individual packages. The screen therefore labels battery
attribution unavailable. App rows show foreground-time share, not battery-drain percentage. This is
intentional: an app's foreground time cannot be presented as measured energy use. If the watch does
not expose Usage Access, the screen says so and shows no fabricated rows.

The sleep policy is app-owned:

- **Active** starts the connected-device bridge normally.
- **Optimized** starts the bridge and leaves the system Doze exemption unchanged.
- **Sleeping** stops the bridge and suppresses boot auto-start until Active or Optimized is chosen.

The Doze exemption card reads `PowerManager.isIgnoringBatteryOptimizations`. The system settings
button is best effort; TicWatch/Wear OS builds may not expose the standard exemption dialog. When
needed, use the watch's current ADB endpoint:

```sh
adb -s WATCH_IP:PORT shell dumpsys deviceidle whitelist +io.github.bloom11.dieselbridge
adb -s WATCH_IP:PORT shell dumpsys deviceidle whitelist | grep io.github.bloom11.dieselbridge
```

These controls do not modify other apps, hidden system app-standby buckets, or vendor power modes.
Those operations require privileged/system APIs and would be unsafe to imitate. The next sensible
power work is to observe real watch behavior from the counters and BLE/sensor logs, then add only
controls supported by evidence on this TicWatch Pro 5.
