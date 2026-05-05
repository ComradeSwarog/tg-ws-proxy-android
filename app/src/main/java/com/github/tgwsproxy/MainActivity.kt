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
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.tgwsproxy.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtils.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppLogger.init(this)
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(R.string.tab_proxy)))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(getString(R.string.tab_logs)))
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

        setupLanguageSpinner()

        binding.helpButton.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        binding.checkUpdateButton.setOnClickListener {
            lifecycleScope.launch { performUpdateCheck(manual = true) }
        }

        binding.btnRefreshLogs.setOnClickListener { refreshLogs() }
        binding.btnClearLogs.setOnClickListener {
            AppLogger.clearAll()
            refreshLogs()
        }
        binding.btnShareLogs.setOnClickListener { shareLogs() }
        binding.btnSaveLogs.setOnClickListener { saveLogsToDevice() }

        binding.logLevelChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: View.NO_ID
            val level = when (checkedId) {
                R.id.chipDebug -> AppLogger.Level.DEBUG
                R.id.chipWarn -> AppLogger.Level.WARN
                else -> AppLogger.Level.INFO
            }
            AppLogger.setLevel(level, this)
        }

        logRefreshTimer = Timer("log-refresh", true)
        logRefreshTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (binding.logsTab.visibility == View.VISIBLE) {
                    runOnUiThread { refreshLogs() }
                }
            }
        }, 1000, 2000)

        loadSettingsToUI()

        binding.toggleButton.setOnClickListener { toggleProxy() }
        binding.copyLinkButton.setOnClickListener { copyLinkToClipboard() }
        binding.openTelegramButton.setOnClickListener { openInTelegram() }
        binding.regenSecretButton.setOnClickListener {
            binding.secretInput.setText(generateSecret())
        }
        binding.saveButton.setOnClickListener {
            if (saveSettings()) {
                Toast.makeText(this, getString(R.string.toast_settings_saved), Toast.LENGTH_SHORT).show()
            }
        }

        ProxyService.onProxyStateChanged = { running ->
            runOnUiThread {
                isProxyRunning = running
                updateUI()
            }
        }

        isProxyRunning = ProxyService.isProxyRunning
        updateUI()

        statsTimer = Timer("stats-timer", true)
        statsTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread { updateStats() }
            }
        }, 1000, 2000)

        if (UpdateChecker.isAutoCheckEnabled(this)) {
            lifecycleScope.launch { performUpdateCheck(manual = false) }
        }
    }

    private fun getAppVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }
    }

    override fun onResume() {
        super.onResume()
        isProxyRunning = ProxyService.isProxyRunning
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        statsTimer?.cancel()
        logRefreshTimer?.cancel()
        ProxyService.onProxyStateChanged = null
    }

    // ---- Language selector ----

    private fun setupLanguageSpinner() {
        val current = LocaleUtils.getAppLanguage(this)
        val items = listOf(
            getString(R.string.language_auto) to "",
            getString(R.string.language_ru) to "ru",
            getString(R.string.language_en) to "en"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items.map { it.first })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.languageSpinner.adapter = adapter
        binding.languageSpinner.setSelection(items.indexOfFirst { it.second == current }.coerceAtLeast(0))
        binding.languageSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = items[position].second
                if (selected != current) {
                    LocaleUtils.setAppLanguage(this@MainActivity, selected)
                    val intent = Intent(this@MainActivity, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    finish()
                    startActivity(intent)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    // ---- Update checker ----

    private suspend fun performUpdateCheck(manual: Boolean) {
        val result = UpdateChecker.check(this, getAppVersionName(), force = manual)
        runOnUiThread {
            if (result.error != null) {
                if (manual) {
                    Toast.makeText(this, getString(R.string.update_check_error, result.error), Toast.LENGTH_SHORT).show()
                }
                return@runOnUiThread
            }
            if (result.hasUpdate && result.latestVersion != null) {
                showUpdateDialog(result.latestVersion, result.htmlUrl, result.apkDownloadUrl)
            } else if (manual) {
                Toast.makeText(this, getString(R.string.update_latest), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUpdateDialog(version: String, url: String?, apkUrl: String?) {
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_message, version))
            .setNegativeButton(R.string.update_no, null)

        // If APK download URL available, add "Download & Install" button
        if (apkUrl != null) {
            builder.setPositiveButton(R.string.update_download) { _, _ ->
                downloadAndInstallApk(apkUrl, version)
            }
            builder.setNeutralButton(R.string.update_open_page) { _, _ ->
                val link = url ?: "https://github.com/ComradeSwarog/tg-ws-proxy-android/releases/latest"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            }
        } else {
            builder.setPositiveButton(R.string.update_yes) { _, _ ->
                val link = url ?: "https://github.com/ComradeSwarog/tg-ws-proxy-android/releases/latest"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            }
        }

        builder.show()
    }

    private fun downloadAndInstallApk(apkUrl: String, version: String) {
        val progress = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading))
            .setMessage(getString(R.string.update_downloading_msg, version))
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            try {
                val file = File(cacheDir, "update-${version}.apk")
                val url = java.net.URL(apkUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 120000
                conn.setRequestProperty("User-Agent", "tg-ws-proxy-android")
                if (conn.responseCode != 200) {
                    throw Exception("HTTP ${conn.responseCode}")
                }
                val total = conn.contentLength
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        val buf = ByteArray(8192)
                        var downloaded = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            output.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                runOnUiThread {
                                    progress.setMessage(
                                        getString(R.string.update_downloading_msg, version) +
                                        "\n" + (100L * downloaded / total) + "%"
                                    )
                                }
                            }
                        }
                    }
                }
                conn.disconnect()

                runOnUiThread {
                    progress.dismiss()
                    installApk(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_download_error, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.update_install_error, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    // ---- Proxy control ----

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
    }

    private fun stopProxyService() {
        val intent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        startService(intent)
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
        binding.statsConnections.text = buildString {
            appendLine("${getString(R.string.stats_total)}: ${stats.connectionsTotal.get()}")
            appendLine("${getString(R.string.stats_active)}: ${stats.connectionsActive.get()}")
            appendLine("${getString(R.string.stats_ws)}: ${stats.connectionsWs.get()}")
            appendLine("${getString(R.string.stats_tcp)}: ${stats.connectionsTcpFallback.get()}")
            appendLine("${getString(R.string.stats_cf)}: ${stats.connectionsCfproxy.get()}")
            appendLine("${getString(R.string.stats_bad)}: ${stats.connectionsBad.get()}")
        }
        binding.statsTraffic.text = buildString {
            appendLine("${getString(R.string.traffic_up)}: ${MtProtoConstants.humanBytes(stats.bytesUp.get())}")
            appendLine("${getString(R.string.traffic_down)}: ${MtProtoConstants.humanBytes(stats.bytesDown.get())}")
        }
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
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.toast_telegram_not_installed), Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Logs ----

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
                Toast.makeText(this, getString(R.string.toast_no_logs), Toast.LENGTH_SHORT).show()
                return
            }
            val allLogs = logFiles.sortedBy { it.lastModified() }
                .joinToString("\n\n--- ${"=".repeat(40)} ---\n\n") { it.readText() }
            val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val fileName = "tg-ws-proxy-android_$ts.txt"
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
            startActivity(Intent.createChooser(shareIntent, getString(R.string.btn_share)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_save_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveLogsToDevice() {
        try {
            val logFiles = AppLogger.getLogFiles()
            if (logFiles.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_no_logs), Toast.LENGTH_SHORT).show()
                return
            }
            val allLogs = logFiles.sortedBy { it.lastModified() }
                .joinToString("\n\n--- ${"=".repeat(40)} ---\n\n") { it.readText() }
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val ts = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
            val fileName = "tg-ws-proxy-android_$ts.txt"
            val outFile = File(downloadsDir, fileName)
            FileOutputStream(outFile).use { it.write(allLogs.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, getString(R.string.toast_saved) + ": ${outFile.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_save_error, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Settings ----

    private fun loadSettingsToUI() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (prefs.getInt("settings_version", 0) < 1) {
            prefs.edit()
                .putBoolean("cfproxy", true)
                .putBoolean("cfproxy_priority", true)
                .putBoolean("use_doh", true)
                .putBoolean("auto_fake_tls", true)
                .putBoolean("parallel_connect", true)
                .putBoolean("media_via_cf", true)
                .putBoolean("ws_frame_padding", false)
                .putBoolean("doh_rotation", true)
                .putInt("settings_version", 1)
                .apply()
        }
        if (!prefs.contains("secret") || prefs.getString("secret", "")?.isEmpty() == true) {
            prefs.edit().putString("secret", generateSecret()).apply()
        }

        binding.hostInput.setText(prefs.getString("host", "127.0.0.1"))
        binding.portInput.setText(prefs.getInt("port", 1443).toString())
        binding.secretInput.setText(prefs.getString("secret", ""))
        binding.dcInput.setText(prefs.getString("dc_ip", "2:149.154.167.220\n4:149.154.167.220"))
        binding.fakeTlsInput.setText(prefs.getString("fake_tls_domain", ""))
        binding.poolSizeInput.setText(prefs.getInt("pool_size", 4).toString())

        binding.cfproxySwitch.isChecked = prefs.getBoolean("cfproxy", true)
        binding.cfproxyPrioritySwitch.isChecked = prefs.getBoolean("cfproxy_priority", true)
        binding.useDoHSwitch.isChecked = prefs.getBoolean("use_doh", true)
        binding.autoFakeTlsSwitch.isChecked = prefs.getBoolean("auto_fake_tls", true)
        binding.parallelConnectSwitch.isChecked = prefs.getBoolean("parallel_connect", true)
        binding.mediaViaCfSwitch.isChecked = prefs.getBoolean("media_via_cf", true)
        binding.wsPaddingSwitch.isChecked = prefs.getBoolean("ws_frame_padding", false)
        binding.dohRotationSwitch.isChecked = prefs.getBoolean("doh_rotation", true)
        binding.workInBackgroundSwitch.isChecked = prefs.getBoolean("work_in_background", false)
        binding.checkUpdatesSwitch.isChecked = UpdateChecker.isAutoCheckEnabled(this)

        when (prefs.getString("log_level", "INFO") ?: "INFO") {
            "DEBUG" -> binding.chipDebug.isChecked = true
            "WARN" -> binding.chipWarn.isChecked = true
            else -> binding.chipInfo.isChecked = true
        }
    }

    private fun saveSettings(): Boolean {
        val host = binding.hostInput.text.toString().trim()
        val portStr = binding.portInput.text.toString().trim()
        val secret = binding.secretInput.text.toString().trim()
        val dcIp = binding.dcInput.text.toString().trim()
        val fakeTls = binding.fakeTlsInput.text.toString().trim()
        val poolSizeStr = binding.poolSizeInput.text.toString().trim()

        if (host.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_enter_host), Toast.LENGTH_SHORT).show()
            return false
        }
        val port = portStr.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            Toast.makeText(this, getString(R.string.toast_port_range), Toast.LENGTH_SHORT).show()
            return false
        }
        if (secret.length != 32 || secret.any { it !in "0123456789abcdefABCDEF" }) {
            Toast.makeText(this, getString(R.string.toast_secret_hex), Toast.LENGTH_SHORT).show()
            return false
        }
        for (line in dcIp.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(":")
            if (parts.size != 2 || parts[0].toIntOrNull() == null) {
                Toast.makeText(this, getString(R.string.toast_dc_format) + " '$trimmed'", Toast.LENGTH_SHORT).show()
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
            .putString("fake_tls_domain", fakeTls)
            .putInt("pool_size", poolSize)
            .putBoolean("cfproxy", binding.cfproxySwitch.isChecked)
            .putBoolean("cfproxy_priority", binding.cfproxyPrioritySwitch.isChecked)
            .putBoolean("use_doh", binding.useDoHSwitch.isChecked)
            .putBoolean("auto_fake_tls", binding.autoFakeTlsSwitch.isChecked)
            .putBoolean("parallel_connect", binding.parallelConnectSwitch.isChecked)
            .putBoolean("media_via_cf", binding.mediaViaCfSwitch.isChecked)
            .putBoolean("ws_frame_padding", binding.wsPaddingSwitch.isChecked)
            .putBoolean("doh_rotation", binding.dohRotationSwitch.isChecked)
            .putBoolean("work_in_background", binding.workInBackgroundSwitch.isChecked)
            .putBoolean("auto_check_updates", binding.checkUpdatesSwitch.isChecked)
            .apply()
        return true
    }

    private fun generateSecret(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
