# AGENTS.md — tg-ws-proxy-android

## Project Overview

Android Kotlin port of [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
A Telegram MTProto WebSocket Bridge Proxy with DPI bypass (DoH, CF Worker/Proxy fallback, parallel connect, auto Fake TLS).
**Synced to upstream v1.7.2.**

## Prerequisites

- **JAVA_HOME**: `C:\Program Files\Android\Android Studio\jbr`
- **Build tool**: Gradle wrapper (`gradlew.bat`)
- **Platform**: Windows (PowerShell)
- **GitHub CLI**: `gh` (for release management)
- **Upstream reference**: `W:\MyProjects\tg-proxy\tg-ws-proxy-main` (clone of Flowseal/tg-ws-proxy)

## Critical Rules

### 1. NEVER Hang on Gradle Build (Daemon Prompt)

**PROBLEM:** Gradle daemon may prompt interactively for JVM options. OpenCode's PowerShell shell hangs on such prompts and never returns.

**REQUIRED:** Every `gradlew.bat` call MUST include `--no-daemon`.

**CORRECT:**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleDebug
```

**WRONG (DO NOT USE):**
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat assembleDebug       # ← hangs!
```

**ALTERNATIVE (use cmd if PowerShell fails):**
```powershell
cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr && gradlew.bat --no-daemon assembleDebug"
```

### 2. Build via Gradle (not .bat scripts)

Agents MUST NOT rely on `.bat` build scripts — use Gradle wrapper directly:
- **Debug**: `$env:JAVA_HOME = '...'; .\gradlew.bat --no-daemon assembleDebug`
- **Release**: `$env:JAVA_HOME = '...'; .\gradlew.bat --no-daemon assembleRelease`

### 3. Avoid Interactive Shell Commands

- NEVER use `call gradlew.bat` inside another .bat — can block on daemon fork
- NEVER use `pause` in scripts for agent execution
- NEVER use `start /wait` with interactive programs
- If a command may prompt, add `echo y |` prefix or use `--no-daemon` / `--batch-mode`

### 4. Build Workflow

**Release APK:**
```powershell
cd W:\MyProjects\tg-proxy\tg-ws-proxy-android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleRelease
# Gradle output: app\build\outputs\apk\release\app-release.apk
# MUST rename/copy to:
Copy-Item app\build\outputs\apk\release\app-release.apk app\build\outputs\apk\release\tg-ws-proxy-android.apk
```

**Debug APK:**
```powershell
cd W:\MyProjects\tg-proxy\tg-ws-proxy-android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleDebug
# Gradle output: app\build\outputs\apk\debug\app-debug.apk
# MUST rename/copy to:
Copy-Item app\build\outputs\apk\debug\app-debug.apk app\build\outputs\apk\debug\tg-ws-proxy-android-debug.apk
```

### APK Naming Convention (enforced)

| Variant | Gradle default | Final required name |
|---|---|---|
| Release | `app-release.apk` | `tg-ws-proxy-android.apk` |
| Debug   | `app-debug.apk`   | `tg-ws-proxy-android-debug.apk` |

Agents **must** copy the default Gradle artifact to the required filename immediately after `BUILD SUCCESSFUL`.

### 5. Release Workflow

After a successful build, the full release flow is:

```powershell
# 1. Build
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleRelease

# 2. Copy to final name
Copy-Item app\build\outputs\apk\release\app-release.apk app\build\outputs\apk\release\tg-ws-proxy-android.apk

# 3. Verify APK exists
Get-FileHash -Path "app\build\outputs\apk\release\tg-ws-proxy-android.apk" -Algorithm SHA256

# 4. Update release notes: release-notes-stable.md (include SHA-256 in Assets table)

# 5. Commit, tag, push
git add -A
git commit -m "<conventional-commit-message>"
git push origin master
git tag -a v<version> -m "<tag message>"
git push origin v<version>

# 6. Create GitHub release with APK attached
gh release create v<version> --repo ComradeSwarog/tg-ws-proxy-android --title "<title>" --notes-file release-notes-stable.md "app\build\outputs\apk\release\tg-ws-proxy-android.apk"
```

### 6. Files to Never Commit

Already in `.gitignore`, but remember:
- `release.keystore` (secrets)
- `*.bat` build scripts (local tooling)
- `.gradle/`, `build/`, `app/build/`

### 7. Conventional Commits

Use standard prefix format:
- `feat(upstream):` — porting features from upstream Flowseal/tg-ws-proxy
- `feat:` — new Android-only features
- `fix:` — bug fixes
- `docs:` — documentation changes
- `chore:` — build, CI, config changes

## Key Files Map

| File | Purpose |
|---|---|
| `app/src/main/java/com/github/tgwsproxy/TgWsProxy.kt` | Main proxy server: `handleClient`, `WsPool`, `CfWorkerPool`, `doFallback`, bridge/fallback/race logic |
| `app/src/main/java/com/github/tgwsproxy/RawWebSocket.kt` | Low-level WS client: TLS + WS handshake, DoH/parallel connect, **separate `tlsHandshakeExecutor`** |
| `app/src/main/java/com/github/tgwsproxy/MsgSplitter.kt` | O(N) MTProto packet splitter using offset-pointer (ported from `proxy/bridge.py`) |
| `app/src/main/java/com/github/tgwsproxy/Balancer.kt` | CF proxy domain balancer: per-DC affinity + randomized rotation |
| `app/src/main/java/com/github/tgwsproxy/MtProtoConstants.kt` | MTProto protocol constants (`PROTO_TAG_*`, `PROTO_*_INT`, `DC_DEFAULT_IPS`) |
| `app/src/main/java/com/github/tgwsproxy/ProxyConfig.kt` | Configuration: `cfproxyUserDomains`, `cfproxyWorkerDomains`, `coerceDomainList()`, 15 default domains |
| `app/src/main/java/com/github/tgwsproxy/ProxyService.kt` | Foreground service: manages `TgWsProxy` lifecycle, CF domain refresh, wakelocks |
| `app/src/main/java/com/github/tgwsproxy/DoHResolver.kt` | DoH resolver with endpoint rotation (Cloudflare → Google → Quad9) |
| `app/src/main/java/com/github/tgwsproxy/ProxyStats.kt` | Thread-safe connection/byte counters |
| `app/src/main/java/com/github/tgwsproxy/MainActivity.kt` | Main UI: proxy tab (settings, stats, start/stop) + logs tab |
| `app/src/main/java/com/github/tgwsproxy/HelpActivity.kt` | In-app help screen (EN + RU) |
| `app/src/main/java/com/github/tgwsproxy/AppLogger.kt` | File + memory logging with rotation |
| `app/src/main/java/com/github/tgwsproxy/UpdateChecker.kt` | GitHub Releases API check with ETag caching |
| `app/src/main/java/com/github/tgwsproxy/LocaleUtils.kt` | Runtime language switching (Auto/RU/EN) |
| `app/src/main/res/layout/activity_main.xml` | Main activity layout (proxy + logs tabs) |
| `app/src/main/res/layout/activity_help.xml` | Help screen layout |
| `app/src/main/res/values/strings.xml` | English string resources |
| `app/src/main/res/values-ru/strings.xml` | Russian string resources |
| `app/build.gradle.kts` | Build config (`versionCode`, `versionName`) |
| `gradle.properties` | `org.gradle.daemon=false` (**critical**) |
| `release-notes-stable.md` | Release notes template for GitHub Releases |
| `AGENTS.md` | This file — agent instructions |
| `README.md` | Public project README |
| `CHANGELOG.md` | Historical changelog |

## Architecture: Upstream Port Mapping

| Original Python (`tg-ws-proxy-main/proxy/`) | Kotlin port |
|---|---|
| `proxy/tg_ws_proxy.py` | `TgWsProxy.kt` (main accept loop + handler) |
| `proxy/bridge.py` | `MsgSplitter.kt` + bridge/fallback/race in `TgWsProxy.kt` |
| `proxy/pool.py` | `WsPool` + `CfWorkerPool` inner classes in `TgWsProxy.kt` |
| `proxy/raw_websocket.py` | `RawWebSocket.kt` |
| `proxy/config.py` | `ProxyConfig.kt` |
| `proxy/balancer.py` | `Balancer.kt` |
| `proxy/utils.py` | `MtProtoConstants.kt` |
| `proxy/fake_tls.py` | FakeTLS logic in `TgWsProxy.kt` |
| `proxy/stats.py` | `ProxyStats.kt` |
| `ui/ctk_tray_ui.py` | `MainActivity.kt` + `HelpActivity.kt` |
| `utils/update_check.py` | `UpdateChecker.kt` |
| `utils/tray_common.py` | `ProxyService.kt` (config loading/application) |
| `utils/default_config.py` | `ProxyConfig.kt` defaults + `coerceDomainList()` |

## Key Architecture Decisions

### 1. Separate TLS Handshake Executor
`RawWebSocket.kt` uses two thread pools:
- `connectExecutor` (8 fixed threads) — TCP connect + WS upgrade
- `tlsHandshakeExecutor` (cached) — TLS `startHandshake()`

**Why:** Parallel connect submits TLS work and calls `Future.get()`. If both pools were shared, all 8 threads would be blocked waiting for TLS futures with no thread available to run them — deadlock. The separation ensures TLS handshakes always have threads to execute.

### 2. O(N) MsgSplitter
`MsgSplitter.kt` uses offset-pointer walking instead of front-deletion on byte arrays. Original approach: `del buf[:n]` shifts remaining bytes → O(N²) for many small packets. New approach: track `pos` offset, single `copyOfRange(pos, size)` after all packets split → O(N).

### 3. Fallback Priority Chain
Fixed order: **CF Worker → CF Proxy → Direct WS → TCP**. No priority toggle — matches upstream v1.7.0+ behavior. Parallel fallback launches worker + CF + TCP concurrently (TCP delayed 250ms).

### 4. CF Worker Pool
Pre-warmed connections per (DC × worker_domain) key, same `poolSize` as regular WS pool. Uses `GET /apiws?dst=<target_ip>&dc=<N>` path. Worker domains are shuffled before each fallback attempt.

### 5. Config Format
`ProxyConfig.cfproxyUserDomains` and `cfproxyWorkerDomains` are `List<String>`. The `coerceDomainList()` function handles comma/space/semicolon-separated input from UI text fields or CLI args, with domain validation following upstream rules (label length, TLD, character set).

## Testing After Build

After `BUILD SUCCESSFUL`, verify APK exists:
```powershell
ls app\build\outputs\apk\release\tg-ws-proxy-android.apk
# or
ls app\build\outputs\apk\debug\tg-ws-proxy-android-debug.apk
```

No automated tests configured — this project relies on manual testing on physical Android devices.

## Updating Upstream Reference

When new upstream versions are released (Flowseal/tg-ws-proxy):

```powershell
# Replace local reference with latest tag
Remove-Item -Recurse -Force "W:\MyProjects\tg-proxy\tg-ws-proxy-main"
git clone --depth 1 --branch v<version> https://github.com/Flowseal/tg-ws-proxy.git "W:\MyProjects\tg-proxy\tg-ws-proxy-main"
```

Always read `proxy/pool.py`, `proxy/bridge.py`, `proxy/config.py`, `proxy/tg_ws_proxy.py`, `ui/ctk_tray_ui.py`, and `utils/default_config.py` first to understand what changed before porting.
