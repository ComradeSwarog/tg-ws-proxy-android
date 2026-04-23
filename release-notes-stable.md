## What's New

### Samsung / Android 16 Stability (Critical)
This release fixes the "works then freezes after 10–15 minutes" pattern reported on Samsung Galaxy devices and Android 16.

- **WakeLock + WifiLock**: ProxyService now acquires `PARTIAL_WAKE_LOCK` and adaptive `WifiLock` to keep the radio active during long-running proxy sessions.
- **WakeLock refresh loop**: A background coroutine re-acquires the WakeLock every 25 minutes, preventing Samsung Device Care from letting it expire after the 30-minute window.
- **Foreground service type `dataSync`**: Replaced `specialUse` with `dataSync` in `AndroidManifest.xml`, along with the required `FOREGROUND_SERVICE_DATA_SYNC` permission. Android 16+ is less aggressive with this foreground service type.
- **Sticky restart safety**: Service now uses `START_REDELIVER_INTENT` so the last valid Intent is replayed automatically after system-initiated restarts.
- **Explicit WakeLock release**: Both WakeLock and WifiLock are released explicitly in `stopProxy()`, preventing battery drain if the service crashes.

---

## Full Changelog (v1.3.0 → v1.5.0)

### v1.5.0 — Samsung/Android 16 Stability
- `PARTIAL_WAKE_LOCK` (30 min) + adaptive `WifiLock` (`LOW_LATENCY` on Android 10+, `HIGH_PERF` fallback)
- WakeLock refresh every 25 min via Kotlin Coroutine
- `START_REDELIVER_INTENT` for reliable sticky restarts
- `foregroundServiceType="dataSync"` + `FOREGROUND_SERVICE_DATA_SYNC` permission
- Explicit `releaseWakeLocks()` and cancel `wakeLockRefreshJob` on `stopProxy()`
- Auto-generated secret on first launch

### v1.4.0 — Localization & UX
- Russian & English localization (system auto-detect + manual switch)
- In-app Help page (`?` button in toolbar)
- Check for Updates (manual button + optional auto-check on startup)
- New toggles in Bypass Settings: WS Frame Padding, DoH Rotation

### v1.3.0 — DPI Bypass Hardening
- WS Frame Fragmentation: splits large payloads into random-sized chunks (512–4096 bytes)
- WS Frame Padding (self-describing `u16_be` length-prefix format)
- DoH Endpoint Rotation between Cloudflare / Google / Quad9

---

## How to Install

> ⚠️ **Google Play Protect warning** is expected for open-source apps. Tap **"Install anyway"** (or **"More details → Install anyway"**).

### Method A — APK (recommended)
1. Download `tg-ws-proxy-android.apk` below.
2. Transfer to your Android device (Telegram Saved Messages, USB, or cloud storage).
3. Open the APK — Android will prompt to allow installation from this source. Grant permission.
4. Install and open the app.
5. Tap **Start** to launch the proxy.
6. Copy the generated link and paste it into Telegram → Settings → Data and Storage → Proxy Settings → Add Proxy.

### Method B — Build from Source
See [BUILD.md](https://github.com/ComradeSwarog/tg-ws-proxy-android/blob/master/BUILD.md) for full instructions.

---

## Requirements
- Android 8.0+ (API 26+)
- Telegram app (any client that supports MTProto proxy links, e.g. official, Forkgram, ForkgramX, Nekogram, Nagram)

---

## Known Issues

| Issue | Description | Workaround |
|-------|-------------|------------|
| **Samsung freeze** | Device Care may throttle foreground service after ~30 min of inactive screen | **Fixed in v1.5.0** via WakeLock refresh + `dataSync` service type |

---

## Assets

| File | SHA-256 |
|------|---------|
| `tg-ws-proxy-android.apk` | *(see artifact)* |

---

## Links

- [Full Changelog](https://github.com/ComradeSwarog/tg-ws-proxy-android/blob/master/CHANGELOG.md)
- [Source Code](https://github.com/ComradeSwarog/tg-ws-proxy-android)
- [Report an Issue](https://github.com/ComradeSwarog/tg-ws-proxy-android/issues/new)
- [Original tg-ws-proxy (desktop)](https://github.com/Flowseal/tg-ws-proxy)
