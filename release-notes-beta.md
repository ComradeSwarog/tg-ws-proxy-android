## What's New (Beta v1.6.0-beta4 — Race Fix)

### Fixed — First Connection Speed (5-9s → <2s)
The previous beta (beta3-hf) worked correctly, but the **first connection after enabling proxy took 5-9 seconds**. This beta fixes that with a parallel race between Direct WS and CF fallback.

- **Parallel race on first connect**: `handleClient()` now launches direct WS and CF fallback simultaneously via coroutines. The winner (whichever connects first) is used immediately, the loser is cancelled and its socket cleaned up. This cuts first-connect time from ~5-9s down to ~1-2s on blocked networks.
- **CF staggered by 150ms**: CF connection starts 150ms after direct, because CF involves extra DNS resolution latency. On unblocked networks direct wins fast; on blocked networks CF wins seconds before direct would timeout.
- **Reuses existing `cfproxyConnectOnly()`**: Extracted CF-only connection logic so both `raceConnection()` and `doFallback()` share the same connection code without duplication.
- **CF-proven mode preserved**: Once 2+ CF successes accumulate, subsequent connections skip direct entirely (`CF proven active, skipping direct`), just like before.

### Previously Fixed (beta1-beta3)
- **Removed permanent CF-fallback lock-in** (CF_SUCCESS_THRESHOLD removed)
- **CF-success decay** every 5 minutes via background cleanup
- **Fixed broken WebSocket pool health check** (infinite blocking `recv()` → `!isClosed`)
- **WebSocket read timeout** 70s (was infinite, caused thread leaks)
- **Service restart robustness** (no double-restart loop)
- **Hotfix**: restored missing `doFallback()` call after direct WS failure

---

## Full Changelog
See [CHANGELOG.md](https://github.com/ComradeSwarog/tg-ws-proxy-android/blob/beta/CHANGELOG.md).

---

## How to Test
1. Download `tg-ws-proxy-android-beta.apk` below.
2. Transfer to your Android device and install.
3. **Test first connect**: enable proxy on mobile data (blocked network). Telegram should connect within **1-3 seconds** instead of 5-9.
4. **Test reconnection**: close Telegram, reopen — should reconnect instantly.
5. **Longevity**: leave running for 30+ min, verify no hangs.
6. Share logs via **Logs tab → Share** if issues occur.

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
