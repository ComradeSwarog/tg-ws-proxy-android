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
- Bumped versionCode: 1 → 2, versionName: 1.0.0 → 1.1.1
- Added unit tests for `Balancer`, `DoHResolver`, `RawWebSocket`

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
