## What's New in v1.7.1

### Media Loading Fix

**Problem:** Photos and videos stopped loading in Telegram when using the proxy.

**Root cause:** Three issues combined to break media sessions:

1. **Client socket `soTimeout = 30s`** — media downloads can have pauses > 30s between packets. The `cltInput.read()` would throw `SocketTimeoutException`, killing the uplink job and closing the session prematurely.

2. **Upstream WS `soTimeout = 35s`** — large media files from Telegram's media servers can have pauses > 35s between chunks. The `ws.recv()` would timeout, killing the downlink job.

3. **Bridge join logic `upJob.join(); downJob.cancelAndJoin()`** — waited only for **uplink** completion, then killed **downlink**. But during media downloads, the uplink is idle (client only receives data), while downlink is actively streaming the photo/video. The old logic killed the active downlink when the idle uplink timed out.

### Fixes Applied

| Fix | File | Change |
|-----|------|--------|
| Client socket timeout → 0 (infinite) | `TgWsProxy.kt:137` | `soTimeout = 30_000` → `soTimeout = 0` |
| Upstream WS timeout → 0 (infinite) | `RawWebSocket.kt:341` | `soTimeout = 35_000` → `soTimeout = 0` |
| Bridge: FIRST_COMPLETED semantics | `TgWsProxy.kt` `bridgeWsReencrypt` | `upJob.join(); downJob.cancelAndJoin()` → `AtomicReference<Job?>` + `CountDownLatch` — whichever direction finishes first wins, the other is cancelled |
| TCP bridge: same FIRST_COMPLETED fix | `TgWsProxy.kt` `bridgeTcpReencrypt` | Same pattern applied |
| Keepalive interval configurable | `ProxyConfig.kt` | Added `wsKeepaliveIntervalMs: Long = 25_000L` (ported from upstream v1.7.3) |

### Why this works

- **`soTimeout = 0`** means no artificial timeout on reads — the socket waits indefinitely for data
- **Dead connections are now detected by the keepalive PING/PONG loop** (not by socket timeout):
  1. Every 25s, `ws.ping()` sends a WebSocket PING to the upstream
  2. `recv()` processes PONG and sets `lastPong = true`
  3. If no PONG received before next interval, `ws.close()` kills the session
- **FIRST_COMPLETED** matches upstream `asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)` — the bridge ends when either direction naturally closes, not when one side goes quiet

### Synced with upstream v1.7.3

Upstream commit `96e5b4b` ("fix: add WebSocket keepalive pings to prevent idle disconnects (#646)") added the same keepalive PING approach. Our implementation already had keepalive, but the socket timeouts were overriding it. Now both work together correctly.

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
| `tg-ws-proxy-android.apk` | `C7B375AAEA2B6B27AD9302F6DF1929C4719254C7BF9FEC4340A3AE68C702AEE7` |