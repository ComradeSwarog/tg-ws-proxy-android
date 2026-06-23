# tg-ws-proxy-android

[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blueviolet)](https://kotlinlang.org/)
[![Version](https://img.shields.io/badge/Version-1.7.0-blue)](https://github.com/ComradeSwarog/tg-ws-proxy-android/releases/latest)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> Android Kotlin port of [**tg-ws-proxy**](https://github.com/Flowseal/tg-ws-proxy) by [Flowseal](https://github.com/Flowseal).
>
> A **Telegram MTProto WebSocket Bridge Proxy** with advanced DPI bypass for Android devices.
> **Synced to upstream v1.7.2.**

---

## What is this?

**tg-ws-proxy-android** is an Android application that creates a local MTProto proxy server on your phone. It connects to Telegram's WebSocket (WS) endpoints using DPI-bypass techniques: DoH resolution, Cloudflare Worker/Proxy fallback, parallel connections, and fake TLS handshakes.

### Why not just use the original Python proxy?

The original [**tg-ws-proxy**](https://github.com/Flowseal/tg-ws-proxy) runs as a Python CLI on desktop. This project:
- **Ported to Android** using Kotlin + Android SDK 34
- **Runs as a foreground service** with persistent notification
- **GUI settings** instead of editing text files
- **In-app log viewer** with export/sharing
- **Optimised for mobile network** — less battery usage than Python + Termux
- **Fixed critical stability issues** discovered during real-world testing

---

## Features

| Feature | Description |
|---|---|
| **MTProto ↔ WebSocket Bridge** | Transparent bridge between Telegram app and Telegram DCs |
| **Cloudflare Worker Support** | Free alternative to CF Proxy — deploy a Worker on your CF account, no domain purchase needed. Pre-warmed pool per DC × worker domain |
| **Multi-Domain CF Proxy** | Comma-separated custom CF-proxy domains. Balancer rotates domains with per-DC affinity |
| **Simplified Priority Chain** | Fixed fallback order: CF Worker → CF Proxy → Direct WS → TCP (no config toggle — matches upstream v1.7.0+) |
| **Parallel Race (Direct vs CF)** | On first connect, races direct WS and CF fallback simultaneously — winner bridges instantly |
| **CF-Proven Mode** | After 2+ CF successes, skips slow direct attempts for 5 min; auto-recovers when network improves |
| **O(N) Packet Splitter** | Offset-pointer MTProto packet splitting — no O(N²) array shifts (matches upstream optimization) |
| **DoH (DNS-over-HTTPS)** | Bypass DNS spoofing with encrypted DNS resolution |
| **Parallel Connect** | Race multiple IPs simultaneously for sub-second handshakes |
| **Auto Fake TLS** | Automatic TLS SNI camouflage for DPI bypass |
| **Media via CF** | Route media traffic through Cloudflare to save bandwidth |
| **Frame Padding + DoH Rotation** | Optional WS padding + cyclic DoH provider rotation |
| **Pre-warmed CF Pool** | Background health-check before first real connection (< 3s cold-start) |
| **Connection Pool** | Keep-alive pool with automatic refilling and age-based eviction |
| **Deadlock-free TLS** | Separate thread pools for parallel connect and TLS handshakes — no thread pool deadlock |
| **Auto Network Recovery** | Detects WiFi/mobile network switches and instantly resets stale state (cooldowns, pool, blacklist, WifiLock) |
| **Foreground Service Hardening** | `dataSync` type + `WakeLock` + `WifiLock` — Samsung/Android 16 won't throttle network I/O after 30 min |
| **WakeLock Refresh** | Re-acquires wake lock every 25 min before Samsung's timeout expires |
| **Auto CF Domain Refresh** | Fetches updated CF proxy domain list from upstream repo every hour |
| **In-app Logs** | Live log viewer with export to `.txt` (share or save to Downloads) |
| **Auto-generated Secret** | Fresh 32-hex secret generated on first launch. Tap the inline refresh icon to regenerate anytime |
| **RU / EN Localization** | Auto-detect system language; manual switcher in Bypass Settings |
| **In-app Help** | Localized help screen with full feature docs |
| **Auto-Update** | Downloads and installs APK directly from GitHub Releases |

---

## Screenshots

| English | Русский |
|---|---|
| ![App EN](screenshots/app-screenshot-en.jpg) | ![App RU](screenshots/app-screenshot-ru.jpg) |

---

## Download

| Variant | File |
|---|---|
| **Release** (recommended) | `tg-ws-proxy-android.apk` |
| Debug | `tg-ws-proxy-android-debug.apk` |

Download from [Releases](../../releases).

### SHA-256 Checksum

```
DF29B3AFF16E703043126185472909F316D3BF2F55AFE54E2B61D64F7A71AB34
```

Verify after download:
```powershell
# Windows PowerShell
Get-FileHash -Path "tg-ws-proxy-android.apk" -Algorithm SHA256

# Linux / macOS
sha256sum tg-ws-proxy-android.apk
```

---

## Build from Source

```bash
# Debug APK
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleDebug

# Release APK (requires release.keystore or use debug signing)
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleRelease
```

Outputs:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/tg-ws-proxy-android.apk`

---

## Bypass Modes

| Mode | When it triggers |
|---|---|
| **Direct WS** | Connects to `kws{dc}.web.telegram.org` via DoH + parallel TCP |
| **Parallel Race** | First connect: races direct WS vs CF fallback simultaneously, winner bridges |
| **CF Worker** | Connects to user-deployed Cloudflare Worker at `wss://<worker-domain>/apiws?dst=<DC_IP>&dc=<N>` |
| **CF Proxy Fallback** | Connects to `kws{dc}.<cf-domain>` — uses Balancer with per-DC domain affinity |
| **CF-Proven Fast Path** | After 2+ CF successes for a DC, skips direct entirely for 5 min |
| **TCP Fallback** | Plain TCP to known DC IPs as last resort |

### Settings — Bypass Settings Card

| Setting | Default | Description |
|---|---|---|
| DoH resolving | ✅ on | Encrypted DNS resolution |
| Auto Fake TLS | ✅ on | SNI camo when direct IP blocked |
| Parallel connect | ✅ on | Multi-IP race for fast fallback |
| CF Proxy fallback | ✅ on | Enable Cloudflare-based fallback |
| CF-proxy custom domains | *(empty)* | Comma-separated user domains (replaces auto-pool) |
| CF Worker domains | *(empty)* | Comma-separated Cloudflare Worker domains |
| Media via CF | ✅ on | Route downloads through CF |
| WS frame padding | ⬜ off | Random WS frame padding (DPI obfuscation) |
| Rotate DoH providers | ✅ on | Cycle Cloudflare → Google → Quad9 |
| Work in background | ✅ on | Keep proxy alive after UI close |
| Language | Auto | Auto / Russian / English |
| Auto check for updates | ✅ on | Check GitHub Releases on startup |

---

## Roadmap

| Feature | Status | Notes |
|---|---|---|
| **QUIC transport instead of WS** | 🔮 **Research** | QUIC (UDP-based, HTTP/3) is harder for DPI to fingerprint and has faster 0-RTT handshake. Telegram does not yet expose QUIC for MTProto WebSocket; experimental if backend support appears. |
| **WireGuard / OpenVPN tunnel integration** | 🔮 **Research** | Would turn the app into a system-level VPN tunnel (`VpnService`) so all traffic (not just Telegram) is bypassed. This is a major architecture shift — evaluate if out of scope. |

**Not on the roadmap:** F-Droid publication, iOS port.

---

## Known Issues & Fixes

| Issue | Cause | Fix (commit) |
|---|---|---|
| App crash on proxy connect | NPE in `ConcurrentHashMap.put(null)` from failed parallel socket | `51b51de` |
| Service killed / restart loop | `ForegroundServiceDidNotStartInTimeException` on sticky restart | `f9d5910` |
| Proxy hanging after hours | `CF_SUCCESS_THRESHOLD=1` permanently blocks direct WS after 1 CF success; dead sockets leaked from pool; `soTimeout=0` causes infinite read hangs | Remove permanent CF lock-in (v1.6.0) |
| Samsung/Android 16 freeze | Device Care throttles foreground service network I/O after ~30 min | WakeLock refresh + `dataSync` foreground service type |
| Hanging after hours | Global `SSLSocketFactory` poisoning JVM | `5674d6f` |
| DNS NXDOMAIN flood | System DNS caches negative entries indefinitely | `5674d6f` |
| Socket FD exhaustion | Parallel connect leaked loser's sockets | `5674d6f` |
| **Thread pool deadlock** | TLS handshake and parallel connect shared same 8-thread pool → all blocked | Separate `tlsHandshakeExecutor` (v1.6.0) |
| **Slow first connect (8–20s)** | Excessive timeouts: CF 6s, DoH 8s, race 6.5s, TLS 8s + 150ms CF delay | All reduced: CF 3s, DoH 3s, race 4.5s, TLS 5s (v1.6.0) |

Full changelog: [CHANGELOG.md](CHANGELOG.md)

---

## Architecture (Port Mapping)

| Original Python | Kotlin port |
|---|---|
| `proxy/tg_ws_proxy.py` | `TgWsProxy.kt` |
| `proxy/bridge.py` | `MsgSplitter.kt` + bridge/fallback logic in `TgWsProxy.kt` |
| `proxy/pool.py` | `WsPool` + `CfWorkerPool` in `TgWsProxy.kt` |
| `proxy/fake_tls.py` | `handleFakeTLS`, `FakeTlsInputStream` in `TgWsProxy.kt` |
| `proxy/raw_websocket.py` | `RawWebSocket.kt` |
| `proxy/config.py` | `ProxyConfig.kt` |
| `proxy/stats.py` | `ProxyStats.kt` |
| `proxy/balancer.py` | `Balancer.kt` |
| `proxy/utils.py` | `MtProtoConstants.kt` |
| `ui/ctk_tray_ui.py` | `MainActivity.kt` + `HelpActivity.kt` |
| `utils/update_check.py` | `UpdateChecker.kt` |
| `utils/tray_common.py` | `ProxyService.kt` |

---

## Contributing

1. Fork this repository
2. Create a feature branch (`git checkout -b feat/my-feature`)
3. Commit changes (`git commit -am 'feat: add new feature'`)
4. Push to GitHub (`git push origin feat/my-feature`)
5. Open a Pull Request

### For AI Agents / Automated Contributions

See [`AGENTS.md`](AGENTS.md) for build instructions, naming conventions, and critical rules about Gradle daemon and APK filenames.

---

## License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE).

Based on [**tg-ws-proxy**](https://github.com/Flowseal/tg-ws-proxy) by [Flowseal](https://github.com/Flowseal), also under MIT License.

---

## Acknowledgements

- Original idea and protocol design: **[Flowseal](https://github.com/Flowseal)** and contributors to [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)
- MTProto protocol: Telegram Messenger LLP
- CF proxy domain pool: community-curated, auto-refreshed from upstream repo every hour

---

> ⚠️ **Disclaimer**: This tool is for educational purposes and personal use in regions with network censorship. Users are responsible for compliance with local laws and Telegram's Terms of Service.
