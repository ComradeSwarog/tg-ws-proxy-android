# tg-ws-proxy-android

[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](https://developer.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blueviolet)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

> Android Kotlin port of [**tg-ws-proxy**](https://github.com/Flowseal/tg-ws-proxy) by [Flowseal](https://github.com/Flowseal).
>
> A **Telegram MTProto WebSocket Bridge Proxy** with advanced DPI bypass for Android devices.

---

## What is this?

**tg-ws-proxy-android** is an Android application that creates a local MTProto proxy server on your phone. It connects to Telegram's WebSocket (WS) endpoints using DPI-bypass techniques: DoH resolution, Cloudflare fallback, parallel connections, and fake TLS handshakes.

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
| **DoH (DNS-over-HTTPS)** | Bypass DNS spoofing with encrypted DNS resolution |
| **CF Proxy Fallback** | Automatic fallback via Cloudflare Workers if direct IPs are blocked |
| **Parallel Connect** | Race multiple IPs simultaneously for sub-second handshakes |
| **Auto Fake TLS** | Automatic TLS SNI camouflage for DPI bypass |
| **Media via CF** | Route media traffic through Cloudflare to save bandwidth |
| **Pre-warmed CF Pool** | Background health-check before first real connection (< 1s cold-start) |
| **Connection Pool** | Keep-alive pool with automatic refilling and age-based eviction |
| **Foreground Service** | Persistent notification, optional background restart |
| **In-app Logs** | Live log viewer with export to `.txt` (share or save to Downloads) |
| **Proxy Link** | Auto-generate `tg://proxy` link with dd/ee secret |

---

## UI Overview

| Home Tab | Logs Tab |
|---|---|
| Proxy on/off toggle | Live log stream (2000 line buffer) |
| Connection stats | Filter by level (DBG / INF / WRN / ERR) |
| Generated proxy link | Export / Share / Clear |
| Settings (scrollable): Host, Port, Secret, DC:IP, bypass toggles | File logs with rotation (3 × 2MB files) |

> Tap **"Open in Telegram"** → Telegram opens proxy settings directly.

---

## Requirements

- Android **8.0+** (API 26+)
- Network permission (auto-granted)
- Notification permission (for foreground service, Android 13+)

---

## Download

| Variant | File | Size |
|---|---|---|
| **Release** (recommended) | `tg-ws-proxy-android.apk` | ~2.4 MB |
| Debug | `tg-ws-proxy-android-debug.apk` | ~6.8 MB |

Download from [Releases](../../releases) or build from source.

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
| **CF Fallback** | Triggered if direct WebSocket fails or DPI blocks it |
| **TCP Fallback** | Plain TCP to known DC IPs as last resort |
| **Cold-start fast lane** | Skips direct connect on first run (no CF history yet) for speed |

---

## Roadmap

- [ ] F-Droid publication
- [ ] Russian UI localisation
- [ ] WireGuard / OpenVPN tunnel integration
- [ ] QUIC transport instead of WS
- [ ] Auto-update domain pool from upstream repo

---

## Known Issues & Fixes

| Issue | Cause | Fix (commit) |
|---|---|---|
| App crash on proxy connect | NPE in `ConcurrentHashMap.put(null)` from failed parallel socket | `51b51de` |
| Service killed / restart loop | `ForegroundServiceDidNotStartInTimeException` on sticky restart | `f9d5910` |
| Hanging after hours | Global `SSLSocketFactory` poisoning JVM | `5674d6f` |
| DNS NXDOMAIN flood | System DNS caches negative entries indefinitely | `5674d6f` |
| Socket FD exhaustion | Parallel connect leaked loser's sockets | `5674d6f` |
| Telegram handshake timeout | `soTimeout=10s` too short for MTProto handshake silence | `5674d6f` |

Full changelog: [CHANGELOG.md](CHANGELOG.md)

---

## Architecture (Port Mapping)

| Original Python | Kotlin port |
|---|---|
| `proxy/tg_ws_proxy.py` | `TgWsProxy.kt` |
| `proxy/bridge.py` | Bridge + fallback logic in `TgWsProxy.kt` |
| `proxy/fake_tls.py` | `handleFakeTLS`, `FakeTlsInputStream` |
| `proxy/raw_websocket.py` | `RawWebSocket.kt` |
| `proxy/config.py` | `ProxyConfig.kt` |
| `proxy/stats.py` | `ProxyStats.kt` |
| `proxy/balancer.py` | `Balancer.kt` |
| `proxy/doh_resolver.py` | `DoHResolver.kt` |
| `proxy/utils.py` | `MtProtoConstants.kt` |

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
- CF proxy domain pool: community-curated, refreshed from upstream repo

---

> ⚠️ **Disclaimer**: This tool is for educational purposes and personal use in regions with network censorship. Users are responsible for compliance with local laws and Telegram's Terms of Service.
