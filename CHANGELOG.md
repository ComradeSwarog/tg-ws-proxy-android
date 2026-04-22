# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.1] - 2026-04-22

### Fixed (Critical Stability)
- **ForegroundServiceDidNotStartInTimeException**: `startForeground()` is now called **immediately** in `onStartCommand()`, before any branching, preventing Android 12+ from killing the service on sticky restart. (`f9d5910`)
- **NullPointerException in connectParallel**: `ConcurrentHashMap` rejects null values. Fixed `allWs[Thread] = ws` where `ws` could be null on failed connect — now conditional `if (ws != null)`. (`51b51de`)
- **InterruptedException in Thread.sleep**: Added `try/catch` around cleanup sleep in `connectParallel()` to prevent cascading crash. (`ec3636e`)
- **Sticky restart handling**: Null intent (system auto-restart) now triggers proxy restart with cached config instead of doing nothing. (`f9d5910`)

### Fixed (Root Causes from Log Analysis)
- **Global SSLSocketFactory poisoning**: Removed `HttpsURLConnection.setDefaultSSLSocketFactory()` in `DoHResolver.kt`. Now uses isolated per-connection `SSLContext`. (`5674d6f`)
- **DoH called for raw IPs**: Added `isIpAddress()` fast-path in `DoHResolver.resolve()` — skips HTTP roundtrip when target is already a dotted IPv4. (`5674d6f`)
- **Socket FD exhaustion in parallel connect**: Losing threads now have their partially-opened `Socket`/`SSLSocket` tracked via `ConcurrentHashMap<Thread, Socket>` and explicitly closed after winner is chosen. (`5674d6f`)
- **Client timeout too short**: Increased `client.soTimeout` from 10s to 30s in `TgWsProxy.kt`. MTProto handshake silence legitimately lasts 10–15s under DPI. (`5674d6f`)
- **CF hammering / 429 rate-limit**: `Balancer.markDomainFailed()` now accepts custom TTL. CF rejects (429, DNS NXDOMAIN) blacklisted for 2min; prewarm rejects for 1min. (`5674d6f`)
- **Unused `distinct()`**: `addrsToTry.distinct()` result now assigned to `finalAddrs` variable. (`5674d6f`)

### Documentation
- Added `AGENTS.md` with APK naming convention:
  - Release: `tg-ws-proxy-android.apk`
  - Debug: `tg-ws-proxy-android-debug.apk`

### Internal
- Bumped versionCode: 2 → 3, versionName: 1.1.1 → 1.2.0
- Updated unit tests to match new `baseTtlMs` parameter name

## [1.3.0] - 2026-04-23

### Added
- **WS Frame Fragmentation**: `RawWebSocket.send()` splits large MTProto payloads into binary + continuation frames with random chunk sizes (512–4096 bytes). This reduces fixed-size signature exposure and improves DPI bypass resilience.
- **WS Frame Padding (self-describing)**: Each frame carries a length-prefix header: `[u16_be payloadLen] [payloadLen bytes payload] [random padding]`. The format is self-describing, allowing safe stripping on the receiving side. Padding is disabled by default; controlled via `ProxyConfig.wsFramePadding`, `wsFramePaddingMinBytes`, `wsFramePaddingMaxBytes`.
- **DoH Endpoint Rotation**: `DoHResolver.resolve()` now cyclically rotates through configured providers via atomic round-robin (`rotateEndpoints()`). Each call starts from a different endpoint, reducing single-provider lock-in. Enabled by default via `ProxyConfig.dohRotation`.

### Fixed
- **Length-prefix padding format**: Replaced naive trailing-padding with self-describing `u16_be` header to prevent MTProto framing corruption during pass-through via CF Worker.

### Internal
- Bumped versionCode: 3 → 4, versionName: 1.2.0 → 1.3.0
- Added unit tests for `DoHResolver.rotateEndpoints`, `RawWebSocket.encodePaddedPayload`, `RawWebSocket.stripPaddingIfPresent`
- All 18 unit tests pass

### Documentation
- Updated `CHANGELOG.md` with new feature descriptions

## [1.2.0] - 2026-04-23

### Fixed (Critical Stability)
- **OutOfMemoryError: unable to create new native thread**: `RawWebSocket.connectParallel` and `TgWsProxy.WsPool.refill` now use bounded `Executors.newFixedThreadPool()` (8 and 4 threads) instead of spawning raw `Thread`s per connection attempt. Prevents memory exhaustion under heavy fallback load.
- **ForegroundServiceDidNotStartInTimeException on sticky restart**: `startForeground()` moved to the very top of `onStartCommand()`, before any `when(intent.action)` branching, guaranteeing the service call is registered within Android 12+ 5-second deadline even on null-intent restarts.
- **OutOfMemoryError does not trigger graceful shutdown**: Added `CoroutineExceptionHandler` on `serviceScope` and `Thread.setDefaultUncaughtExceptionHandler` in `ProxyService.onCreate()`. On OOM signals proxy stop, foreground removal, and app exit to prevent zombie notification.
- **Dead / half-open sockets returned from pool**: Added `pingPongCheck()` in `WsPool.get()` and `WsPool.refill()` — sends WebSocket PING and awaits PONG within 3s before considering a connection alive. Dead sockets are closed instead of handed to clients.
- **CF domain hammering during rapid retries**: `Balancer.markDomainFailed()` now implements exponential backoff: effective TTL = `baseTtl * 2^(failCount-1)`, capped at 30 min. Jitter is adaptive — max ±30s but capped at half of TTL, and disabled entirely when TTL is shorter than jitter range.

### Internal
- Bumped versionCode: 2 → 3, versionName: 1.1.1 → 1.2.0
- Updated unit tests to match new `baseTtlMs` parameter name

## [1.0.0] - 2026-04-22

### Added (Initial Release)
- MTProto WebSocket Bridge Proxy for Android
- DoH resolver with Cloudflare/Google/Quad9 endpoints
- Cloudflare Worker fallback with domain pool refresh
- Parallel connect to multiple resolved IPs
- Auto Fake TLS SNI camouflage
- Connection pool (warmup, refill, age eviction)
- Foreground service with notification and background-restart option
- In-app log viewer with share/export to `.txt`
- Settings: Host, Port, Secret, DC:IP mapping, bypass toggles
- Proxy link generation (`tg://proxy?...`)
- File logging with rotation: max 2MB × 3 files
