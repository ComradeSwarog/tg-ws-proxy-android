# tg-ws-proxy-android

Telegram MTProto WebSocket Bridge Proxy для Android.  
Порт [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) на Kotlin.

## Возможности

- **MTProto — WebSocket мост** к серверам Telegram
- **DoH (DNS-over-HTTPS)** — обход блокировки DNS
- **CF Proxy Fallback** — fallback через Cloudflare Worker (автоматически при недоступности прямых IP)
- **Parallel Connect** — параллельное подключение к нескольким IP для ускорения соединения
- **Auto Fake TLS** — автоматическая маскировка SNI
- **Media через CF** — медиа-файлы через Cloudflare для экономии трафика
- **Pre-warmed CF Pool** — фоновое тестирование CF перед первым подключением, подключение менее 1 секунды
- **Session Balancing** — поддержка до 10 параллельных соединений, быстрое переподключение
- **Foreground Service** с уведомлением и опцией «Работать в фоне»
- **Экспорт логов** в файл (.txt) для анализа

## Скриншоты / UI

```
[Статус прокси]
[Включить / Выключить]          ← запускает foreground service
[Открыть в Telegram]             ← 64dp для удобства
[Настройки (scroll)]
    Host / Port / Secret / DC:IP
    Расширенные настройки обхода (все ON по умолчанию)
    [Сохранить настройки]
```

## Требования

- Android 8.0 (API 26) и выше
- Доступ к сети

## Сборка

```bash
./gradlew assembleDebug
```

Release:
```bash
./gradlew assembleRelease
```

## Режимы работы

| Режим | Описание |
|---|---|
| **Прямой WebSocket** | Подключение к `kws{dc}.web.telegram.org` через DoH + параллельный connect |
| **CF Fallback** | Если прямой IP блокируется — автоматический fallback через Cloudflare |
| **TCP Fallback** | Резервный TCP-коннект к известным IP DC если CF тоже недоступен |
| **Cold-start** | При первом запуске (пока нет истории CF) direct connect пропускается для скорости |

## Обход блокировки (по умолчанию включено)

Все флаги установлены в ON при первой установке:

- **DoH резолвинг** — обход DNS-спуфинга
- **Auto Fake TLS** — автоматическая маскировка TLS SNI
- **Параллельный коннект** — параллельные TCP-рукопожатия к нескольким IP
- **Медиа через CF** — медиа-трафик через Cloudflare
- **CF Proxy fallback** + **приоритет** — автоматический fallback через CF

## Логи и диагностика

- In-memory буфер 2000 строк (вкладка «Логи»)
- Файловые логи с ротацией: max 2MB × 3 файла
- Кнопки **«Поделиться»** (файл `.txt`) и **«Сохранить»** (Downloads)

## Стек

- Kotlin + Android SDK 34
- Coroutines (асинхронная обработка)
- Material Design Components
- ViewBinding / DataBinding
- Raw TLS + WebSocket framing (без OkHttp WS для контроля таймаутов)

## Портировано из

- `proxy/tg_ws_proxy.py` → `TgWsProxy.kt`
- `proxy/bridge.py` → bridge + fallback logic
- `proxy/fake_tls.py` → `handleFakeTLS`, `FakeTlsInputStream`
- `proxy/raw_websocket.py` → `RawWebSocket.kt`
- `proxy/config.py` → `ProxyConfig.kt`
- `proxy/stats.py` → `ProxyStats.kt`
- `proxy/balancer.py` → `Balancer.kt`
- `proxy/doh_resolver.py` → `DoHResolver.kt`