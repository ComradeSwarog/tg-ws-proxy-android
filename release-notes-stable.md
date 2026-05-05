## What's New

### Auto-Update: Download & Install from GitHub

The update checker now supports **one-tap download and install** of new APK versions directly from GitHub Releases:

- **Bypasses cache on manual check** — the "Check for update" button now always hits the API, never returns a stale cached result
- **Downloads APK from release assets** — automatically finds the `.apk` file attached to the release
- **Shows download progress** with percentage indicator
- **Launches system package installer** via `FileProvider` once download completes
- **Three-button dialog**: "Download & Install", "Open release page", "Later"

### Fixed

- **Update checker always returned cached result** — manual "Check for update" was blocked by the 1-hour cache. Now uses `force = true` to bypass cache on manual checks.
- **APK download URL extraction** — added GitHub release assets parsing to get the direct APK download link.

---

## Technical Details

- Added `REQUEST_INSTALL_PACKAGES` permission to `AndroidManifest.xml`
- `FileProvider` uses `cache-path` for APK storage (already configured)
- Download runs on a coroutine with `Dispatchers.IO`
- Uses `java.net.HttpURLConnection` for download (no extra dependencies)

---

## Full Changelog (v1.6.1 → v1.6.2)

- `UpdateChecker.check()` now accepts `force` parameter (manual checks bypass cache)
- `UpdateChecker` extracts APK download URL from GitHub release assets
- `MainActivity.showUpdateDialog()` — three buttons: Download, Open page, Later
- `MainActivity.downloadAndInstallApk()` — streams APK to cache dir with progress dialog
- `MainActivity.installApk()` — launches `ACTION_VIEW` intent via `FileProvider`
- New string resources: `update_downloading`, `update_downloading_msg`, `update_download_error`, `update_install_error`, `update_open_page`, `update_download`
- Updated EN and RU string resources

---

## How to Install

> ⚠️ **Google Play Protect warning** is expected for open-source apps. Tap **"Install anyway"** (or **"More details → Install anyway"**).

1. Download `tg-ws-proxy-android.apk` below.
2. Transfer to your Android device (Telegram Saved Messages, USB, or cloud storage).
3. Open the APK — Android will prompt to allow installation from this source. Grant permission.
4. Install and open the app.
5. Tap **Start** to launch the proxy.
6. Copy the generated link and paste it into Telegram → Settings → Data and Storage → Proxy Settings → Add Proxy.

---

## Requirements
- Android 8.0+ (API 26+)
- Telegram app (any client that supports MTProto proxy links)

---

## Assets

| File | SHA-256 |
|------|---------|
| `tg-ws-proxy-android.apk` | `F54601BFEFBEA9D6DD268472E464D1F3CA74EFF56F15B324A0023E997102CE91` |

---

## Links

- [Full Changelog](https://github.com/ComradeSwarog/tg-ws-proxy-android/blob/master/CHANGELOG.md)
- [Source Code](https://github.com/ComradeSwarog/tg-ws-proxy-android)
- [Report an Issue](https://github.com/ComradeSwarog/tg-ws-proxy-android/issues/new)
