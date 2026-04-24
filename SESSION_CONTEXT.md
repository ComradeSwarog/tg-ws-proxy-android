# SESSION_CONTEXT — tg-ws-proxy-android

**Repo**: https://github.com/ComradeSwarog/tg-ws-proxy-android
**Current version**: v1.6.0-beta (release candidate on `beta` branch, versionCode 7)
**Latest release**: https://github.com/ComradeSwarog/tg-ws-proxy-android/releases/tag/v1.6.0-beta
**Build path**: `W:\MyProjects\tg-proxy\tg-ws-proxy-android`
**Branch for experiments**: `beta` (do NOT merge to `master` until user approves)

---

## Session Summary
This session completed a major v1.6.0-beta refactor to fix proxy hanging after hours and to dramatically improve first-connect speed on DPI-blocked networks.

### Critical Bugs Fixed
1. **Hanging / stopped connecting after hours** (root: infinite `soTimeout`, broken pool health check, double restart loop, permanent CF lock-in).
2. **Hotfix beta3**: missing `doFallback()` call after direct WS failure caused every connection to silently die.
3. **Beta4 race slow first connect**: race waited for direct TLS handshake which hung silently (20s timeout). Fixed with hard race cap (6.5s) + TLS handshake timeout (8s).

---

## Architecture Decisions (carry forward)

### Connection Strategy (TgWsProxy.kt)
1. **Pool hit** → fast path, immediate bridge.
2. **CF-proven active** (`now < cfProvenUntil[dcKey]`) → skip direct entirely, go straight to `doFallback()`.
3. **Otherwise** → `raceConnection()` launches:
   - `directJob` tries `connectRawWsEnhanced()` (Direct WS with adjusted timeout)
   - `cfJob` starts after `delay(150)` and tries CF fallback
   - `withTimeout(6500)` hard cap: if nothing wins in 6.5s, both jobs cancelled
4. **Race lost** → `dcFailUntil[dcKey] = now + 60_000`, then `doFallback()`.

### CF-Proven Mode
- `CF_PROVEN_MIN = 2` successes required.
- `CF_PROVEN_TTL_MS = 300_000L` (5 min window).
- Background `periodicCleanupLoop()` decays `cfSuccessCount` by half every 5 min to auto-recover when network improves.

### RawWebSocket.kt
- `soTimeout = 70_000` (was 0/infinite) → prevents silent hangs during Doze.
- TLS `startHandshake()` wrapped in `Future` with `connectExecutor.get(timeout)` to prevent infinite TLS stalls.
- `pingPongCheck()` simplified from broken blocking `recv()` to `!isClosed`.
- DoH + parallel IP connect preserved.

### ProxyService.kt
- `scheduleRestart()` removed from `onDestroy()` to prevent double-restart loop.
- WakeLock (30 min) + adaptive WifiLock + `foregroundServiceType="dataSync"` preserved from earlier work.

### Balancer.kt
- CF domain pool with blacklist TTL (2 min for rate-limit, 10 min for DNS failures).
- `refreshCfDomainsIfNeeded()` refreshes domain list from GitHub on service start.

---

## Current State
- `beta` branch is ahead of `master` by ~5 commits.
- Pre-release tag `v1.6.0-beta` is force-updated on every new beta build.
- **User confirmed beta4-hf works**: fast reconnect on app restart, first connect improved (~5-6s on this particular network, down from 20s).
- Pre-release APK: `app/build/outputs/apk/debug/tg-ws-proxy-android-beta.apk`

---

## Open Items (for next session)

### ⏳ Required before Stable v1.6.0
- [ ] User confirms beta4-hf is stable over **24+ hours** of continuous use on mobile data.
- [ ] Merge `beta` → `master`, bump versionCode 7 → 8, publish stable release with `release-notes-stable.md`.

### 🚀 Potential Enhancements
- **Prewarm on boot / on network change**: start a background `testCfForDc()` when WiFi→mobile transition detected.
- **Adaptive race timeout**: instead of fixed 6.5s, measure direct WS latency history and cap based on p50/p95.
- **Connection pooling for CF**: currently pools only direct WS connections. Could pool CF connections too.
- **DoH-over-HTTPS via pinned IP**: some DoH endpoints themselves are blocked; add fallback pinned IPs.

---

## Build Rules

```powershell
cd W:\MyProjects\tg-proxy\tg-ws-proxy-android

# Debug (for beta APK)
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleDebug
Copy-Item app\build\outputs\apk\debug\app-debug.apk app\build\outputs\apk\debug\tg-ws-proxy-android-beta.apk

# Release (for stable)
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleRelease
Copy-Item app\build\outputs\apk\release\app-release.apk app\build\outputs\apk\release\tg-ws-proxy-android.apk
```

⚠️ **Every `gradlew.bat` call MUST include `--no-daemon`** or it hangs.

---

## Key Files
| File | Purpose |
|------|---------|
| `app/src/main/java/com/github/tgwsproxy/TgWsProxy.kt` | Main proxy — **raceConnection, cfproxyConnectOnly, doFallback, bridgeWsReencrypt** |
| `app/src/main/java/com/github/tgwsproxy/RawWebSocket.kt` | WS client — **TLS handshake timeout, soTimeout, DoH/parallel connect** |
| `app/src/main/java/com/github/tgwsproxy/ProxyService.kt` | Foreground service, no double restart |
| `app/src/main/java/com/github/tgwsproxy/Balancer.kt` | CF domain pool, rotation, blacklist |
| `app/src/main/java/com/github/tgwsproxy/ProxyConfig.kt` | Config: `dcRedirects`, `fallbackCfproxy`, timeouts |
| `app/src/main/java/com/github/tgwsproxy/AppLogger.kt` | File + memory logging |
| `release-notes-beta.md` | GitHub pre-release notes template |

## Release Workflow
```powershell
# 1. Build debug
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleDebug
Copy-Item app\build\outputs\apk\debug\app-debug.apk app\build\outputs\apk\debug\tg-ws-proxy-android-beta.apk

# 2. Commit & push beta branch
git add -A
git commit -m "..."
git push origin beta

# 3. Force-update pre-release tag
git tag -d v1.6.0-beta
git tag -a v1.6.0-beta -m "Pre-release v1.6.0-betaN"
git push origin v1.6.0-beta --force

# 4. Recreate GitHub pre-release
gh release delete v1.6.0-beta --yes --repo ComradeSwarog/tg-ws-proxy-android
gh release create v1.6.0-beta --repo ComradeSwarog/tg-ws-proxy-android \
  --prerelease --title "v1.6.0-betaN — ..." \
  --notes-file release-notes-beta.md \
  "app\build\outputs\apk\debug\tg-ws-proxy-android-beta.apk"
```

## Testing Checklist (re-run after any change)
- [ ] Unit tests pass: `gradlew.bat --no-daemon test`
- [ ] First connect on mobile data (blocked) → under 5 seconds
- [ ] Reopen Telegram → immediate reconnect (<1s)
- [ ] Leave proxy on for 30+ min → no hangs, messages keep flowing
- [ ] Check logs for `"RACE WON"`, `"CF proven active"`, `"WS done"` correctness

## Known Limitations
- **First connect on some networks still ~5-6s**: this is the time it takes CF domains to resolve + TLS + WS handshake. The 6.5s race hard cap prevents it from ballooning beyond that.
- **TCP fallback never succeeds** on this user's network (all TCP to Telegram IPs timeout at 3s). CF fallback is the only viable path.
- **CF domain DNS failures**: some domains in the pool are dead (DNS NXDOMAIN). Balancer blacklists them, but first-time startup may try a few dead ones.
