# AGENTS.md — tg-ws-proxy-android

## Project Overview

Android Kotlin port of [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).
A Telegram MTProto WebSocket Bridge Proxy with DPI bypass (DoH, CF fallback, parallel connect, auto Fake TLS).

## Prerequisites

- **JAVA_HOME**: `C:\Program Files\Android\Android Studio\jbr`
- **Build tool**: Gradle wrapper (`gradlew.bat`)
- **Platform**: Windows (PowerShell)

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

**Debug APK:**
```powershell
cd W:\MyProjects\tg-proxy\tg-ws-proxy-android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleDebug
# Output: app\build\outputs\apk\debug\app-debug.apk
```

**Release APK:**
```powershell
cd W:\MyProjects\tg-proxy\tg-ws-proxy-android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleRelease
# Output: app\build\outputs\apk\release\app-release.apk
```

### 5. Files to Never Commit

Already in `.gitignore`, but remember:
- `release.keystore` (secrets)
- `*.bat` build scripts (local tooling)
- `.gradle/`, `build/`, `app/build/`

## Key Files Map

| File | Purpose |
|---|---|
| `app/src/main/java/com/github/tgwsproxy/TgWsProxy.kt` | Main proxy server |
| `app/src/main/java/com/github/tgwsproxy/RawWebSocket.kt` | WS client with DoH/parallel connect |
| `app/src/main/java/com/github/tgwsproxy/ProxyService.kt` | Foreground service |
| `app/src/main/java/com/github/tgwsproxy/MainActivity.kt` | UI (tabs, settings, logs) |
| `app/src/main/java/com/github/tgwsproxy/AppLogger.kt` | File + memory logging with rotation |
| `app/src/main/res/layout/activity_main.xml` | Main layout |
| `app/build.gradle.kts` | Build config |
| `gradle.properties` | `org.gradle.daemon=false` (critical) |

## Testing After Build

After `BUILD SUCCESSFUL`, verify APK exists:
```powershell
ls app\build\outputs\apk\debug\app-debug.apk
# or
ls app\build\outputs\apk\release\app-release.apk
```