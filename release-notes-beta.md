## What's New (Beta v1.6.0)

### Fixed — Proxy Hanging After Hours (Critical)
This beta fixes the root cause of the "proxy works for a while then silently stops connecting" issue reported on Android devices under mobile networks.

- **Removed permanent CF-fallback lock-in**: `CF_SUCCESS_THRESHOLD` (set to 1) previously blocked all direct WebSocket connections after a single successful Cloudflare fallback, eventually forcing every connection exclusively through CF until domains were exhausted or rate-limited. Direct attempts are now always tried (subject to a 60-second cooldown after hard failures only).
- **CF-success decay**: `cfSuccessCount` is halved every 5 minutes by a background cleanup coroutine. This prevents a transient CF success from permanently biasing the proxy away from direct paths when the network later improves.
- **Fixed broken WebSocket pool health check**: `pingPongCheck()` used a blocking `recv()` expecting a PONG frame, but `recv()` handles PONG internally and never returns it — the check never completed, marking healthy pooled connections as dead. Replaced with a simple `!isClosed` check for speed and correctness.
- **WebSocket read timeout**: Changed `sslSocket.soTimeout` from `0` (infinite) to **70 seconds** (`RawWebSocket.kt`). On mobile networks or during Doze, a "silent" network drop (no RST/FIN packet) previously caused threads to hang forever, eventually leading to OutOfMemoryError or service death. The timeout frees these threads cleanly.
- **Service restart robustness**: `scheduleRestart()` now falls back from `setExactAndAllowWhileIdle` to `setAndAllowWhileIdle` on devices that restrict exact alarms. Restart delay increased from 1s to 3–5s to allow cleanup to complete before relaunch.

---

## Full Changelog
See [CHANGELOG.md](https://github.com/ComradeSwarog/tg-ws-proxy-android/blob/beta/CHANGELOG.md).

---

## How to Test
1. Download `tg-ws-proxy-android-beta.apk` below.
2. Transfer to your Android device and install.
3. Start the proxy and leave it running for **1+ hours** (preferably on mobile data).
4. Check that Telegram messages continue to send/receive without re-connecting to proxy.
5. Share logs via **Logs tab → Share** if issues occur.

---

## Requirements
- Android 8.0+ (API 26+)
- Telegram app (any MTProto proxy client)

---

## Assets

| File | SHA-256 |
|------|---------|
| `tg-ws-proxy-android-beta.apk` | *(see artifact below)* |

---

## Links
- [Full Changelog](https://github.com/ComradeSwarog/tg-ws-proxy-android/blob/beta/CHANGELOG.md)
- [Source Code (beta branch)](https://github.com/ComradeSwarog/tg-ws-proxy-android/tree/beta)
- [Report an Issue](https://github.com/ComradeSwarog/tg-ws-proxy-android/issues/new)
