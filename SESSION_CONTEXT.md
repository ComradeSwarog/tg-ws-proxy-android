# SESSION_CONTEXT — tg-ws-proxy-android

**Repo**: https://github.com/ComradeSwarog/tg-ws-proxy-android
**Current version**: v1.5.0 stable (versionCode 6), tag `v1.5.0`
**Latest release**: https://github.com/ComradeSwarog/tg-ws-proxy-android/releases/tag/v1.5.0
**Build path**: `W:\MyProjects\tg-proxy\tg-ws-proxy-android`

## What was done recently
- **Samsung/Android 16 stability**: WakeLock (30 min) + refresh loop (every 25 min) via Kotlin Coroutine, adaptive WifiLock (`LOW_LATENCY` on Android 10+, `HIGH_PERF` fallback), `foregroundServiceType="dataSync"`, `START_REDELIVER_INTENT` for sticky restarts.
- **Auto-generated secret** on first launch (32 hex).
- **Localization**: RU/EN, auto-detect + manual switch in Bypass Settings (applies after restart).
- **In-app Help**: `?` toolbar button, localized `help_en.html` / `help_ru.html` in `assets/`.
- **Update Checker**: checks GitHub `releases/latest` API on startup (toggle in Bypass Settings). Manual check via toolbar icon.
- **DPI bypass hardening**: WS Frame Padding (self-describing `u16_be` prefix), WS Frame Fragmentation (random chunk sizes 512-4096 bytes), DoH Endpoint Rotation (Cloudflare/Google/Quad9).
- **All 18 unit tests pass**.

## Build rules (from AGENTS.md)
- JAVA_HOME: `C:\Program Files\Android\Android Studio\jbr`
- **Release**: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleRelease`
- After BUILD SUCCESSFUL, **rename**: `Copy-Item app\build\outputs\apk\release\app-release.apk app\build\outputs\apk\release\tg-ws-proxy-android.apk`
- **Debug**: `.\gradlew.bat --no-daemon assembleDebug` then rename to `tg-ws-proxy-android-debug.apk`
- ⚠️ **Every `gradlew.bat` call MUST include `--no-daemon`** or it hangs.

## Key files
| File | Purpose |
|------|---------|
| `app/build.gradle.kts` | versionCode, versionName, dependencies |
| `CHANGELOG.md` | version history |
| `release-notes-stable.md` | GitHub release notes template |
| `app/src/main/java/com/github/tgwsproxy/ProxyService.kt` | Foreground service, WakeLock, WifiLock |
| `app/src/main/java/com/github/tgwsproxy/MainActivity.kt` | UI, settings, logs |
| `app/src/main/assets/help_en.html` / `help_ru.html` | In-app help |
| `app/build/outputs/apk/release/tg-ws-proxy-android.apk` | Release artifact (2.4 MB) |

## Notes
- Do NOT commit: `release.keystore`, `build/`, `.gradle/` (already in `.gitignore`).
- `gh` CLI installed locally for releases.
- Tag `v1.5.0` already pushed; Release published at GitHub.
