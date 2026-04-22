package com.github.tgwsproxy

/**
 * Configuration for the MTProto WS Bridge Proxy.
 * Extended with DPI bypass settings.
 */
data class ProxyConfig(
    var host: String = "127.0.0.1",
    var port: Int = 1443,
    var secret: String = "",
    var dcRedirects: Map<Int, String> = mapOf(2 to "149.154.167.220", 4 to "149.154.167.220"),
    var bufferSize: Int = 256 * 1024,
    var poolSize: Int = 4,
    var fallbackCfproxy: Boolean = true,
    var fallbackCfproxyPriority: Boolean = true,
    var cfproxyUserDomain: String = "",
    var fakeTlsDomain: String = "",
    // --- DPI bypass settings ---
    var useDoH: Boolean = true,
    var dohEndpoints: List<String> = listOf(
        "https://cloudflare-dns.com/dns-query",
        "https://dns.google/resolve",
        "https://dns.quad9.net:5053/dns-query"
    ),
    var keepAliveIntervalMs: Long = 30000,
    var parallelConnect: Boolean = true,
    var connectTimeoutMs: Long = 8000,
    var handshakeTimeoutMs: Long = 5000,
    var autoFakeTls: Boolean = false,
    var mediaViaCf: Boolean = false,
    var tcpFallbackTls: Boolean = false,
    var verbose: Boolean = false,
) {
    companion object {
        val DC_DEFAULT_IPS = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.51",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "149.154.171.5",
            203 to "91.105.192.100"
        )

        // Encoded CF proxy domains (from original project)
        private val _CFPROXY_ENC = listOf(
            "virkgj.com", "vmmzovy.com", "mkuosckvso.com",
            "zaewayzmplad.com", "twdmbzcm.com"
        )
        private val _S = String(intArrayOf(46, 99, 111, 46, 117, 107).map { it.toChar() }.toCharArray())

        private fun _dd(s: String): String {
            if (!s.endsWith(".com")) return s
            val p = s.dropLast(4)
            val n = p.count { it.isLetter() }
            return p.map { c ->
                if (c.isLetter()) {
                    val base = if (c > '`') 97 else 65
                    ((c.code - base - n) % 26 + base).toChar()
                } else c
            }.joinToString("") + _S
        }

        val CFPROXY_DEFAULT_DOMAINS: List<String> = _CFPROXY_ENC.map { _dd(it) }

        const val CFPROXY_DOMAINS_URL =
            "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
    }
}
