## What's New in v1.7.2

### Critical Fix: Proxy Connection Loop

**Problem:** Proxy enters infinite reconnect loop — connects, briefly works, then resets and reconnects endlessly.

**Root causes found and fixed:**

### 1. Broken CF Domain Decoder (CRITICAL)

The Caesar cipher decoder `_dd()` in `ProxyConfig.kt` and `ProxyService.kt` used Kotlin's `%` operator, which returns **negative** values for negative operands (unlike Python's `%` which always returns non-negative). This caused 13 out of 15 CF proxy domains to decode as garbage:

```
Expected: kws2.yorokdda.co.uk
Got:      kws2.Yorokd\a.co.uk  ← backslash from negative modulo
```

DNS resolution failed for all 13 corrupted domains → balancer slowly iterated through garbage → Telegram kept reconnecting → infinite loop.

**Fix:** Replaced `%` with `Math.floorMod()` which matches Python's non-negative modulo behavior.

| File | Change |
|------|--------|
| `ProxyConfig.kt:78` | `(c.code - base - n) % 26` → `Math.floorMod(c.code - base - n, 26)` |
| `ProxyService.kt:343` | Same fix in `decodeCfDomain()` |

### 2. Network Callback Over-Triggering

`onCapabilitiesChanged()` fired on every minor network change (signal strength, validated status) — every ~60 seconds on mobile networks. Each trigger called `resetForNetworkChange()` which:
- Cleared all WS pool connections
- Cleared all cooldown/blacklist state
- Launched new warmup
- Killed active Telegram sessions → reconnect loop

**Fix:** Removed `onCapabilitiesChanged` trigger — only `onAvailable` (new network) and `onLost` (network disconnected) trigger recovery now. Also increased debounce from 2s to 5s.

---

## How to Install

1. Download `tg-ws-proxy-android.apk` below
2. Transfer to your Android device
3. Open the APK — grant installation permission
4. Tap **Start** to launch the proxy
5. Copy the link into Telegram → Settings → Proxy Settings → Add Proxy

---

## Requirements
- Android 8.0+ (API 26+)
- Telegram app

---

## Assets

| File | SHA-256 |
|------|---------|
| `tg-ws-proxy-android.apk` | `4DA12E3712B079C7EC2C51AAC5AC4F125060C92BB5ACAFD451C3D7A0C2F23D10` |