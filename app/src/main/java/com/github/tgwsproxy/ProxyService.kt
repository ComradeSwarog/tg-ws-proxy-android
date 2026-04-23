package com.github.tgwsproxy

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*

/**
 * Foreground service that runs the MTProto WS proxy.
 * Enhanced with periodic CF domain refresh and automatic reconnect.
 */
class ProxyService : Service() {

    companion object {
        private const val TAG = "ProxyService"
        private const val NOTIFICATION_CHANNEL_ID = "tg_ws_proxy_channel"
        private const val NOTIFICATION_ID = 1
        private const val RESTART_REQUEST_CODE = 1001

        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000
        private const val WAKELOCK_REFRESH_MS = 25L * 60 * 1000

        const val ACTION_START = "com.github.tgwsproxy.ACTION_START"
        const val ACTION_STOP = "com.github.tgwsproxy.ACTION_STOP"
        const val EXTRA_CONFIG = "com.github.tgwsproxy.EXTRA_CONFIG"

        var isProxyRunning = false
            private set

        var onProxyStateChanged: ((Boolean) -> Unit)? = null
    }

    private var proxy: TgWsProxy? = null
    private var config: ProxyConfig? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Coroutine exception: ${throwable.message}", throwable)
            if (throwable is OutOfMemoryError) {
                Log.e(TAG, "OOM in serviceScope \u2014 triggering graceful shutdown")
                stopProxy()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    )
    private var domainRefreshJob: Job? = null
    private val balancer = Balancer()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on ${thread.name}: ${throwable.message}", throwable)
            if (throwable is OutOfMemoryError) {
                Log.e(TAG, "OOM detected — graceful shutdown")
                stopProxy()
                try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            }
            stopSelf()
            if (throwable is OutOfMemoryError) {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action} startId=$startId flags=$flags")
        
        // ALWAYS start foreground immediately to avoid ForegroundServiceDidNotStartInTimeException.
        // Android 12+ kills the service within 5s if startForeground() is not called.
        try {
            val notification = buildNotification(running = true)
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            // If we can't start foreground, crash gracefully rather than get killed
            throw e
        }
        
