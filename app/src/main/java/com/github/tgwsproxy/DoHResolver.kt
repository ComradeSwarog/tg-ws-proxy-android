package com.github.tgwsproxy

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * DNS-over-HTTPS resolver with fallback.
 */
object DoHResolver {
    private const val TAG = "DoHResolver"

    private val trustAllCerts = arrayOf(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    init {
        try {
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        } catch (_: Exception) {}
    }

    fun resolve(hostname: String, endpoints: List<String>, timeoutMs: Int = 3000): List<String> {
        val results = mutableListOf<String>()
        for (ep in endpoints) {
            try {
                val url = if (ep.contains("?")) ep else "$ep?name=$hostname&type=A"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Accept", "application/dns-json")
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val ips = parseJson(text)
                    if (ips.isNotEmpty()) {
                        AppLogger.d(TAG, "DoH $ep resolved $hostname -> $ips")
                        results.addAll(ips)
                        break
                    }
                }
            } catch (e: Exception) {
                AppLogger.d(TAG, "DoH $ep failed: ${e.message}")
            }
        }
        if (results.isEmpty()) {
            try {
                val inet = InetAddress.getAllByName(hostname)
                results.addAll(inet.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() })
                AppLogger.d(TAG, "System DNS resolved $hostname -> $results")
            } catch (e: Exception) {
                AppLogger.w(TAG, "System DNS failed for $hostname: ${e.message}")
            }
        }
        return results.distinct()
    }

    private fun parseJson(text: String): List<String> {
        val ips = mutableListOf<String>()
        try {
            val obj = JSONObject(text)
            val answers = obj.optJSONArray("Answer") ?: JSONArray()
            for (i in 0 until answers.length()) {
                val ans = answers.getJSONObject(i)
                val type = ans.optInt("type", 0)
                if (type == 1) {
                    val data = ans.optString("data", "")
                    if (data.isNotEmpty()) ips.add(data)
                }
            }
        } catch (_: Exception) {}
        try {
            val obj = JSONObject(text)
            val answers = obj.optJSONArray("Answer") ?: JSONArray()
            for (i in 0 until answers.length()) {
                val ans = answers.getJSONObject(i)
                val data = ans.optString("data", "")
                if (data.matches(Regex("""^\d+(\.\d+){3}$"""))) ips.add(data)
            }
        } catch (_: Exception) {}
        return ips
    }

    fun resolveTelegramDC(endpoints: List<String>): Map<String, List<String>> {
        val hosts = listOf(
            "kws1.web.telegram.org",
            "kws2.web.telegram.org",
            "kws2-1.web.telegram.org",
            "kws3.web.telegram.org",
            "kws4.web.telegram.org",
            "kws4-1.web.telegram.org",
            "kws5.web.telegram.org",
            "kws203.web.telegram.org"
        )
        val resolved = mutableMapOf<String, List<String>>()
        for (h in hosts) {
            val ips = resolve(h, endpoints, 5000)
            if (ips.isNotEmpty()) resolved[h] = ips
        }
        AppLogger.i(TAG, "Telegram DC resolved: ${resolved.keys.size} hosts")
        return resolved
    }
}
