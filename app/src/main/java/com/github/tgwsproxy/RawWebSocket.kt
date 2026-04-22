package com.github.tgwsproxy

import android.util.Log
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Raw WebSocket client with DoH, parallel connect, and keep-alive.
 *
 * ONE TLS handshake with SNI + disabled cert verification.
 * Manual WebSocket framing — no OkHttp interference.
 */
class RawWebSocket private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val socket: Socket
) {
    companion object {
        private const val TAG = "RawWebSocket"
        private const val CONNECT_RETRY_MAX = 3
        private const val CONNECT_BASE_DELAY_MS = 1000L
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        // ---- DoH + parallel connect ----
        /**
         * Connect with optional DoH resolution, parallel attempts, and retry.
         */
        fun connect(
            targetIp: String,
            domain: String,
            timeoutMs: Long = 8000,
            useDoH: Boolean = true,
            dohEndpoints: List<String> = ProxyConfig().dohEndpoints,
            retryMax: Int = CONNECT_RETRY_MAX
        ): RawWebSocket? {
            for (attempt in 1..retryMax) {
                val delay = if (attempt == 1) 0L else CONNECT_BASE_DELAY_MS * (1 shl (attempt - 2))
                if (delay > 0) {
                    try { Thread.sleep(delay) } catch (_: InterruptedException) {}
                }
                val ws = tryConnectOnce(targetIp, domain, timeoutMs, useDoH, dohEndpoints, attempt)
                if (ws != null) return ws
            }
            return null
        }

        private fun tryConnectOnce(
            targetIp: String,
            domain: String,
            timeoutMs: Long,
            useDoH: Boolean,
            dohEndpoints: List<String>,
            attempt: Int
        ): RawWebSocket? {
            val logTag = "RawWS"
            val addrsToTry = mutableListOf<String>()

            if (isIpAddress(targetIp)) {
                addrsToTry.add(targetIp)
            } else {
                if (useDoH) {
                    AppLogger.d(logTag, "[attempt $attempt] DoH resolving $targetIp ...")
                    val resolved = DoHResolver.resolve(targetIp, dohEndpoints, 8000)
                        .takeIf { it.isNotEmpty() }
                        ?: DoHResolver.resolve(domain, dohEndpoints, 8000)
                    addrsToTry.addAll(resolved)
                    AppLogger.d(logTag, "[attempt $attempt] DoH resolved $targetIp -> $resolved")
                }
                if (addrsToTry.isEmpty()) {
                    // fallback to system DNS
                    try {
                        val inet = java.net.InetAddress.getAllByName(targetIp)
                        addrsToTry.addAll(inet.map { it.hostAddress ?: "" }.filter { it.isNotEmpty() })
                    } catch (e: Exception) {
                        AppLogger.w(logTag, "[attempt $attempt] System DNS failed: ${e.message}")
                    }
                }
            }
            if (addrsToTry.isEmpty()) {
                AppLogger.e(logTag, "[attempt $attempt] No addresses to connect for $targetIp")
                return null
            }
            // Always keep original IP first if it's direct DC IP
            addrsToTry.distinct()

            return if (addrsToTry.size == 1) {
                connectSingle(addrsToTry[0], domain, timeoutMs, attempt)
            } else {
                connectParallel(addrsToTry, domain, timeoutMs, attempt)
            }
        }

        private fun connectSingle(addr: String, domain: String, timeoutMs: Long, attempt: Int): RawWebSocket? {
            return rawConnect(addr, domain, timeoutMs).also {
                if (it != null) AppLogger.i("RawWS", "[attempt $attempt] Connected via single path: $addr -> $domain")
            }
        }

        /**
         * Parallel connect to multiple IPs; returns first successful.
         */
        private fun connectParallel(addrs: List<String>, domain: String, timeoutMs: Long, attempt: Int): RawWebSocket? {
            if (addrs.isEmpty()) return null
            val done = CountDownLatch(1)
            val winner = AtomicReference<RawWebSocket?>(null)
            val finished = AtomicBoolean(false)
            val threads = mutableListOf<Thread>()
            AppLogger.d("RawWS", "[attempt $attempt] Parallel connect to ${addrs.size} IPs for $domain: $addrs")

            for (addr in addrs) {
                val t = Thread {
                    if (finished.get()) return@Thread
                    val ws = rawConnect(addr, domain, timeoutMs)
                    if (ws != null && winner.compareAndSet(null, ws)) {
                        finished.set(true)
                        done.countDown()
                        AppLogger.i("RawWS", "[attempt $attempt] Parallel winner: $addr -> $domain")
                    }
                }
                threads.add(t); t.start()
            }

            val ok = done.await(timeoutMs + 2000, TimeUnit.MILLISECONDS)
            threads.forEach { it.interrupt() }
            return if (ok) winner.get() else null
        }

        private fun rawConnect(targetIp: String, domain: String, timeoutMs: Long): RawWebSocket? {
            val logTag = "RawWS"
            try {
                val connTimeout = timeoutMs.toInt().coerceIn(3000, 20000)

                // 1. Plain TCP connect
                val plainSocket = Socket()
                plainSocket.tcpNoDelay = true
                plainSocket.sendBufferSize = 256 * 1024
                plainSocket.receiveBufferSize = 256 * 1024
                val tcpStart = System.currentTimeMillis()
                plainSocket.connect(InetSocketAddress(targetIp, 443), connTimeout)
                plainSocket.soTimeout = connTimeout
                AppLogger.d(logTag, "TCP connected $targetIp in ${System.currentTimeMillis() - tcpStart}ms")

                // 2. TLS handshake with SNI + no cert verification
                val trustAll = arrayOfWorkaroundTrustManagers()
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAll, null)
                val sslSocket = sslContext.socketFactory.createSocket(
                    plainSocket, targetIp, 443, true
                ) as SSLSocket

                var sniSet = false
                try {
                    val params = sslSocket.sslParameters
                    params.serverNames = listOf(SNIHostName(domain))
                    params.endpointIdentificationAlgorithm = null
                    sslSocket.sslParameters = params
                    sniSet = true
                } catch (e: Exception) {
                    try {
                        val method = sslSocket.javaClass.getMethod("setHostname", String::class.java)
                        method.invoke(sslSocket, domain)
                        sniSet = true
                    } catch (_: Exception) {}
                }
                if (!sniSet) {
                    AppLogger.w(logTag, "WARNING: SNI not set for $domain")
                }

                val tlsStart = System.currentTimeMillis()
                sslSocket.startHandshake()
                AppLogger.d(logTag, "TLS handshake done $domain in ${System.currentTimeMillis() - tlsStart}ms protocol=${sslSocket.session?.protocol}")

                val input = sslSocket.getInputStream()
                val output = sslSocket.getOutputStream()

                // 3. WebSocket upgrade request
                val wsKey = Base64Encoder.encode(SecureRandom().generateSeed(16))
                val request = (
                    "GET /apiws HTTP/1.1\r\n" +
                    "Host: $domain\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $wsKey\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "Sec-WebSocket-Protocol: binary\r\n" +
                    "User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\n" +
                    "\r\n"
                ).toByteArray()
                output.write(request)
                output.flush()

                // 4. Read HTTP response
                val responseLines = mutableListOf<String>()
                val buf = ByteArray(1)
                var line = StringBuilder()
                val readStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - readStart < connTimeout) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val ch = buf[0].toInt().toChar()
                    line.append(ch)
                    if (ch == '\n') {
                        val trimmed = line.toString().trim()
                        if (trimmed.isEmpty()) break
                        responseLines.add(trimmed)
                        line = StringBuilder()
                    }
                }
                if (responseLines.isEmpty()) {
                    AppLogger.w(logTag, "WS: empty response from $targetIp/$domain")
                    sslSocket.close()
                    return null
                }
                val firstLine = responseLines[0]
                val parts = firstLine.split(" ")
                val statusCode = if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0
                AppLogger.d(logTag, "WS response: status=$statusCode line=$firstLine")

                if (statusCode != 101) {
                    AppLogger.w(logTag, "WS handshake rejected: status=$statusCode for $targetIp/$domain")
                    sslSocket.close()
                    return null
                }
                sslSocket.soTimeout = 0
                AppLogger.i(logTag, "WS established $targetIp/$domain")
                return RawWebSocket(sslSocket.getInputStream(), sslSocket.getOutputStream(), sslSocket)
            } catch (e: Exception) {
                AppLogger.d(logTag, "rawConnect failed $targetIp/$domain: ${e.message}")
                return null
            }
        }

        private fun isIpAddress(s: String): Boolean {
            return s.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))
        }

        private fun arrayOfWorkaroundTrustManagers(): Array<X509TrustManager> {
            return arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
        }
    }

    @Volatile
    private var closed = false

    fun send(data: ByteArray) {
        if (closed) return
        val frame = buildFrame(OP_BINARY, data, mask = true)
        output.write(frame)
        output.flush()
    }

    fun sendBatch(parts: List<ByteArray>) {
        if (closed) return
        for (part in parts) {
            output.write(buildFrame(OP_BINARY, part, mask = true))
        }
        output.flush()
    }

    /**
     * Send a WebSocket PING frame.
     */
    fun ping(data: ByteArray = ByteArray(0)) {
        if (closed) return
        try {
            output.write(buildFrame(OP_PING, data, mask = true))
            output.flush()
        } catch (_: Exception) {}
    }

    fun recv(): ByteArray? {
        while (!closed) {
            val frame = readFrame() ?: return null
            val opcode = frame.first
            val payload = frame.second
            when (opcode) {
                OP_CLOSE -> {
                    closed = true
                    try {
                        output.write(buildFrame(OP_CLOSE, payload.copyOfRange(0, minOf(2, payload.size)), mask = true))
                        output.flush()
                    } catch (_: Exception) {}
                    return null
                }
                OP_PING -> {
                    try {
                        output.write(buildFrame(OP_PONG, payload, mask = true))
                        output.flush()
                    } catch (_: Exception) {}
                    continue
                }
                OP_PONG -> continue
                0x1, 0x2 -> return payload
                else -> continue
            }
        }
        return null
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            output.write(buildFrame(OP_CLOSE, ByteArray(0), mask = true))
            output.flush()
        } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }

    val isClosed: Boolean get() = closed

    // ---- Frame building / reading helpers ----

    private fun buildFrame(opcode: Int, data: ByteArray, mask: Boolean = false): ByteArray {
        val length = data.size
        val fb = (0x80 or opcode).toByte()
        if (!mask) {
            return when {
                length < 126 -> byteArrayOf(fb, length.toByte()) + data
                length < 65536 -> byteArrayOf(fb, 126.toByte()) + shortToBytes(length.toShort()) + data
                else -> byteArrayOf(fb, 127.toByte()) + longToBytes(length.toLong()) + data
            }
        }
        val maskKey = SecureRandom().generateSeed(4)
        val masked = xorMask(data, maskKey)
        return when {
            length < 126 -> byteArrayOf(fb, (0x80 or length).toByte()) + maskKey + masked
            length < 65536 -> byteArrayOf(fb, (0x80 or 126).toByte()) + shortToBytes(length.toShort()) + maskKey + masked
            else -> byteArrayOf(fb, (0x80 or 127).toByte()) + longToBytes(length.toLong()) + maskKey + masked
        }
    }

    private fun readFrame(): Pair<Int, ByteArray>? {
        try {
            val hdr = readExactly(2)
            val opcode = hdr[0].toInt() and 0x0F
            var length = hdr[1].toInt() and 0x7F
            if (length == 126) length = readShort()
            else if (length == 127) length = readLong().toInt()
            val masked = (hdr[1].toInt() and 0x80) != 0
            if (masked) {
                val maskKey = readExactly(4)
                val payload = readExactly(length)
                return Pair(opcode, xorMask(payload, maskKey))
            }
            val payload = readExactly(length)
            return Pair(opcode, payload)
        } catch (_: Exception) { return null }
    }

    private fun xorMask(data: ByteArray, mask: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val result = ByteArray(data.size)
        for (i in data.indices) result[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        return result
    }

    private fun readExactly(n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read <= 0) throw EOFException("Connection closed")
            offset += read
        }
        return buf
    }

    private fun readShort(): Int {
        val b = readExactly(2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    private fun readLong(): Long {
        val b = readExactly(8)
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (b[i].toInt().toLong() and 0xFF)
        return result
    }

    private fun shortToBytes(v: Short): ByteArray {
        return byteArrayOf((v.toInt() shr 8).toByte(), (v.toInt() and 0xFF).toByte())
    }

    private fun longToBytes(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 7 downTo 0) b[i] = (v shr (8 * (7 - i))).toByte()
        return b
    }

    private object Base64Encoder {
        private val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        fun encode(data: ByteArray): String {
            val sb = StringBuilder()
            var i = 0
            while (i < data.size) {
                val b0 = data[i].toInt() and 0xFF
                val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
                sb.append(CHARS[b0 shr 2])
                sb.append(CHARS[(b0 and 3 shl 4) or (b1 shr 4)])
                sb.append(if (i + 1 < data.size) CHARS[(b1 and 0xF shl 2) or (b2 shr 6)] else '=')
                sb.append(if (i + 2 < data.size) CHARS[b2 and 0x3F] else '=')
                i += 3
            }
            return sb.toString()
        }
    }
}