        when (intent?.action) {
            ACTION_START -> {
                val cfg = loadConfig()
                config = cfg
                startProxy(cfg)
            }
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // Null intent handled by START_REDELIVER_INTENT (should not happen)
                // or unknown action: restart proxy if not running
                if (!isProxyRunning) {
                    val cfg = config ?: loadConfig()
                    config = cfg
                    startProxy(cfg)
                }
            }
        }
        val workInBackground = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("work_in_background", false)
        return if (workInBackground) START_REDELIVER_INTENT else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved called")
        val workInBackground = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("work_in_background", false)

        if (workInBackground) {
            scheduleRestart()
        } else {
            stopProxy()
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        domainRefreshJob?.cancel()
        serviceScope.cancel()
        val workInBackground = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("work_in_background", false)

        if (workInBackground && isProxyRunning) {
            scheduleRestart()
        } else {
            stopProxy()
        }
        super.onDestroy()
    }

    private fun scheduleRestart() {
        Log.d(TAG, "Scheduling service restart in 3 seconds")
        try {
            val restartIntent = Intent(this, ProxyService::class.java).apply {
                action = ACTION_START
            }
            val pendingIntent = PendingIntent.getService(
                this,
                RESTART_REQUEST_CODE,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Primary: exact alarm with small window (3s) so cleanup finishes before restart
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 3000,
                    pendingIntent
                )
            } catch (_: Exception) {
                // Fallback for devices that restrict exact alarms
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5000,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart: ${e.message}")
        }
    }

    private fun startProxy(cfg: ProxyConfig) {
        if (isProxyRunning) return

        val notification = buildNotification(running = true)
        startForeground(NOTIFICATION_ID, notification)
        acquireWakeLocks()

        proxy = TgWsProxy(cfg, balancer) { status ->
            when {
                status == "running" -> {
                    isProxyRunning = true
                    onProxyStateChanged?.invoke(true)
                    updateNotification(running = true)
                }
                status.startsWith("error") -> {
                    isProxyRunning = false
                    onProxyStateChanged?.invoke(false)
                    updateNotification(running = false, error = status)
                }
                else -> {
                    isProxyRunning = false
                    onProxyStateChanged?.invoke(false)
                    updateNotification(running = false)
                }
            }
        }
        proxy?.start()
        isProxyRunning = true
        onProxyStateChanged?.invoke(true)

        // Start periodic CF domain refresh
        startDomainRefresh(cfg)
        // Start WakeLock refresh loop (25 min) to prevent Samsung expiring wakelocks
        startWakeLockRefreshLoop()
    }

    private var wakeLockRefreshJob: Job? = null

    private fun startWakeLockRefreshLoop() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = serviceScope.launch {
            while (isActive) {
                delay(WAKELOCK_REFRESH_MS)
                refreshWakeLock()
            }
        }
    }

    private fun startDomainRefresh(cfg: ProxyConfig) {
        domainRefreshJob?.cancel()
        if (!cfg.fallbackCfproxy || cfg.cfproxyUserDomain.isNotEmpty()) return
        domainRefreshJob = serviceScope.launch {
            while (isActive) {
                refreshCfproxyDomains()
                delay(3600_000L) // every hour
            }
        }
    }

    private suspend fun refreshCfproxyDomains() {
        try {
            val url = ProxyConfig.CFPROXY_DOMAINS_URL + "?" + (1..7).map { ('a'..'z').random() }.joinToString("")
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "tg-ws-proxy-android")
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val decoded = text.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { decodeCfDomain(it) }
                if (decoded.isNotEmpty()) {
                    balancer.updateDomainsList(decoded.toList())
                    AppLogger.i(TAG, "CF domain pool refreshed: ${decoded.size} domains")
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "CF domain refresh failed: ${e.message}")
        }
    }

    private fun decodeCfDomain(s: String): String {
        if (!s.endsWith(".com")) return s
        val p = s.dropLast(4)
        val n = p.count { it.isLetter() }
        val decoded = p.map { c ->
            if (c.isLetter()) {
                val base = if (c > '`') 97 else 65
                ((c.code - base - n) % 26 + base).toChar()
            } else c
        }.joinToString("")
        return decoded + ".co.uk"
    }

    private fun stopProxy() {
        proxy?.stop()
        proxy = null
        isProxyRunning = false
        onProxyStateChanged?.invoke(false)
        releaseWakeLocks()
    }

    private fun acquireWakeLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy::ProxyWakeLock")
            wakeLock?.acquire(30 * 60 * 1000L) // 30 min window, re-acquired periodically by keep-alive
            AppLogger.d(TAG, "WakeLock acquired (30 min)")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiLockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(wifiLockMode, "TgWsProxy::WifiLock")
            wifiLock?.acquire()
            AppLogger.d(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to acquire WifiLock: ${e.message}")
        }
    }

    private fun refreshWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy::ProxyWakeLock")
            wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            AppLogger.d(TAG, "WakeLock refreshed")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to refresh WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLocks() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = null
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            AppLogger.d(TAG, "WakeLock released")
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
            AppLogger.d(TAG, "WifiLock released")
        } catch (_: Exception) {}
    }

    private fun loadConfig(): ProxyConfig {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val secret = prefs.getString("secret", "") ?: ""
        val host = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("port", 1443)
        val dcIpRaw = prefs.getString("dc_ip", "2:149.154.167.220\n4:149.154.167.220") ?: "2:149.154.167.220\n4:149.154.167.220"
        val cfproxy = prefs.getBoolean("cfproxy", true)
        val cfproxyPriority = prefs.getBoolean("cfproxy_priority", true)
        val cfproxyUserDomain = prefs.getString("cfproxy_user_domain", "") ?: ""
        val fakeTlsDomain = prefs.getString("fake_tls_domain", "") ?: ""
        val poolSize = prefs.getInt("pool_size", 4)

        val dcRedirects = mutableMapOf<Int, String>()
        for (line in dcIpRaw.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(":")
            if (parts.size == 2) {
                val dc = parts[0].toIntOrNull()
                val ip = parts[1].trim()
                if (dc != null && ip.isNotEmpty()) {
                    dcRedirects[dc] = ip
                }
            }
        }

        return ProxyConfig(
            host = host,
            port = port,
            secret = if (secret.isEmpty()) generateSecret() else secret,
            dcRedirects = dcRedirects,
            poolSize = poolSize,
            fallbackCfproxy = cfproxy,
            fallbackCfproxyPriority = cfproxyPriority,
            cfproxyUserDomain = cfproxyUserDomain,
            fakeTlsDomain = fakeTlsDomain,
            useDoH = prefs.getBoolean("use_doh", true),
            keepAliveIntervalMs = prefs.getLong("keepalive_ms", 30_000),
            parallelConnect = prefs.getBoolean("parallel_connect", true),
            autoFakeTls = prefs.getBoolean("auto_fake_tls", true),
            mediaViaCf = prefs.getBoolean("media_via_cf", true),
            connectTimeoutMs = prefs.getLong("connect_timeout", 3000),
            handshakeTimeoutMs = prefs.getLong("handshake_timeout", 5000)
        )
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_running)
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(running: Boolean, error: String? = null): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (running) getString(R.string.proxy_running) else getString(R.string.proxy_stopped)
        val text = if (error != null) error else if (running) stats.shortSummary() else ""

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_proxy_on)
            .setContentIntent(pendingIntent)
            .setOngoing(running)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(running: Boolean, error: String? = null) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(running, error))
    }
}