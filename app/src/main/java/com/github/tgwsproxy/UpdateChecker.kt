package com.github.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases API for newer stable versions.
 * Rate-limited to 1 request/hour; uses ETag; caches the last check.
 */
object UpdateChecker {
    private const val PREF_LAST_CHECK_TIME = "update_last_check"
    private const val PREF_ETAG = "update_etag"
    private const val PREF_CACHED_TAG = "update_cached_tag"
    private const val PREF_CACHED_URL = "update_cached_url"
    private const val PREF_DONT_AUTO_CHECK = "update_dont_auto_check"

    private const val REPO = "ComradeSwarog/tg-ws-proxy-android"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_PAGE_URL = "https://github.com/$REPO/releases/latest"
    private const val MIN_INTERVAL_MS = 3600_000L // 1 hour

    data class CheckResult(
        val hasUpdate: Boolean,
        val latestVersion: String?,
        val htmlUrl: String?,
        val error: String? = null
    )

    fun isAutoCheckEnabled(context: Context): Boolean {
        return !PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_DONT_AUTO_CHECK, false)
    }

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(PREF_DONT_AUTO_CHECK, !enabled).apply()
    }

    fun setDontAutoCheck(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(PREF_DONT_AUTO_CHECK, enabled).apply()
    }

    /**
     * Check for updates. If too soon, return cached result without network call.
     * If 304 Not Modified, use cached tag.
     */
    suspend fun check(context: Context, currentVersion: String): CheckResult =
        withContext(Dispatchers.IO) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val now = System.currentTimeMillis()
            val lastCheck = prefs.getLong(PREF_LAST_CHECK_TIME, 0)

            if (now - lastCheck < MIN_INTERVAL_MS) {
                val cachedTag = prefs.getString(PREF_CACHED_TAG, null)
                return@withContext evaluate(cachedTag, currentVersion, prefs)
            }

            val etag = prefs.getString(PREF_ETAG, null)
            var connection: HttpURLConnection? = null
            try {
                val url = URL(API_URL)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 12000
                connection.readTimeout = 12000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "tg-ws-proxy-android")
                if (!etag.isNullOrEmpty()) {
                    connection.setRequestProperty("If-None-Match", etag)
                }

                val code = connection.responseCode
                val newEtag = connection.getHeaderField("ETag")

                if (code == 304) {
                    prefs.edit().putLong(PREF_LAST_CHECK_TIME, now).apply()
                    val cachedTag = prefs.getString(PREF_CACHED_TAG, null)
                    return@withContext evaluate(cachedTag, currentVersion, prefs)
                }

                if (code != 200) {
                    return@withContext CheckResult(
                        false, null, null,
                        "HTTP $code"
                    )
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "").trim()
                val htmlUrl = json.optString("html_url", RELEASES_PAGE_URL).trim()
                    .ifEmpty { RELEASES_PAGE_URL }

                prefs.edit()
                    .putLong(PREF_LAST_CHECK_TIME, now)
                    .putString(PREF_ETAG, newEtag)
                    .putString(PREF_CACHED_TAG, tag)
                    .putString(PREF_CACHED_URL, htmlUrl)
                    .apply()

                evaluate(tag, currentVersion, prefs)
            } catch (e: Exception) {
                prefs.edit().putLong(PREF_LAST_CHECK_TIME, now).apply()
                CheckResult(false, null, null, e.message)
            } finally {
                connection?.disconnect()
            }
        }

    private fun evaluate(tag: String?, currentVersion: String, prefs: SharedPreferences): CheckResult {
        if (tag.isNullOrEmpty()) {
            return CheckResult(false, null, null)
        }
        val cleanTag = tag.trimStart('v', 'V')
        val cleanCurrent = currentVersion.trimStart('v', 'V')
        val hasUpdate = versionGreaterThan(cleanTag, cleanCurrent)
        val url = prefs.getString(PREF_CACHED_URL, RELEASES_PAGE_URL)
            ?: RELEASES_PAGE_URL
        // Only report if remote version is strictly greater (not a pre-release or beta)
        return CheckResult(hasUpdate && !cleanTag.contains("beta", true) && !cleanTag.contains("alpha", true), tag, url)
    }

    /**
     * Simple tuple-based version comparison.
     * "1.3.0" > "1.2.5" = true; "1.2.0" > "1.2.0" = false
     */
    private fun versionGreaterThan(a: String, b: String): Boolean {
        val pa = a.split(".")
        val pb = b.split(".")
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val va = pa.getOrElse(i) { "0" }.filter { it.isDigit() }.toIntOrNull() ?: 0
            val vb = pb.getOrElse(i) { "0" }.filter { it.isDigit() }.toIntOrNull() ?: 0
            if (va > vb) return true
            if (va < vb) return false
        }
        return false
    }
}
