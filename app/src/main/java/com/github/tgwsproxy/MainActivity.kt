package com.github.tgwsproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.github.tgwsproxy.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isProxyRunning = false
    private var statsTimer: Timer? = null
    private var logRefreshTimer: Timer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Init logger
        AppLogger.init(this)
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Прокси"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Логи"))
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (tab.position == 0) {
                    binding.proxyTab.visibility = View.VISIBLE
                    binding.logsTab.visibility = View.GONE
                } else {
                    binding.proxyTab.visibility = View.GONE
                    binding.logsTab.visibility = View.VISIBLE
                    refreshLogs()
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                if (tab.position == 1) refreshLogs()
            }
        })

        // Log buttons
        binding.btnRefreshLogs.setOnClickListener { refreshLogs() }
        binding.btnClearLogs.setOnClickListener {
            AppLogger.clearAll()
            refreshLogs()
        }
        binding.btnShareLogs.setOnClickListener { shareLogs() }
        binding.btnSaveLogs.setOnClickListener { saveLogsToDevice() }

        // Log level chips
        binding.logLevelChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: View.NO_ID
            val level = when (checkedId) {
                R.id.chipDebug -> AppLogger.Level.DEBUG
                R.id.chipWarn -> AppLogger.Level.WARN
                else -> AppLogger.Level.INFO
            }
            AppLogger.setLevel(level, this)
        }

        // Auto-refresh logs when on logs tab
        logRefreshTimer = Timer("log-refresh", true)
        logRefreshTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (binding.logsTab.visibility == View.VISIBLE) {
                    runOnUiThread { refreshLogs() }
                }
            }
        }, 1000, 2000)

        // Load saved settings into UI
        loadSettingsToUI()

        // Toggle button
        binding.toggleButton.setOnClickListener {
            toggleProxy()
        }

        // Copy link
        binding.copyLinkButton.setOnClickListener {
            copyLinkToClipboard()
        }

        // Open in Telegram
        binding.openTelegramButton.setOnClickListener {
            openInTelegram()
        }

        // Regenerate secret
        binding.regenSecretButton.setOnClickListener {
            val newSecret = generateSecret()
            binding.secretInput.setText(newSecret)
        }

        // Save button — only saves settings, does NOT restart service
        binding.saveButton.setOnClickListener {
            if (saveSettings()) {
                Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            }
        }

        // Observe service state changes
        ProxyService.onProxyStateChanged = { running ->
            runOnUiThread {
                isProxyRunning = running
                updateUI()
            }
        }

        // Check current service state (no auto-start)
        isProxyRunning = ProxyService.isProxyRunning
        updateUI()

        // Start stats update timer
        statsTimer = Timer("stats-timer", true)
        statsTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread { updateStats() }
            }
        }, 1000, 2000)
    }

    override fun onResume() {
        super.onResume()
        // Sync state with service when returning to activity
        isProxyRunning = ProxyService.isProxyRunning
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        statsTimer?.cancel()
        statsTimer = null
        logRefreshTimer?.cancel()
        logRefreshTimer = null
        ProxyService.onProxyStateChanged = null
    }

    private fun toggleProxy() {
        if (isProxyRunning) {
            stopProxyService()
        } else {
            if (saveSettings()) {
                startProxyService()
            }
        }
    }

    private fun startProxyService() {
        val intent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // State will be updated via callback from service
    }

    private fun stopProxyService() {
        val intent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        startService(intent)
        // State will be updated via callback from service
    }

    private fun updateUI() {
        if (isProxyRunning) {
            binding.statusIcon.setImageResource(R.drawable.ic_proxy_on)
            binding.statusText.text = getString(R.string.proxy_running)
            binding.toggleButton.text = getString(R.string.btn_disable)
            binding.toggleButton.setBackgroundColor(ContextCompat.getColor(this, R.color.status_inactive))
            binding.linkCard.visibility = View.VISIBLE
            binding.statsCard.visibility = View.VISIBLE
            updateLinkText()
        } else {
            binding.statusIcon.setImageResource(R.drawable.ic_proxy_off)
            binding.statusText.text = getString(R.string.proxy_stopped)
            binding.toggleButton.text = getString(R.string.btn_enable)
            binding.toggleButton.setBackgroundColor(ContextCompat.getColor(this, R.color.status_active))
            binding.linkCard.visibility = View.GONE
            binding.statsCard.visibility = View.GONE
        }
    }

    private fun updateLinkText() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val host = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("port", 1443)
        val secret = prefs.getString("secret", "") ?: ""
        val fakeTlsDomain = prefs.getString("fake_tls_domain", "") ?: ""

        val ddLink = "tg://proxy?server=$host&port=$port&secret=dd$secret"

        if (fakeTlsDomain.isNotEmpty()) {
            val domainHex = fakeTlsDomain.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
            val eeLink = "tg://proxy?server=$host&port=$port&secret=ee$secret$domainHex"
            binding.linkText.text = "dd: $ddLink\n\nee: $eeLink"
        } else {
            binding.linkText.text = ddLink
        }
    }

    private fun updateStats() {
        if (!isProxyRunning) return
        val connText = "total: ${stats.connectionsTotal.get()}\n" +
                "active: ${stats.connectionsActive.get()}\n" +
                "ws: ${stats.connectionsWs.get()}\n" +
                "tcp: ${stats.connectionsTcpFallback.get()}\n" +
                "cf: ${stats.connectionsCfproxy.get()}\n" +
                "bad: ${stats.connectionsBad.get()}"
        binding.statsConnections.text = connText

        val trafficText = "↑ ${MtProtoConstants.humanBytes(stats.bytesUp.get())}\n" +
                "↓ ${MtProtoConstants.humanBytes(stats.bytesDown.get())}"
        binding.statsTraffic.text = trafficText

        binding.statusDetail.text = stats.shortSummary()
    }

    private fun copyLinkToClipboard() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val host = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("port", 1443)
        val secret = prefs.getString("secret", "") ?: ""

        val link = "tg://proxy?server=$host&port=$port&secret=dd$secret"

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TG Proxy Link", link))
        Toast.makeText(this, getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
    }

    private fun openInTelegram() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val host = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("port", 1443)
        val secret = prefs.getString("secret", "") ?: ""

        val link = "tg://proxy?server=$host&port=$port&secret=dd$secret"

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Telegram не установлен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSettingsToUI() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Migration: ensure all bypass flags are ON for existing users updating from old versions
        if (prefs.getInt("settings_version", 0) < 1) {
            prefs.edit()
                .putBoolean("cfproxy", true)
                .putBoolean("cfproxy_priority", true)
                .putBoolean("use_doh", true)
                .putBoolean("auto_fake_tls", true)
                .putBoolean("parallel_connect", true)
                .putBoolean("media_via_cf", true)
                .putInt("settings_version", 1)
                .apply()
        }

        if (!prefs.contains("secret") || prefs.getString("secret", "")?.isEmpty() == true) {
            val secret = generateSecret()
            prefs.edit().putString("secret", secret).apply()
        }

        binding.hostInput.setText(prefs.getString("host", "127.0.0.1"))
        binding.portInput.setText(prefs.getInt("port", 1443).toString())
        binding.secretInput.setText(prefs.getString("secret", ""))
        binding.dcInput.setText(prefs.getString("dc_ip", "2:149.154.167.220\n4:149.154.167.220"))
        binding.cfproxySwitch.isChecked = prefs.getBoolean("cfproxy", true)
        binding.cfproxyPrioritySwitch.isChecked = prefs.getBoolean("cfproxy_priority", true)
        binding.fakeTlsInput.setText(prefs.getString("fake_tls_domain", ""))
        binding.poolSizeInput.setText(prefs.getInt("pool_size", 4).toString())
        binding.workInBackgroundSwitch.isChecked = prefs.getBoolean("work_in_background", false)

        // Enhanced settings
        binding.useDoHSwitch.isChecked = prefs.getBoolean("use_doh", true)
        binding.autoFakeTlsSwitch.isChecked = prefs.getBoolean("auto_fake_tls", true)
        binding.parallelConnectSwitch.isChecked = prefs.getBoolean("parallel_connect", true)
        binding.mediaViaCfSwitch.isChecked = prefs.getBoolean("media_via_cf", true)

        // Load log level
        val levelStr = prefs.getString("log_level", "INFO") ?: "INFO"
        when (levelStr) {
            "DEBUG" -> binding.chipDebug.isChecked = true
            "WARN" -> binding.chipWarn.isChecked = true
            else -> binding.chipInfo.isChecked = true
        }
    }

    private fun refreshLogs() {
        binding.logTextView.text = AppLogger.getLogLines()
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun shareLogs() {
        try {
            val logFiles = AppLogger.getLogFiles()
            if (logFiles.isEmpty()) {
                Toast.makeText(this, "Нет логов для отправки", Toast.LENGTH_SHORT).show()
                return
            }
            val allLogs = logFiles.sortedBy { it.lastModified() }
                .joinToString("\n\n--- ${"=".repeat(40)} ---\n\n") { it.readText() }

            val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val fileName = "tg-ws-proxy-android_${ts}.txt"
            val cacheFile = File(cacheDir, fileName)
            FileOutputStream(cacheFile).use { it.write(allLogs.toByteArray(Charsets.UTF_8)) }

            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", cacheFile)

            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                type = "text/plain"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Поделиться логами"))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveLogsToDevice() {
        try {
            val logFiles = AppLogger.getLogFiles()
            if (logFiles.isEmpty()) {
                Toast.makeText(this, "Нет логов для сохранения", Toast.LENGTH_SHORT).show()
                return
            }
            val allLogs = logFiles.sortedBy { it.lastModified() }
                .joinToString("\n\n--- ${"=".repeat(40)} ---\n\n") { it.readText() }

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val fileName = "tg-ws-proxy-android_${ts}.txt"
            val outFile = File(downloadsDir, fileName)
            FileOutputStream(outFile).use { it.write(allLogs.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, "Сохранено: ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSettings(): Boolean {
        val host = binding.hostInput.text.toString().trim()
        val portStr = binding.portInput.text.toString().trim()
        val secret = binding.secretInput.text.toString().trim()
        val dcIp = binding.dcInput.text.toString().trim()
        val fakeTls = binding.fakeTlsInput.text.toString().trim()
        val poolSizeStr = binding.poolSizeInput.text.toString().trim()

        // Validate
        if (host.isEmpty()) {
            Toast.makeText(this, "Укажите IP-адрес", Toast.LENGTH_SHORT).show()
            return false
        }

        val port = portStr.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            Toast.makeText(this, "Порт должен быть числом 1-65535", Toast.LENGTH_SHORT).show()
            return false
        }

        if (secret.length != 32 || secret.any { it !in "0123456789abcdefABCDEF" }) {
            Toast.makeText(this, "Secret должен содержать ровно 32 hex-символа", Toast.LENGTH_SHORT).show()
            return false
        }

        // Validate DC IP lines
        for (line in dcIp.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(":")
            if (parts.size != 2 || parts[0].toIntOrNull() == null) {
                Toast.makeText(this, "Некорректный формат DC:IP — '$trimmed'", Toast.LENGTH_SHORT).show()
                return false
            }
        }

        val poolSize = poolSizeStr.toIntOrNull() ?: 4

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit()
            .putString("host", host)
            .putInt("port", port)
            .putString("secret", secret.lowercase())
            .putString("dc_ip", dcIp)
            .putBoolean("cfproxy", binding.cfproxySwitch.isChecked)
            .putBoolean("cfproxy_priority", binding.cfproxyPrioritySwitch.isChecked)
            .putString("cfproxy_user_domain", "")
            .putString("fake_tls_domain", fakeTls)
            .putInt("pool_size", poolSize)
            .putBoolean("work_in_background", binding.workInBackgroundSwitch.isChecked)
            .putBoolean("use_doh", binding.useDoHSwitch.isChecked)
            .putBoolean("auto_fake_tls", binding.autoFakeTlsSwitch.isChecked)
            .putBoolean("parallel_connect", binding.parallelConnectSwitch.isChecked)
            .putBoolean("media_via_cf", binding.mediaViaCfSwitch.isChecked)
            .apply()

        return true
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}