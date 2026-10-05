# ADB Watchdog

> Keep your Android devices connected — automatically.

ADB Watchdog monitors your ADB connections in real time. When a device disconnects, goes offline, or loses authorization, it reconnects automatically — no manual intervention needed.

Works on **macOS**, **Windows**, and **Linux**. Lives in the menu bar / system tray.

---

## Download

Go to the [Releases](../../releases) page and grab the installer for your OS:

| Platform | File |
|----------|------|
| macOS | `ADB-Watchdog-x.x.x.dmg` |
| Windows | `ADB-Watchdog-x.x.x.msi` |
| Linux (Debian/Ubuntu) | `adb-watchdog-x.x.x.deb` |

> **macOS note:** On first launch, right-click the app → **Open** to bypass Gatekeeper.

---

## What It Does

- **Live monitoring** — listens to `adb track-devices`. Notifies you the moment a device disconnects, goes offline, or loses authorization.
- **Auto-reconnect (Wi-Fi devices)** — tries the last known `ip:port`, falls back to mDNS discovery, then port 5555. Retries with exponential back-off (3 s → 60 s). After 3 failures, suggests re-pairing.
- **Wireless pairing** — for Android 11+, enter the pairing address and 6-digit code. ADB Watchdog finds the connection port for you.
- **Per-device stability options** — all off by default, toggle them per device from the card:

| Option | What it does |
|--------|-------------|
| Disable auth timeout | Sets `adb_allowed_connection_time 0` so authorization never expires |
| Lock port to 5555 | Runs `adb tcpip 5555` and reconnects. Re-applies automatically after reboot |
| Block sleep & Doze | Keeps the device awake while plugged in |
| Monitor IP changes | Counts IP changes, warns when threshold is exceeded, shows MAC address |
| Keep-alive ping | Sends `shell echo ok` every N seconds; resets connection on no response |

> Emulators: options 2 and 4 are disabled; the rest work normally.

---

## Requirements

- **ADB (Android Debug Bridge)** — part of Android SDK Platform Tools.
  Install via Android Studio, or [download standalone](https://developer.android.com/tools/releases/platform-tools).
  If ADB isn't found automatically, set the path in **Settings** inside the app.

---

## Getting Started

1. Install the app.
2. Connect your Android device via USB or Wi-Fi.
3. ADB Watchdog appears in the **menu bar** (macOS) or **system tray** (Windows/Linux).
4. Your device shows up in the list. That's it — it stays connected automatically from now on.

### Wireless setup (Android 11+)

1. On your device go to **Developer Options → Wireless debugging → Pair device with pairing code**.
2. In ADB Watchdog click **Pair** and enter the address and code shown on the device.
3. Done. Future reconnects happen automatically.

---

## Settings Location

| OS | Path |
|----|------|
| macOS | `~/Library/Application Support/AdbWatchdog/config.json` |
| Windows | `%APPDATA%\AdbWatchdog\config.json` |
| Linux | `~/.config/adb-watchdog/config.json` |

---

## Build From Source

Requirements: **JDK 17+**

```bash
git clone https://github.com/BusraOnder/adb-watchdog.git
cd adb-watchdog
./gradlew run          # run directly
./gradlew packageDmg   # macOS installer
gradlew.bat packageMsi # Windows installer
./gradlew packageDeb   # Linux .deb
```

---

## License

MIT
