## What's New

### Ported from upstream tg-ws-proxy v1.6.4

- **Redirect-aware WS error handling** — `RawWebSocket.connect()` now tracks HTTP status codes and whether the response was a redirect (301/302/303/307/308). This enables smarter failure decisions instead of treating all WS failures equally.
- **WS blacklist** — restored the `wsBlacklist` feature that was lost during v1.6.0 refactoring. When all Telegram domains return HTTP redirects for a DC, that DC is permanently blacklisted for WS and falls back to CF/TCP immediately on subsequent connections.
- **DC fail cooldown 30s** — reduced from 60s to 30s to match upstream. Failed DCs are retried sooner on networks where DPI is intermittent.

### Simplified Balancer

Removed domain blacklisting and exponential backoff from `Balancer.kt` to match upstream behaviour. The blacklist could block perfectly working domains for up to 30 minutes after a network switch, while the original Python Balancer rotates domains freely and lets the connection code decide which ones work.

### Technical Details

- `RawWebSocket.lastStatusCode` / `RawWebSocket.lastWasRedirect` — static fields populated on every WS handshake attempt
- `TgWsProxy.connectRawWsEnhanced()` accumulates redirect flags across domain attempts
- `TgWsProxy.cfproxyConnectOnly()` / `cfproxyFallback()` / `testCfForDc()` — removed `markDomainFailed()` calls
- `Balancer` — removed `domainBlacklist`, `domainFailCount`, `markDomainFailed()`, `resetBlacklist()`
- `ProxyService.onNetworkChanged()` — removed `balancer.resetBlacklist()` call (method no longer exists)

---

## Full Changelog (v1.6.2 → v1.6.3)

- Added `RawWebSocket.lastStatusCode` and `lastWasRedirect` static fields
- `connectRawWsEnhanced()` now logs HTTP status and redirect flags per domain attempt
- Restored `wsBlacklist` check in `handleClient()` — blacklisted DCs skip race and go straight to fallback
- DC fail cooldown: 60s → 30s (matches upstream `DC_FAIL_COOLDOWN = 30.0`)
- Simplified `Balancer.kt` — removed domain blacklist, fail count, `markDomainFailed()`, `resetBlacklist()`
- Removed all `markDomainFailed()` calls from `cfproxyConnectOnly()`, `cfproxyFallback()`, `testCfForDc()`
- Removed `balancer.resetBlacklist()` call from `ProxyService.onNetworkChanged()`

---

## How to Install

1. Download `tg-ws-proxy-android.apk` below.
2. Transfer to your Android device.
3. Open the APK — grant installation permission.
4. Tap **Start** to launch the proxy.
5. Copy the link into Telegram → Settings → Proxy Settings → Add Proxy.

---

## Requirements
- Android 8.0+ (API 26+)
- Telegram app

---

## Assets

| File | SHA-256 |
|------|---------|
| `tg-ws-proxy-android.apk` | `294A14CB67DA572CC61A145818F0968174EF708C0DF87727226F76E97A2669B3` |
