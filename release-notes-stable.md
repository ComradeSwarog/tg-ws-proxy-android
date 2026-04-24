## What's New

### Connection Speed (Critical)

First connect time reduced from **8–20 seconds to 1–3 seconds** on DPI-blocked networks.

- **Parallel Race: Direct WS vs CF Fallback** — on first connect, both direct WebSocket and Cloudflare fallback are launched simultaneously. The faster one wins and bridges immediately.
- **CF-Proven Fast Path** — after 2+ successful CF connections to a DC, direct attempts are skipped for 5 minutes. Automatically recovers when network conditions improve.
- **Aggressive timeout tuning** — CF proxy 6s→3s, DoH 8s→3s, race cap 6.5s→4.5s, TLS handshake 8s→5s.
- **Zero-delay fallback** — known-blocked DCs skip the race entirely and go straight to CF fallback.
- **Deadlock-free TLS** — TLS handshakes run on a dedicated thread pool, separate from the parallel connect pool. Previously they shared one pool, causing a deadlock where all threads blocked waiting for TLS and no connection could complete.

### Stability (Critical)

- **Fixed proxy hanging after hours** — removed permanent CF-fallback lock-in that blocked direct WebSocket reconnections after a single CF success. CF success counters now decay every 5 minutes so direct paths are automatically retried.
- **Fixed broken WebSocket pool health check** — dead sockets were handed to clients because `pingPongCheck()` never completed correctly. Now uses a simple `!isClosed` check.
- **WebSocket read timeout** — set 70-second `soTimeout` on TLS socket so threads are freed instead of leaking forever on silent network drops (common on mobile / Doze).
- **Service restart robustness** — restart alarm now falls back to `setAndAllowWhileIdle` on devices that restrict exact alarms.

---

## Full Changelog (v1.5.0 → v1.6.0)

### v1.6.0 — Connection Speed + Stability
- Parallel Race: direct WS vs CF fallback on first connect (1–3s vs 8–20s)
- CF-Proven Fast Path: skip direct after 2+ CF successes, auto-recover
- Fixed thread pool deadlock: separate `tlsHandshakeExecutor` for TLS
- Fixed slow first connect: CF 3s, DoH 3s, race 4.5s, TLS 5s, no 150ms CF delay
- Skip race for known-blocked DCs → instant CF fallback
- Removed permanent CF lock-in; CF success decays every 5 min
- Fixed pool health check: `!isClosed` instead of broken `recv()` PING
- `sslSocket.soTimeout = 70_000` (was 0/infinite)
- Service restart fallback: `setAndAllowWhileIdle` for restricted devices

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
| **CF domain DNS failures** | Some domains in the CF pool are dead (NXDOMAIN). Balancer blacklists them, but first-time startup may try a few dead ones | Upgrade to v1.6.0 (3s timeout per domain, auto-blacklist) |

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