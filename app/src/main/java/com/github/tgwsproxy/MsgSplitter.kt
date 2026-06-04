package com.github.tgwsproxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Splits TCP stream data into individual MTProto transport packets
 * so each can be sent as a separate WS frame.
 * Uses offset-pointer approach to avoid O(N^2) from front-deletion.
 */
class MsgSplitter(relayInit: ByteArray, protoInt: Int) {

    private val dec: Cipher
    private val proto: Int
    private var cipherBuf: ByteArray = ByteArray(0)
    private var plainBuf: ByteArray = ByteArray(0)
    private var offset = 0
    var disabled = false
        private set

    init {
        val key = relayInit.copyOfRange(8, 40)
        val iv = relayInit.copyOfRange(40, 56)
        val iv16 = ByteArray(16)
        System.arraycopy(iv, 0, iv16, 0, 16.coerceAtMost(iv.size))
        dec = Cipher.getInstance("AES/CTR/NoPadding")
        dec.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv16))
        dec.update(MtProtoConstants.ZERO_64)
        proto = protoInt
    }

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        // Append to buffers
        cipherBuf = concatOffset(cipherBuf, offset, chunk)
        val decrypted = dec.update(chunk)
        plainBuf = concatOffset(plainBuf, offset, decrypted)
        offset = 0  // reset offset after compaction

        val parts = mutableListOf<ByteArray>()
        var pos = 0
        val bufLen = cipherBuf.size
        while (pos < bufLen) {
            val avail = bufLen - pos
            val packetLen = nextPacketLen(pos, avail) ?: break
            if (packetLen <= 0) {
                parts.add(cipherBuf.copyOfRange(pos, bufLen))
                cipherBuf = ByteArray(0); plainBuf = ByteArray(0)
                disabled = true
                return parts
            }
            parts.add(cipherBuf.copyOfRange(pos, pos + packetLen))
            pos += packetLen
        }

        // Trim consumed prefix
        if (pos > 0) {
            cipherBuf = cipherBuf.copyOfRange(pos, cipherBuf.size)
            plainBuf = plainBuf.copyOfRange(pos, plainBuf.size)
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = cipherBuf
        cipherBuf = ByteArray(0)
        plainBuf = ByteArray(0)
        return listOf(tail)
    }

    private fun nextPacketLen(pos: Int, avail: Int): Int? {
        if (avail <= 0) return null
        return when (proto) {
            MtProtoConstants.PROTO_ABRIDGED_INT -> nextAbridgedLen(pos, avail)
            MtProtoConstants.PROTO_INTERMEDIATE_INT,
            MtProtoConstants.PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediateLen(pos, avail)
            else -> 0
        }
    }

    private fun nextAbridgedLen(pos: Int, avail: Int): Int? {
        if (avail <= 0 || pos >= plainBuf.size) return null
        val first = plainBuf[pos].toInt() and 0xFF
        if (first == 0x7F || first == 0xFF) {
            if (avail < 4) return null
            val payloadLen = (((plainBuf[pos + 3].toInt() and 0xFF) shl 16) or
                              ((plainBuf[pos + 2].toInt() and 0xFF) shl 8) or
                              (plainBuf[pos + 1].toInt() and 0xFF)) * 4
            if (payloadLen <= 0) return 0
            val packetLen = 4 + payloadLen
            if (avail < packetLen) return null
            return packetLen
        } else {
            val payloadLen = (first and 0x7F) * 4
            if (payloadLen <= 0) return 0
            val packetLen = 1 + payloadLen
            if (avail < packetLen) return null
            return packetLen
        }
    }

    private fun nextIntermediateLen(pos: Int, avail: Int): Int? {
        if (avail < 4) return null
        val payloadLen = (((plainBuf[pos + 3].toInt() and 0xFF) shl 24) or
                          ((plainBuf[pos + 2].toInt() and 0xFF) shl 16) or
                          ((plainBuf[pos + 1].toInt() and 0xFF) shl 8) or
                          (plainBuf[pos].toInt() and 0xFF)) and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (avail < packetLen) return null
        return packetLen
    }

    private companion object {
        /** Append 'data' to 'buf' but skip first 'offset' bytes (compaction). */
        fun concatOffset(buf: ByteArray, offset: Int, data: ByteArray): ByteArray {
            val remaining = buf.size - offset
            val result = ByteArray(remaining + data.size)
            if (remaining > 0) System.arraycopy(buf, offset, result, 0, remaining)
            System.arraycopy(data, 0, result, remaining, data.size)
            return result
        }
    }
}
