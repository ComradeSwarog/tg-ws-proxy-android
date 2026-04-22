# Skill: Android Gradle Build (tg-ws-proxy-android)

## When to Use

Build the Android APK (debug or release) for the `tg-ws-proxy-android` project.

## Requirements

- Working directory: project root (`tg-ws-proxy-android/`)
- JDK at: `C:\Program Files\Android\Android Studio\jbr`
- Gradle pre-installed (wrapper available)

## WARNING — PowerShell Hang

PowerShell execution of Gradle can **block forever** if the Gradle daemon asks for interactive confirmation.

**ALWAYS USE `--no-daemon`.**

## Debug Build

### Command (PowerShell)
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleDebug
```

### Command (via cmd, guaranteed non-blocking)
```powershell
cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr && gradlew.bat --no-daemon assembleDebug"
```

### Output file
```
app\build\outputs\apk\debug\app-debug.apk
```

### On success
Look for: `BUILD SUCCESSFUL`

## Release Build

### Command (PowerShell)
```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat --no-daemon assembleRelease
```

### Command (via cmd)
```powershell
cmd /c "set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr && gradlew.bat --no-daemon assembleRelease"
```

### Output file
```
app\build\outputs\apk\release\app-release.apk
```

### On success
Look for: `BUILD SUCCESSFUL`

## If Build Hangs / Times Out

1. Try with `--no-daemon` explicitly
2. If still hangs, use the `.bat` helper: `.\build_debug.bat` or `.\build_release.bat`
3. If still hangs, use the agent-safe wrapper: `.\agent_build_debug.bat`
4. As last resort, use `cmd /c` directly (the shell inside Windows `cmd` is non-interactive)

## Copy APK to Release Name

After build, rename the APK for distribution:
```powershell
Copy-Item "app\build\outputs\apk\release\app-release.apk" "app\build\outputs\apk\release\tg-ws-proxy-android-v$(Get-Date -Format 'yyyy.MM.dd').apk"
```

## Verify Build Output

```powershell
ls app\build\outputs\apk\release\
# Should show: app-release.apk (and optionally renamed copy)
```

## Troubleshooting

| Error | Fix |
|---|---|
| `JAVA_HOME is not set` | Run with `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'` |
| `Gradle daemon issues` | Add `--no-daemon` to any gradlew call |
| `PowerShell hangs` | Use `cmd /c "..."` instead of direct command |
| `OutOfMemory` | Increase `org.gradle.jvmargs=-Xmx4096m` in `gradle.properties` |