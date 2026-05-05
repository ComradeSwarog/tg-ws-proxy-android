## What's New

### Auto Network Recovery (Critical)

The proxy now **detects WiFi/mobile network switches** and instantly resets all stale state:

- Clears stale `dcFailUntil` and `cfProvenUntil` cooldowns
- Purges stale WebSocket connection pool
- Clears domain blacklist (domains may work on new network)
- Re-acquires `WifiLock` on the new network interface

No more "proxy stuck after switching WiFi" — recovery is automatic and instant.

### Keepalive PONG Fix

Empty PONG frames from Telegram servers no longer cause false keepalive timeouts that disconnected active bridges. Any frame received now correctly resets the keepalive timer.

### Faster Dead-Connection Detection

`sslSocket.soTimeout` reduced from 70s to 35s — dead connections after network loss are detected twice as fast, freeing threads sooner.

---

## Full Changelog (v1.6.0 → v1.6.1)

- Added `ConnectivityManager.NetworkCallback` for network change detection
- `onNetworkChange()` resets: `dcFailUntil`, `cfProvenUntil`, `cfSuccessCount`, `wsPool`, `Balancer.blacklist`, `WifiLock`
- New `TgWsProxy.resetForNetworkChange()` method for proxy-level recovery
- New `Balancer.resetBlacklist()` method
- Fixed keepalive PONG: `lastPong.set(true)` on every frame (was only on `isNotEmpty()`)
- Reduced `sslSocket.soTimeout` from 70s to 35s
- Debounce network change events by 2s to avoid rapid repeated restarts

---

## How to Install

> ⚠️ **Google Play Protect warning** is expected for open-source apps. Tap **"Install anyway"** (or **"More details → Install anyway"**).

1. Download `tg-ws-proxy-android.apk` below.
2. Transfer to your Android device (Telegram Saved Messages, USB, or cloud storage).
3. Open the APK — Android will prompt to allow installation from this source. Grant permission.
4. Install and open the app.
5. Tap **Start** to launch the proxy.
6. Copy the generated link and paste it into Telegram → Settings → Data and Storage → Proxy Settings → Add Proxy.

---

## Requirements
- Android 8.0+ (API 26+)
- Telegram app (any client that supports MTProto proxy links)

---

## Assets

| File | SHA-256 |
|------|---------|
| `tg-ws-proxy-android.apk` | `2AA6896863BBBE723B8485E381B7A2507DDF7D998C0F5DB083E595C9EF1CD84E` |

---

## Links

- [Full Changelog](https://github.com/ComradeSwarog/tg-ws-proxy-android/blob/master/CHANGELOG.md)
- [Source Code](https://github.com/ComradeSwarog/tg-ws-proxy-android)
- [Report an Issue](https://github.com/ComradeSwarog/tg-ws-proxy-android/issues/new)