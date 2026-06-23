## What's New in v1.7.3

### Critical Fix: Keepalive Killing Live Connections

**Problem:** Connections were being killed after 25–35 seconds, media channels never loaded.

**Root cause:** The keepalive `lastPong` logic was broken:

1. `recv()` processes PONG frames internally (`OP_PONG → continue`) and **never returns** to the caller
2. `lastPong` was only set to `true` when `recv()` returned data (binary/text frames)
3. Keepalive checked `lastPong` every 25s — saw `false` (PONG was swallowed) → closed the connection
4. Media sessions died at exactly 25.2s (`^722,0B v8,0B 25,2s`) — one keepalive interval
5. Regular sessions survived slightly longer (35.1s) if data arrived, but died on the second cycle

**Fix:** Removed `lastPong` logic entirely. Keepalive now simply sends PING every 25s (matching upstream v1.7.3 `_ws_keepalive`). Dead connections are detected by:
- `soTimeout = 60s` on upstream WS socket (safety net)
- `ping()` throws `IOException` if socket is dead → keepalive catches it and closes

### Also Fixed

| Fix | File | Change |
|-----|------|--------|
| `ping()` now throws on error | `RawWebSocket.kt:411` | `catch (_: Exception) {}` → `throw IOException` — keepalive can detect dead sockets |
| Upstream WS `soTimeout` | `RawWebSocket.kt:347` | `0` (infinite) → `60_000` (60s safety net) |

---

## How to Install

1. Download `tg-ws-proxy-android.apk` below
2. Transfer to your Android device
3. Open the APK — grant installation permission
4. Tap **Start** to launch the proxy
5. Copy the link into Telegram → Settings → Proxy Settings → Add Proxy

---

## Requirements
- Android 8.0+ (API 26+)
- Telegram app

---

## Assets

| File | SHA-256 |
|------|---------|
| `tg-ws-proxy-android.apk` | `BB149972E1211E9657A1828822936D15877DE1850FAC64A54E67481D6680023C` |