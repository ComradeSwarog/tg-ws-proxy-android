package com.github.tgwsproxy

import android.util.Log
import kotlinx.coroutines.*
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Telegram WebSocket Bridge Proxy.
 * Enhanced with parallel fallback, keep-alive, auto fake TLS, and improved reconnection.
 */
class TgWsProxy(
    private val config: ProxyConfig,
    private val sharedBalancer: Balancer? = null,
    private val onStatusChange: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "TgWsProxy"
    private const val DC_FAIL_COOLDOWN = 60_000L
    private const val WS_FAIL_TIMEOUT = 2_000L
    private const val WS_POOL_MAX_AGE = 120_000L
    private const val KEEPALIVE_INTERVAL_MS = 25_000L
    private const val KEEPALIVE_DEADLINE_MS = 60_000L
    private const val CF_PROVEN_MIN = 2       // ≥2 CF successes → treat DC as CF-proven
    private const val CF_PROVEN_TTL_MS = 300_000L  // skip direct for 5 min after proven
    }

    private var serverSocket: ServerSocket? = null
    private var running = false
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val wsBlacklist = mutableSetOf<String>()
    private val dcFailUntil = ConcurrentHashMap<String, Long>()
    private val cfProvenUntil = ConcurrentHashMap<String, Long>() // timestamp until which CF is proven for this DC
    private val cfSuccessCount = ConcurrentHashMap<String, Int>()
    private val balancer: Balancer = sharedBalancer ?: Balancer()
    private val wsPool = WsPool()

    fun start() {
        if (running) return
        try {
            val addr = InetSocketAddress(config.host, config.port)
            serverSocket = ServerSocket().apply { reuseAddress = true; bind(addr) }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to bind ${config.host}:${config.port}: ${e.message}")
            onStatusChange("error:port_busy")
            return
        }
        running = true
        stats.reset()
        wsBlacklist.clear()
        dcFailUntil.clear()
        cfProvenUntil.clear()
        cfSuccessCount.clear()
        wsPool.reset()
        // Pass DPI-bypass config to RawWebSocket static fields
        RawWebSocket.framePaddingEnabled = config.wsFramePadding
        RawWebSocket.framePaddingMinBytes = config.wsFramePaddingMinBytes
        RawWebSocket.framePaddingMaxBytes = config.wsFramePaddingMaxBytes
        RawWebSocket.dohRotationEnabled = config.dohRotation
        AppLogger.i(TAG, "Config: host=${config.host} port=${config.port} fakeTls=${config.fakeTlsDomain} cfproxy=${config.fallbackCfproxy} pool=${config.poolSize}")
        AppLogger.i(TAG, "DC redirects: ${config.dcRedirects}")
        if (config.fallbackCfproxy) {
            if (config.cfproxyUserDomain.isNotEmpty()) {
                balancer.updateDomainsList(listOf(config.cfproxyUserDomain))
            } else {
                if (sharedBalancer == null) {
                    balancer.updateDomainsList(ProxyConfig.CFPROXY_DEFAULT_DOMAINS)
                }
            }
        }
        serverJob = scope.launch { acceptLoop() }
        scope.launch { wsPool.warmup(config.dcRedirects) }
        scope.launch { prewarmCfPool() }
        scope.launch { periodicCleanupLoop() }
        onStatusChange("running")
        AppLogger.i(TAG, "Proxy started on ${config.host}:${config.port}")
    }

    fun stop() {
        if (!running) return
        running = false
        serverJob?.cancel()
        serverJob = null
        try { serverSocket?.close() } catch (e: Exception) { AppLogger.d(TAG, "Error closing server socket: ${e.message}") }
        serverSocket = null
        scope.coroutineContext.cancelChildren()
        wsPool.reset()
        stats.connectionsActive.set(0)
        onStatusChange("stopped")
        AppLogger.i(TAG, "Proxy stopped. Stats: ${stats.summary()}")
    }

    fun isRunning(): Boolean = running

    private suspend fun acceptLoop() {
        val server = serverSocket ?: return
        val secretBytes = hexToBytes(config.secret)
        try {
            while (running && isActive()) {
                try {
                    val client = server.accept()
                    client.tcpNoDelay = true
                    client.soTimeout = 30_000
                    client.sendBufferSize = config.bufferSize
                    client.receiveBufferSize = config.bufferSize
                    scope.launch { handleClient(client, secretBytes) }
                } catch (e: Exception) { if (!running) break; AppLogger.d(TAG, "Accept loop error: ${e.message}") }
            }
        } finally { try { serverSocket?.close() } catch (e: Exception) { AppLogger.d(TAG, "Error closing server in finally: ${e.message}") } }
    }

    private fun isActive() = running && scope.isActive

    private suspend fun handleClient(socket: Socket, secret: ByteArray) {
        stats.connectionsTotal.incrementAndGet()
        stats.connectionsActive.incrementAndGet()
        val label = socket.remoteSocketAddress?.toString() ?: "?"
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val firstByte = input.read()
            if (firstByte == -1) return

            var cltInput: InputStream = input
            var cltOutput: OutputStream = output
            var handshake: ByteArray

            if (firstByte.toByte() == MtProtoConstants.TLS_RECORD_HANDSHAKE && config.fakeTlsDomain.isNotEmpty()) {
                val tlsResult = handleFakeTLS(firstByte.toByte(), input, output, secret, label)
                if (tlsResult == null) return
                cltInput = tlsResult.first; cltOutput = tlsResult.second; handshake = tlsResult.third
            } else if (config.fakeTlsDomain.isNotEmpty()) {
                output.write(("HTTP/1.1 301 Moved Permanently\r\nLocation: https://${config.fakeTlsDomain}/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                output.flush(); return
            } else {
                val rest = ByteArray(MtProtoConstants.HANDSHAKE_LEN - 1)
                readFully(input, rest)
                handshake = byteArrayOf(firstByte.toByte()) + rest
            }

            val result = tryHandshake(handshake, secret)
            if (result == null) {
                stats.connectionsBad.incrementAndGet()
                AppLogger.w(TAG, "[$label] Bad handshake (firstByte=0x${String.format("%02X", firstByte.toByte())})")
                try { val buf = ByteArray(4096); while (cltInput.read(buf) > 0) {} } catch (_: Exception) {}
                return
            }

            val (dc, isMedia, protoTag, clientDecPrekeyIv) = result
            val dcIdx: Short = if (isMedia) (-dc).toShort() else dc.toShort()

            val protoInt = when {
                protoTag.contentEquals(MtProtoConstants.PROTO_TAG_ABRIDGED) -> MtProtoConstants.PROTO_ABRIDGED_INT
                protoTag.contentEquals(MtProtoConstants.PROTO_TAG_INTERMEDIATE) -> MtProtoConstants.PROTO_INTERMEDIATE_INT
                protoTag.contentEquals(MtProtoConstants.PROTO_TAG_SECURE) -> MtProtoConstants.PROTO_PADDED_INTERMEDIATE_INT
                else -> MtProtoConstants.PROTO_ABRIDGED_INT
            }

            AppLogger.i(TAG, "[$label] handshake ok: DC$dc${if (isMedia) " media" else ""} proto=0x%08X".format(protoInt))

            val relayInit = generateRelayInit(protoTag, dcIdx)
            val ctx = buildCryptoCtx(clientDecPrekeyIv, secret, relayInit)
            val dcKey = "$dc${if (isMedia) "m" else ""}"
            val splitter = try { MsgSplitter(relayInit, protoInt) } catch (e: Exception) { null }

            if (dc !in config.dcRedirects) {
                doFallback(cltInput, cltOutput, relayInit, label, dc, isMedia, ctx, splitter)
                return
            }

            val now = System.currentTimeMillis()
            val failUntil = dcFailUntil[dcKey] ?: 0L
            val provenUntil = cfProvenUntil[dcKey] ?: 0L
            val domains = wsDomains(dc, isMedia)
            val target = config.dcRedirects[dc]!!

            // Fast path: pool hit (no need to race)
            wsPool.get(dc, isMedia, target, domains)?.let { ws ->
                stats.connectionsWs.incrementAndGet()
                AppLogger.i(TAG, "[$label] DC$dc${if (isMedia) " media" else ""} -> pool hit via $target")
                ws.send(relayInit)
                bridgeWsReencrypt(cltInput, cltOutput, ws, label, ctx, dc, isMedia, splitter)
                return
            }

            // CF-proven mode: skip direct, go straight to fallback
            if (now < provenUntil) {
                AppLogger.i(TAG, "[$label] DC$dc${if (isMedia) " media" else ""} -> CF proven active, skipping direct")
                doFallback(cltInput, cltOutput, relayInit, label, dc, isMedia, ctx, splitter)
                return
            }

            // RACE: try direct WS and CF fallback in parallel for fastest first-connect
            val directTmo = if (now < failUntil) WS_FAIL_TIMEOUT else config.handshakeTimeoutMs
            if (raceConnection(dc, isMedia, target, domains, directTmo, label, cltInput, cltOutput, relayInit, ctx, splitter)) {
                return
            }

            dcFailUntil[dcKey] = now + DC_FAIL_COOLDOWN
            AppLogger.w(TAG, "[$label] DC$dc${if (isMedia) " media" else ""} -> race lost, going to fallback")
            doFallback(cltInput, cltOutput, relayInit, label, dc, isMedia, ctx, splitter)
        } catch (e: CancellationException) {
            AppLogger.d(TAG, "[$label] handleClient cancelled (scope shutting down)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "[$label] handleClient error: ${e.message}", e)
        } finally {
            val newActive = stats.connectionsActive.decrementAndGet()
            if (newActive < 0) stats.connectionsActive.set(0)
            try { socket.close() } catch (e: Exception) { AppLogger.d(TAG, "[$label] Error closing client socket: ${e.message}") }
        }
    }

    /**
     * Background prewarm: test CF proxy for each DC so cfSuccessCount > 0 before any client connects.
     */
    private suspend fun prewarmCfPool() {
        if (!config.fallbackCfproxy) return
        val dcList = config.dcRedirects.keys.toList().ifEmpty { listOf(2, 4) }
        for (dc in dcList) {
            scope.launch(Dispatchers.IO) {
                testCfForDc(dc)
            }
        }
    }

    private fun testCfForDc(dc: Int) {
        val domainIterator = balancer.getDomainsForDc(dc)
        while (domainIterator.hasNext()) {
            val baseDomain = domainIterator.next()
            val domain = "kws$dc.$baseDomain"
            try {
                val ws = RawWebSocket.connect(domain, domain, 6_000, useDoH = false, retryMax = 1)
                if (ws != null) {
                    balancer.updateDomainForDc(dc, baseDomain)
                    ws.close()
                    val dcKey = "$dc"
                    cfSuccessCount[dcKey] = (cfSuccessCount[dcKey] ?: 0) + 1
                    AppLogger.i(TAG, "CF prewarm OK: DC$dc via $baseDomain")
                    return
                } else {
                    balancer.markDomainFailed(baseDomain, baseTtlMs = 60_000L)
                    AppLogger.d(TAG, "CF prewarm rejected $baseDomain, blacklisted 1min")
                }
            } catch (_: Exception) { balancer.markDomainFailed(baseDomain, baseTtlMs = 60_000L) }
        }
        AppLogger.d(TAG, "CF prewarm failed for DC$dc")
    }

    /**
     * Periodic cleanup: reset stale dcFailUntil entries and decay cfSuccessCount.
     * This prevents CF successes from permanently blocking direct connection attempts
     * when the network condition later improves.
     */
    private fun periodicCleanupLoop() {
        scope.launch {
            while (isActive()) {
                delay(60_000L)
                val now = System.currentTimeMillis()
                // 1. Remove expired direct-connection cooldowns and CF-proven windows
                dcFailUntil.entries.removeIf { it.value < now }
                cfProvenUntil.entries.removeIf { it.value < now }
                // 2. Decay CF success counters every 5 minutes so a single
                //    transient CF success doesn't bias the proxy forever.
                val lastDecay = cleanupLastDecay.getAndSet(now)
                if (now - lastDecay >= 300_000L) {
                    val iter = cfSuccessCount.iterator()
                    while (iter.hasNext()) {
                        val entry = iter.next()
                        val newVal = entry.value / 2
                        if (newVal <= 0) iter.remove() else entry.setValue(newVal)
                    }
                }
                AppLogger.d(TAG, "Periodic cleanup done: dcFail=${dcFailUntil.size} cfProven=${cfProvenUntil.size} cfDecay=${cfSuccessCount.size}")
            }
        }
    }

    private val cleanupLastDecay = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    private fun connectRawWsEnhanced(targetIp: String, domains: List<String>, timeout: Long, dc: Int, isMedia: Boolean, label: String, forFallback: Boolean = false): RawWebSocket? {
        val dcKey = "$dc${if (isMedia) "m" else ""}"
        // Use the passed timeout (already adjusted by caller based on failUntil)
        val tmo = timeout.coerceIn(2000, 8000)
        // 1. Standard connect with DoH + parallel IPs (fast single attempt)
        for (domain in domains) {
            AppLogger.d(TAG, "[$label] DC$dc connecting directly to $domain, timeout=$tmo")
            val ws = RawWebSocket.connect(
                targetIp, domain, tmo,
                useDoH = config.useDoH,
                dohEndpoints = config.dohEndpoints,
                retryMax = 1
            )
            if (ws != null) return ws
            stats.wsErrors.incrementAndGet()
        }

        // 2. If direct IP blocked, try DoH-resolved IP with SNI from domain
        if (config.autoFakeTls || config.fakeTlsDomain.isNotEmpty()) {
            val fakeDomain = if (config.fakeTlsDomain.isNotEmpty()) config.fakeTlsDomain else domains.firstOrNull() ?: return null
            for (domain in domains) {
                try {
                    val resolved = DoHResolver.resolve(domain, config.dohEndpoints, 3000, config.dohRotation)
                    for (ip in resolved) {
                        AppLogger.d(TAG, "[$label] DC$dc trying resolved IP $ip with SNI=$fakeDomain")
                        val ws = RawWebSocket.connect(ip, fakeDomain, tmo, useDoH = false, retryMax = 1)
                        if (ws != null) return ws
                    }
                } catch (e: Exception) { AppLogger.d(TAG, "DoH resolve fallback failed: ${e.message}") }
            }
        }
        return null
    }

    // ── WS Connection Pool ──────────────────────────────────────────

    private inner class WsPool {
        private val idle = ConcurrentHashMap<Pair<Int, Boolean>, LinkedBlockingDeque<Pair<RawWebSocket, Long>>>()
        private val refilling = ConcurrentHashMap<Pair<Int, Boolean>, Boolean>()
        private val refillExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

        fun get(dc: Int, isMedia: Boolean, targetIp: String, domains: List<String>): RawWebSocket? {
            val key = Pair(dc, isMedia)
            val bucket = idle[key] ?: return null.also { scheduleRefill(key, targetIp, domains) }
            val now = System.currentTimeMillis()
            while (true) {
                val pooled = bucket.poll() ?: break
                val age = now - pooled.second
                if (age > WS_POOL_MAX_AGE || pooled.first.isClosed) {
                    try { pooled.first.close() } catch (_: Exception) {}
                    continue
                }
                // Health-check: send PING and expect PONG within 3 seconds
                if (!pingPongCheck(pooled.first)) {
                    try { pooled.first.close() } catch (_: Exception) {}
                    continue
                }
                stats.poolHits.incrementAndGet()
                scheduleRefill(key, targetIp, domains)
                return pooled.first
            }
            stats.poolMisses.incrementAndGet()
            scheduleRefill(key, targetIp, domains)
            return null
        }

        private fun pingPongCheck(ws: RawWebSocket): Boolean {
            // Simple check: not closed and not idle for too long.
            // Full blocking ping-pong is unreliable because recv() handles PONG internally.
            return !ws.isClosed
        }

        private fun scheduleRefill(key: Pair<Int, Boolean>, targetIp: String, domains: List<String>) {
            if (refilling.putIfAbsent(key, true) != null) return
            scope.launch {
                try { refill(key, targetIp, domains) }
                finally { refilling.remove(key) }
            }
        }

        private fun refill(key: Pair<Int, Boolean>, targetIp: String, domains: List<String>) {
            val (dc, isMedia) = key
            val bucket = idle.getOrPut(key) { LinkedBlockingDeque() }
            val needed = config.poolSize - bucket.size
            if (needed <= 0) return
            val futures = mutableListOf<java.util.concurrent.Future<*>>()
            for (i in 0 until needed) {
                val future = refillExecutor.submit {
                    val ws = connectRawWsEnhanced(targetIp, domains, 8000, dc, isMedia, "pool")
                    if (ws != null) {
                        // Ping check before adding to pool
                        if (pingPongCheck(ws)) {
                            bucket.put(Pair(ws, System.currentTimeMillis()))
                        } else {
                            try { ws.close() } catch (_: Exception) {}
                        }
                    }
                }
                futures.add(future)
            }
            for (f in futures) { try { f.get(10_000, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {} }
            Log.d(TAG, "WS pool refilled DC$dc${if (isMedia) "m" else ""}: ${bucket.size} ready")
        }

        fun warmup(dcRedirects: Map<Int, String>) {
            for ((dc, targetIp) in dcRedirects) {
                for (isMedia in listOf(false, true)) {
                    scheduleRefill(Pair(dc, isMedia), targetIp, wsDomains(dc, isMedia))
                }
            }
            Log.i(TAG, "WS pool warmup started for ${dcRedirects.size} DC(s)")
        }

        fun reset() {
            for ((_, bucket) in idle) {
                for (pooled in bucket) { try { pooled.first.close() } catch (_: Exception) {} }
            }
            idle.clear(); refilling.clear()
        }
    }

    // ── Handshake & Crypto ─────────────────────────────────────────

    private data class HandshakeResult(val dc: Int, val isMedia: Boolean, val protoTag: ByteArray, val clientDecPrekeyIv: ByteArray)

    private fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeResult? {
        if (handshake.size < MtProtoConstants.HANDSHAKE_LEN) return null
        val end = MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        val decPrekeyAndIv = handshake.copyOfRange(MtProtoConstants.SKIP_LEN, end)
        val decPrekey = decPrekeyAndIv.copyOfRange(0, MtProtoConstants.PREKEY_LEN)
        val decIv = decPrekeyAndIv.copyOfRange(MtProtoConstants.PREKEY_LEN, decPrekeyAndIv.size)
        val decKey = MessageDigest.getInstance("SHA-256").digest(decPrekey + secret)
        val iv16 = ByteArray(16)
        val ivInt = BigInteger(decIv)
        val ivBytes = ivInt.toByteArray(MtProtoConstants.IV_LEN)
        System.arraycopy(ivBytes, 0, iv16, 0, MtProtoConstants.IV_LEN)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decKey, "AES"), IvParameterSpec(iv16))
        val decrypted = cipher.doFinal(handshake)
        val protoTag = decrypted.copyOfRange(MtProtoConstants.PROTO_TAG_POS, MtProtoConstants.PROTO_TAG_POS + 4)
        if (!protoTag.contentEquals(MtProtoConstants.PROTO_TAG_ABRIDGED) &&
            !protoTag.contentEquals(MtProtoConstants.PROTO_TAG_INTERMEDIATE) &&
            !protoTag.contentEquals(MtProtoConstants.PROTO_TAG_SECURE)) return null
        val dcIdxShort = ByteBuffer.wrap(decrypted, MtProtoConstants.DC_IDX_POS, 2).order(ByteOrder.LITTLE_ENDIAN).short
        val dc = abs(dcIdxShort.toInt())
        val isMedia = dcIdxShort < 0
        return HandshakeResult(dc, isMedia, protoTag, decPrekeyAndIv)
    }

    private fun generateRelayInit(protoTag: ByteArray, dcIdx: Short): ByteArray {
        val rnd = ByteArray(MtProtoConstants.HANDSHAKE_LEN)
        while (true) {
            SecureRandom().nextBytes(rnd)
            if (rnd[0] in MtProtoConstants.RESERVED_FIRST_BYTES) continue
            if (MtProtoConstants.RESERVED_STARTS.any { rnd.copyOfRange(0, 4).contentEquals(it) }) continue
            if (rnd.copyOfRange(4, 8).contentEquals(MtProtoConstants.RESERVED_CONTINUE)) continue
            break
        }
        val encKey = rnd.copyOfRange(MtProtoConstants.SKIP_LEN, MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN)
        val encIv = rnd.copyOfRange(MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN, MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN)
        val encryptor = Cipher.getInstance("AES/CTR/NoPadding")
        encryptor.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(encIv.copyOf(16)))
        val encryptedFull = encryptor.update(rnd)
        val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dcIdx).array()
        val tailPlain = protoTag + dcBytes + ByteArray(2).also { SecureRandom().nextBytes(it) }
        val encryptedTail = ByteArray(8)
        for (i in 0 until 8) {
            encryptedTail[i] = (tailPlain[i].toInt() xor (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt())).toByte()
        }
        val result = rnd.copyOf()
        System.arraycopy(encryptedTail, 0, result, MtProtoConstants.PROTO_TAG_POS, 8)
        return result
    }

    data class CryptoCtx(val cltDec: Cipher, val cltEnc: Cipher, val tgEnc: Cipher, val tgDec: Cipher)

    private fun buildCryptoCtx(clientDecPrekeyIv: ByteArray, secret: ByteArray, relayInit: ByteArray): CryptoCtx {
        fun makeCipher(key: ByteArray, iv: ByteArray, skipZero64: Boolean = false): Cipher {
            val iv16: ByteArray = if (iv.size < 16) {
                val b = ByteArray(16); System.arraycopy(iv, 0, b, 16 - iv.size, iv.size); b
            } else iv
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv16))
            if (skipZero64) c.update(MtProtoConstants.ZERO_64)
            return c
        }
        val cltDecPrekey = clientDecPrekeyIv.copyOfRange(0, MtProtoConstants.PREKEY_LEN)
        val cltDecIv = clientDecPrekeyIv.copyOfRange(MtProtoConstants.PREKEY_LEN, clientDecPrekeyIv.size)
        val cltDecKey = MessageDigest.getInstance("SHA-256").digest(cltDecPrekey + secret)
        val cltDecryptor = makeCipher(cltDecKey, cltDecIv, skipZero64 = true)

        val cltEncPrekeyIv = clientDecPrekeyIv.reversedArray()
        val cltEncKey = MessageDigest.getInstance("SHA-256").digest(cltEncPrekeyIv.copyOfRange(0, MtProtoConstants.PREKEY_LEN) + secret)
        val cltEncIv = cltEncPrekeyIv.copyOfRange(MtProtoConstants.PREKEY_LEN, cltEncPrekeyIv.size)
        val cltEncryptor = makeCipher(cltEncKey, cltEncIv)

        val relayEncKey = relayInit.copyOfRange(MtProtoConstants.SKIP_LEN, MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN)
        val relayEncIv = relayInit.copyOfRange(MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN, MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN)
        val tgEncryptor = makeCipher(relayEncKey, relayEncIv, skipZero64 = true)

        val relayDecPrekeyIv = relayInit.copyOfRange(MtProtoConstants.SKIP_LEN, MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN).reversedArray()
        val relayDecKey = relayDecPrekeyIv.copyOfRange(0, MtProtoConstants.KEY_LEN)
        val relayDecIv = relayDecPrekeyIv.copyOfRange(MtProtoConstants.KEY_LEN, relayDecPrekeyIv.size)
        val tgDecryptor = makeCipher(relayDecKey, relayDecIv)

        return CryptoCtx(cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
    }

    private fun wsDomains(dc: Int, isMedia: Boolean?): List<String> {
        val effectiveDc = if (dc == 203) 2 else dc
        return if (isMedia == null || isMedia)
            listOf("kws${effectiveDc}-1.web.telegram.org", "kws${effectiveDc}.web.telegram.org")
        else
            listOf("kws${effectiveDc}.web.telegram.org", "kws${effectiveDc}-1.web.telegram.org")
    }

    // ── Bridge ─────────────────────────────────────────────────────

    private suspend fun bridgeWsReencrypt(
        cltInput: InputStream, cltOutput: OutputStream,
        ws: RawWebSocket, label: String, ctx: CryptoCtx,
        dc: Int, isMedia: Boolean, splitter: MsgSplitter?
    ) {
        var upBytes = 0L; var downBytes = 0L; val startTime = System.currentTimeMillis()
        val lastPong = AtomicBoolean(true)
        try {
            val upJob = scope.launch {
                try {
                    val buf = ByteArray(65536)
                    while (isActive()) {
                        val n = cltInput.read(buf); if (n <= 0) break
                        stats.bytesUp.addAndGet(n.toLong()); upBytes += n
                        val plain = ctx.cltDec.update(buf.copyOf(n))
                        val encrypted = ctx.tgEnc.update(plain)
                        if (splitter != null) {
                            val parts = splitter.split(encrypted)
                            if (parts.size > 1) ws.sendBatch(parts)
                            else if (parts.isNotEmpty()) ws.send(parts[0])
                        } else {
                            ws.send(encrypted)
                        }
                    }
                    if (splitter != null) {
                        val tail = splitter.flush()
                        for (part in tail) ws.send(part)
                    }
                } catch (e: Exception) { AppLogger.d(TAG, "[$label] WS bridge uplink error: ${e.message}") }
            }
            val downJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive()) {
                        val data = ws.recv() ?: break
                        // PONG check reset
                        if (data.isNotEmpty()) lastPong.set(true)
                        stats.bytesDown.addAndGet(data.size.toLong()); downBytes += data.size
                        val plain = ctx.tgDec.update(data)
                        val encrypted = ctx.cltEnc.update(plain)
                        cltOutput.write(encrypted); cltOutput.flush()
                    }
                } catch (e: Exception) { AppLogger.d(TAG, "[$label] WS bridge downlink error: ${e.message}") }
            }
            val keepAliveJob = scope.launch {
                try {
                    while (isActive()) {
                        delay(KEEPALIVE_INTERVAL_MS)
                        if (!lastPong.get()) {
                            AppLogger.w(TAG, "[$label] WS keepalive timeout, closing")
                            ws.close()
                            break
                        }
                        lastPong.set(false)
                        ws.ping(byteArrayOf(0x01, 0x02, 0x03, 0x04))
                        AppLogger.d(TAG, "[$label] WS keepalive PING sent")
                    }
                } catch (_: CancellationException) {}
            }
            upJob.join(); downJob.cancelAndJoin(); keepAliveJob.cancel()
        } finally {
            ws.close()
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            AppLogger.i(TAG, "[$label] DC$dc${if (isMedia) "m" else ""} WS done: ^${MtProtoConstants.humanBytes(upBytes)} v${MtProtoConstants.humanBytes(downBytes)} ${"%.1f".format(elapsed)}s")
        }
    }

    private suspend fun bridgeTcpReencrypt(
        cltInput: InputStream, cltOutput: OutputStream,
        remoteInput: InputStream, remoteOutput: OutputStream,
        ctx: CryptoCtx
    ) {
        try {
            val upJob = scope.launch {
                try {
                    val buf = ByteArray(65536)
                    while (isActive()) {
                        val n = cltInput.read(buf); if (n <= 0) break
                        stats.bytesUp.addAndGet(n.toLong())
                        val plain = ctx.cltDec.update(buf.copyOf(n))
                        val encrypted = ctx.tgEnc.update(plain)
                        remoteOutput.write(encrypted); remoteOutput.flush()
                    }
                } catch (e: Exception) { AppLogger.d(TAG, "TCP bridge uplink error: ${e.message}") }
            }
            val downJob = scope.launch {
                try {
                    val buf = ByteArray(65536)
                    while (isActive()) {
                        val n = remoteInput.read(buf); if (n <= 0) break
                        stats.bytesDown.addAndGet(n.toLong())
                        val plain = ctx.tgDec.update(buf.copyOf(n))
                        val encrypted = ctx.cltEnc.update(plain)
                        cltOutput.write(encrypted); cltOutput.flush()
                    }
                } catch (e: Exception) { AppLogger.d(TAG, "TCP bridge downlink error: ${e.message}") }
            }
            upJob.join(); downJob.cancelAndJoin()
        } finally {}
    }

    // ── Fallback ───────────────────────────────────────────────────

    /**
     * Race direct WS vs CF fallback. Returns true if a winner bridged and returned.
     * Uses AtomicBoolean winner so the loser releases input/output cleanly.
     */
    private suspend fun raceConnection(
        dc: Int, isMedia: Boolean, targetIp: String, domains: List<String>,
        directTmo: Long, label: String,
        cltInput: InputStream, cltOutput: OutputStream,
        relayInit: ByteArray, ctx: CryptoCtx, splitter: MsgSplitter?
    ): Boolean {
        val winner = AtomicBoolean(false)
        var directWs: RawWebSocket? = null
        var cfResult: Triple<Boolean, RawWebSocket?, String?>? = null

        val directJob = scope.launch(Dispatchers.IO) {
            val ws = connectRawWsEnhanced(targetIp, domains, directTmo, dc, isMedia, label)
            if (ws != null && winner.compareAndSet(false, true)) {
                directWs = ws
            } else {
                ws?.close()
            }
        }

        val cfJob = scope.launch(Dispatchers.IO) {
            // CF fallback is slightly slower to establish due to domain resolution,
            // so stagger it by 150ms unless direct is already failing fast.
            delay(150)
            // Only race CF if it's actually enabled
            if (!config.fallbackCfproxy && !config.mediaViaCf) return@launch
            if (winner.get()) return@launch
            val ok = cfproxyConnectOnly(dc, isMedia, label)
            if (ok != null && winner.compareAndSet(false, true)) {
                cfResult = ok
            }
        }

        directJob.join(); cfJob.join()
        if (directWs != null) {
            stats.connectionsWs.incrementAndGet()
            AppLogger.i(TAG, "[$label] DC$dc${if (isMedia) " media" else ""} -> RACE WON: direct")
            dcFailUntil.remove("$dc${if (isMedia) "m" else ""}")
            directWs!!.send(relayInit)
            bridgeWsReencrypt(cltInput, cltOutput, directWs!!, label, ctx, dc, isMedia, splitter)
            return true
        }
        if (cfResult != null) {
            val (_, ws, baseDomain) = cfResult!!
            ws!!.send(relayInit)
            val dcKey = "$dc${if (isMedia) "m" else ""}"
            cfSuccessCount[dcKey] = (cfSuccessCount[dcKey] ?: 0) + 1
            val count = cfSuccessCount[dcKey] ?: 0
            if (count >= CF_PROVEN_MIN) cfProvenUntil[dcKey] = System.currentTimeMillis() + CF_PROVEN_TTL_MS
            AppLogger.i(TAG, "[$label] DC$dc${if (isMedia) " media" else ""} -> RACE WON: CF (#$count via $baseDomain)")
            stats.connectionsCfproxy.incrementAndGet()
            bridgeWsReencrypt(cltInput, cltOutput, ws, label, ctx, dc, isMedia, splitter)
            return true
        }
        return false
    }

    /**
     * Connect CF proxy for a DC and update balancer on success.
     * Returns Triple(success, ws, baseDomain) or null if no CF domain works.
     */
    private fun cfproxyConnectOnly(dc: Int, isMedia: Boolean, label: String): Triple<Boolean, RawWebSocket?, String?>? {
        val domainIterator = balancer.getDomainsForDc(dc)
        while (domainIterator.hasNext()) {
            val baseDomain = domainIterator.next()
            val domain = "kws$dc.$baseDomain"
            try {
                val ws = RawWebSocket.connect(domain, domain, 6_000, useDoH = false, retryMax = 1)
                if (ws != null) {
                    balancer.updateDomainForDc(dc, baseDomain)
                    return Triple(true, ws, baseDomain)
                } else {
                    balancer.markDomainFailed(baseDomain, baseTtlMs = 120_000L)
                    AppLogger.w(TAG, "[$label] DC$dc CF proxy rejected/hit rate-limit by $baseDomain, blacklisted 2min")
                }
            } catch (_: CancellationException) { return null }
            catch (e: Exception) {
                AppLogger.w(TAG, "[$label] DC$dc CF proxy failed: ${e.message}")
                balancer.markDomainFailed(baseDomain)
            }
        }
        return null
    }

    private suspend fun doFallback(cltInput: InputStream, cltOutput: OutputStream, relayInit: ByteArray, label: String, dc: Int, isMedia: Boolean, ctx: CryptoCtx, splitter: MsgSplitter?) {
        val fallbackDst = ProxyConfig.DC_DEFAULT_IPS[dc]
        val useCf = config.fallbackCfproxy || config.mediaViaCf
        val cfFirst = config.fallbackCfproxyPriority

        if (!config.parallelConnect || (!useCf || fallbackDst == null)) {
            // Sequential fallback
            val methods = mutableListOf<String>()
            if (useCf && cfFirst) methods.add("cf")
            if (fallbackDst != null) methods.add("tcp")
            if (useCf && !cfFirst) methods.add("cf")
            for (method in methods) {
                if (method == "cf") {
                    if (cfproxyFallback(cltInput, cltOutput, relayInit, label, ctx, dc, isMedia, splitter)) return
                } else if (method == "tcp" && fallbackDst != null) {
                    if (tcpFallback(cltInput, cltOutput, fallbackDst, 443, relayInit, ctx)) return
                }
            }
        } else {
            // Parallel fallback: try CF and TCP at the same time, use first success
            // Short-cut: if CF already proven to work for this DC, skip TCP entirely
            // CF is now always tried alongside direct; we no longer skip direct permanently
            val cfDone = AtomicBoolean(false)
            val tcpDone = AtomicBoolean(false)
            val winner = AtomicBoolean(false)
            AppLogger.i(TAG, "[$label] DC$dc parallel fallback: CF + TCP")

            val cfJob = scope.launch(Dispatchers.IO) {
                if (winner.get()) return@launch
                if (cfproxyFallback(cltInput, cltOutput, relayInit, label, ctx, dc, isMedia, splitter)) {
                    cfDone.set(true)
                    winner.set(true)
                }
            }
            val tcpJob = scope.launch(Dispatchers.IO) {
                delay(200)
                if (winner.get()) return@launch
                if (tcpFallback(cltInput, cltOutput, fallbackDst!!, 443, relayInit, ctx)) {
                    tcpDone.set(true)
                    winner.set(true)
                }
            }
            cfJob.join(); tcpJob.join()
            if (!winner.get()) {
                AppLogger.w(TAG, "[$label] DC$dc both fallback methods failed")
            }
        }
    }

    private suspend fun cfproxyFallback(cltInput: InputStream, cltOutput: OutputStream, relayInit: ByteArray, label: String, ctx: CryptoCtx, dc: Int, isMedia: Boolean, splitter: MsgSplitter?): Boolean {
        val dcKey = "$dc${if (isMedia) "m" else ""}"
        AppLogger.i(TAG, "[$label] DC$dc -> trying CF proxy")
        val domainIterator = balancer.getDomainsForDc(dc)
        while (domainIterator.hasNext()) {
            val baseDomain = domainIterator.next()
            val domain = "kws$dc.$baseDomain"
            try {
                val ws = RawWebSocket.connect(domain, domain, 6_000, useDoH = false, retryMax = 1)
                if (ws != null) {
                    balancer.updateDomainForDc(dc, baseDomain)
                    cfSuccessCount[dcKey] = (cfSuccessCount[dcKey] ?: 0) + 1
                    val count = cfSuccessCount[dcKey] ?: 0
                    // Activate CF-proven mode once enough successes accumulate
                    if (count >= CF_PROVEN_MIN) {
                        cfProvenUntil[dcKey] = System.currentTimeMillis() + CF_PROVEN_TTL_MS
                    }
                    AppLogger.i(TAG, "[$label] DC$dc CF success #$count via $baseDomain")
                    stats.connectionsCfproxy.incrementAndGet()
                    ws.send(relayInit)
                    bridgeWsReencrypt(cltInput, cltOutput, ws, label, ctx, dc, isMedia, splitter)
                    return true
                } else {
                    // WS establishment succeeded but handshake was rejected (429, 403, etc.)
                    // Treat as a soft-failure with shorter blacklist TTL to avoid hammering
                    balancer.markDomainFailed(baseDomain, baseTtlMs = 120_000L)
                    AppLogger.w(TAG, "[$label] DC$dc CF proxy rejected/hit rate-limit by $baseDomain, blacklisted 2min")
                }
            } catch (e: CancellationException) {
                AppLogger.d(TAG, "[$label] DC$dc CF fallback cancelled")
                return false
            } catch (e: Exception) {
                AppLogger.w(TAG, "[$label] DC$dc CF proxy failed: ${e.message}")
                balancer.markDomainFailed(baseDomain)
            }
        }
        return false
    }

    private suspend fun tcpFallback(cltInput: InputStream, cltOutput: OutputStream, dstIp: String, dstPort: Int, relayInit: ByteArray, ctx: CryptoCtx): Boolean {
        try {
            val remote = Socket()
            remote.tcpNoDelay = true
            remote.sendBufferSize = config.bufferSize
            remote.receiveBufferSize = config.bufferSize
            remote.connect(InetSocketAddress(dstIp, dstPort), 3_000)
            val remoteInput = remote.getInputStream()
            val remoteOutput = remote.getOutputStream()
            stats.connectionsTcpFallback.incrementAndGet()
            remoteOutput.write(relayInit); remoteOutput.flush()
            try { bridgeTcpReencrypt(cltInput, cltOutput, remoteInput, remoteOutput, ctx) }
            finally { try { remote.close() } catch (e: Exception) {} }
            return true
        } catch (e: CancellationException) {
            AppLogger.d(TAG, "TCP fallback cancelled")
            return false
        } catch (e: Exception) {
            AppLogger.w(TAG, "TCP fallback to $dstIp:$dstPort failed: ${e.message}")
            return false
        }
    }

    // ── Fake TLS ───────────────────────────────────────────────────

    private fun handleFakeTLS(firstByte: Byte, input: InputStream, output: OutputStream, secret: ByteArray, label: String): Triple<InputStream, OutputStream, ByteArray>? {
        AppLogger.d(TAG, "[$label] FakeTLS handshake started")
        val hdrRest = ByteArray(4); readFully(input, hdrRest)
        val tlsHeader = byteArrayOf(firstByte) + hdrRest
        val recordLen = ((tlsHeader[3].toInt() and 0xFF) shl 8) or (tlsHeader[4].toInt() and 0xFF)
        val recordBody = ByteArray(recordLen); readFully(input, recordBody)
        val clientHello = tlsHeader + recordBody
        val tlsResult = verifyClientHello(clientHello, secret)
        if (tlsResult == null) {
            AppLogger.w(TAG, "[$label] FakeTLS verification failed, sending 301 redirect")
            try { output.write(("HTTP/1.1 301 Moved Permanently\r\nLocation: https://${config.fakeTlsDomain}/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()); output.flush() } catch (e: Exception) {}
            return null
        }
        val (clientRandom, sessionId) = tlsResult
        val serverHello = buildServerHello(secret, clientRandom, sessionId)
        output.write(serverHello); output.flush()
        val tlsInput = FakeTlsInputStream(input)
        val tlsOutput = FakeTlsOutputStream(output)
        val handshake = ByteArray(MtProtoConstants.HANDSHAKE_LEN)
        readFully(tlsInput, handshake)
        return Triple(tlsInput, tlsOutput, handshake)
    }

    private data class TlsVerifyResult(val clientRandom: ByteArray, val sessionId: ByteArray)

    private fun verifyClientHello(data: ByteArray, secret: ByteArray): TlsVerifyResult? {
        if (data.size < 43 || data[0] != MtProtoConstants.TLS_RECORD_HANDSHAKE || data[5] != 0x01.toByte()) return null
        val clientRandom = data.copyOfRange(MtProtoConstants.CLIENT_RANDOM_OFFSET, MtProtoConstants.CLIENT_RANDOM_OFFSET + MtProtoConstants.CLIENT_RANDOM_LEN)
        val zeroed = data.copyOf()
        java.util.Arrays.fill(zeroed, MtProtoConstants.CLIENT_RANDOM_OFFSET, MtProtoConstants.CLIENT_RANDOM_OFFSET + MtProtoConstants.CLIENT_RANDOM_LEN, 0)
        val expected = Mac.getInstance("HmacSHA256").let { it.init(SecretKeySpec(secret, "HmacSHA256")); it.doFinal(zeroed) }
        for (i in 0 until 28) { if (clientRandom[i] != expected[i]) return null }
        val tsXor = ByteArray(4)
        for (i in 0 until 4) { tsXor[i] = (clientRandom[28 + i].toInt() xor expected[28 + i].toInt()).toByte() }
        val timestamp = ByteBuffer.wrap(tsXor).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        if (abs(System.currentTimeMillis() / 1000 - timestamp) > MtProtoConstants.TIMESTAMP_TOLERANCE) return null
        var sessionId = ByteArray(MtProtoConstants.SESSION_ID_LEN)
        if (data.size >= MtProtoConstants.SESSION_ID_OFFSET + MtProtoConstants.SESSION_ID_LEN && data[43] == 0x20.toByte())
            sessionId = data.copyOfRange(MtProtoConstants.SESSION_ID_OFFSET, MtProtoConstants.SESSION_ID_OFFSET + MtProtoConstants.SESSION_ID_LEN)
        return TlsVerifyResult(clientRandom, sessionId)
    }

    private fun buildServerHello(secret: ByteArray, clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val sh = ByteArray(126)
        sh[0] = 0x16; sh[1] = 0x03; sh[2] = 0x03; sh[3] = 0x00; sh[4] = 0x7A.toByte()
        sh[5] = 0x02; sh[6] = 0x00; sh[7] = 0x00; sh[8] = 0x76.toByte()
        sh[9] = 0x03; sh[10] = 0x03; sh[43] = 0x20
        System.arraycopy(sessionId, 0, sh, 44, minOf(sessionId.size, 32))
        sh[76] = 0x13.toByte(); sh[77] = 0x01.toByte(); sh[78] = 0x00; sh[79] = 0x00; sh[80] = 0x2E.toByte()
        sh[81] = 0x00; sh[82] = 0x33.toByte(); sh[83] = 0x00; sh[84] = 0x24.toByte()
        sh[85] = 0x00; sh[86] = 0x1D.toByte(); sh[87] = 0x00; sh[88] = 0x20
        val pubKey = ByteArray(32); SecureRandom().nextBytes(pubKey); System.arraycopy(pubKey, 0, sh, 89, 32)
        sh[121] = 0x00; sh[122] = 0x2B.toByte(); sh[123] = 0x00; sh[124] = 0x02; sh[125] = 0x03
        val ccs = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
        val encSize = 1900 + java.util.Random().nextInt(201)
        val encrypted = ByteArray(encSize); java.util.Random().nextBytes(encrypted)
        val appRecord = ByteArray(5 + encSize)
        appRecord[0] = 0x17; appRecord[1] = 0x03; appRecord[2] = 0x03
        appRecord[3] = (encSize shr 8).toByte(); appRecord[4] = (encSize and 0xFF).toByte()
        System.arraycopy(encrypted, 0, appRecord, 5, encSize)
        val response = sh + ccs + appRecord
        val serverRandom = Mac.getInstance("HmacSHA256").let { it.init(SecretKeySpec(secret, "HmacSHA256")); it.doFinal(clientRandom + response) }
        val result = response.copyOf()
        System.arraycopy(serverRandom, 0, result, 11, minOf(32, serverRandom.size))
        return result
    }

    class FakeTlsInputStream(private val source: InputStream) : InputStream() {
        private val buffer = java.io.ByteArrayOutputStream(); private var readLeft = 0
        override fun read(): Int { val b = ByteArray(1); val n = read(b, 0, 1); return if (n <= 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (buffer.size() >= len) { val d = buffer.toByteArray(); System.arraycopy(d, 0, b, off, len); buffer.reset(); for (i in len until d.size) buffer.write(d[i].toInt()); return len }
            fillBuffer(); if (buffer.size() == 0) return -1
            val toRead = minOf(len, buffer.size()); val d = buffer.toByteArray(); System.arraycopy(d, 0, b, off, toRead); buffer.reset()
            for (i in toRead until d.size) buffer.write(d[i].toInt()); return toRead
        }
        private fun fillBuffer() { while (buffer.size() < 1) { try { val p = readTlsPayload() ?: return; buffer.write(p) } catch (e: Exception) { return } } }
        private fun readFullySrc(buf: ByteArray) { var o = 0; while (o < buf.size) { val n = source.read(buf, o, buf.size - o); if (n <= 0) throw EOFException("EOF"); o += n } }
        private fun readTlsPayload(): ByteArray? {
            if (readLeft > 0) { val data = ByteArray(readLeft); val n = source.read(data); if (n <= 0) return null; readLeft -= n; return data.copyOf(n) }
            val hdr = ByteArray(5); readFullySrc(hdr); val rtype = hdr[0]; val recLen = ((hdr[3].toInt() and 0xFF) shl 8) or (hdr[4].toInt() and 0xFF)
            if (rtype == MtProtoConstants.TLS_RECORD_CCS) { if (recLen > 0) readFullySrc(ByteArray(recLen)); return readTlsPayload() }
            if (rtype != MtProtoConstants.TLS_RECORD_APPDATA) return null
            val data = ByteArray(recLen); readFullySrc(data); return data
        }
    }

    class FakeTlsOutputStream(private val dest: OutputStream) : OutputStream() {
        override fun write(b: Int) { write(byteArrayOf(b.toByte())) }
        override fun write(b: ByteArray) {
            var offset = 0
            while (offset < b.size) {
                val chunkLen = minOf(b.size - offset, MtProtoConstants.TLS_APPDATA_MAX)
                dest.write(byteArrayOf(MtProtoConstants.TLS_RECORD_APPDATA, 0x03, 0x03, (chunkLen shr 8).toByte(), (chunkLen and 0xFF).toByte()))
                dest.write(b, offset, chunkLen); offset += chunkLen
            }
            dest.flush()
        }
        override fun flush() { dest.flush() }
    }

    private fun readFully(input: InputStream, buf: ByteArray) { var o = 0; while (o < buf.size) { val n = input.read(buf, o, buf.size - o); if (n <= 0) throw EOFException("EOF"); o += n } }
    private fun readFully(input: FakeTlsInputStream, buf: ByteArray) { var o = 0; while (o < buf.size) { val n = input.read(buf, o, buf.size - o); if (n <= 0) throw EOFException("EOF"); o += n } }
    private fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

private fun java.math.BigInteger.toByteArray(size: Int): ByteArray {
    val bytes = this.toByteArray()
    if (bytes.size == size) return bytes
    val result = ByteArray(size)
    if (bytes.size > size) System.arraycopy(bytes, bytes.size - size, result, 0, size)
    else System.arraycopy(bytes, 0, result, size - bytes.size, bytes.size)
    return result
}

private fun BigInteger(bytes: ByteArray) = java.math.BigInteger(1, bytes)
