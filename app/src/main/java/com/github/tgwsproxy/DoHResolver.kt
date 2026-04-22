package com.github.tgwsproxy

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
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

    /** Thread-safe round-robin index for DoH endpoint rotation. */
    private val endpointIndex = AtomicInteger(0)

    /**
     * Create a local SSLContext that trusts all certs; never mutate the global default.
     */
    private fun createTrustAllSocketFactory(): javax.net.ssl.SSLSocketFactory? {
        return try {
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, java.security.SecureRandom())
            sc.socketFactory
        } catch (_: Exception) { null }
    }

    /**
     * Return a cyclically rotated copy of the endpoints list so that each call
     * starts from a different provider. If rotation is disabled, list is returned as-is.
     */
    fun rotateEndpoints(endpoints: List<String>, rotation: Boolean = true): List<String> {
        if (endpoints.isEmpty() || !rotation) return endpoints
        val idx = endpointIndex.getAndIncrement() % endpoints.size
        return endpoints.drop(idx) + endpoints.take(idx)
    }

    fun resolve(hostname: String, endpoints: List<String>, timeoutMs: Int = 3000, rotation: Boolean = true): List<String> {
        // If hostname is already an IP, return immediately — DoH is pointless.
        val results = mutableListOf<String>()
        if (isIpAddress(hostname)) return listOf(hostname)

        val localSslFactory = createTrustAllSocketFactory()
        val rotated = rotateEndpoints(endpoints, rotation)
        for (ep in rotated) {
            try {
                val url = if (ep.contains("?")) ep else "$ep?name=$hostname&type=A"
                val conn = URL(url).openConnection() as HttpURLConnection
                // Do NOT touch global defaults; apply local SSL factory only for HTTPS
                if (conn is HttpsURLConnection && localSslFactory != null) {
                    conn.sslSocketFactory = localSslFactory
                    conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
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

    /**
     * Check if a string is a dotted-decimal IPv4 address.
     * Visible for unit tests (same Gradle module).
     */
    internal fun isIpAddress(s: String): Boolean {
        return s.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
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
