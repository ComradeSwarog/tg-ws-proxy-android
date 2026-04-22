package com.github.tgwsproxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Splits TCP stream data into individual MTProto transport packets
 * so each can be sent as a separate WS frame.
 * Ported from proxy/bridge.py MsgSplitter class.
 */
class MsgSplitter(relayInit: ByteArray, protoInt: Int) {

    private val dec: Cipher
    private val proto: Int
    private val cipherBuf = ByteArrayDeque()
    private val plainBuf = ByteArrayDeque()
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

    /**
     * Feed a chunk of encrypted data and return list of packet-sized frames.
     * Returns empty list if not enough data to form a complete packet.
     */
    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuf.write(chunk)
        plainBuf.write(dec.update(chunk))

        val parts = mutableListOf<ByteArray>()
        while (cipherBuf.size > 0) {
            val packetLen = nextPacketLen() ?: break
            if (packetLen <= 0) {
                parts.add(cipherBuf.toByteArray())
                cipherBuf.clear()
                plainBuf.clear()
                disabled = true
                break
            }
            parts.add(cipherBuf.read(packetLen))
            plainBuf.skip(packetLen)
        }
        return parts
    }

    /** Return remaining buffered data as one frame. */
    fun flush(): List<ByteArray> {
        if (cipherBuf.size == 0) return emptyList()
        val tail = cipherBuf.toByteArray()
        cipherBuf.clear()
        plainBuf.clear()
        return listOf(tail)
    }

    private fun nextPacketLen(): Int? {
        if (plainBuf.size == 0) return null
        return when (proto) {
            MtProtoConstants.PROTO_ABRIDGED_INT -> nextAbridgedLen()
            MtProtoConstants.PROTO_INTERMEDIATE_INT, MtProtoConstants.PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediateLen()
            else -> 0
        }
    }

    private fun nextAbridgedLen(): Int? {
        val first = plainBuf.peek(0) ?: return null
        if (first == 0x7F.toByte() || first == 0xFF.toByte()) {
            if (plainBuf.size < 4) return null
            val b1 = (plainBuf.peek(1)?.toInt() ?: return null) and 0xFF
            val b2 = (plainBuf.peek(2)?.toInt() ?: return null) and 0xFF
            val b3 = (plainBuf.peek(3)?.toInt() ?: return null) and 0xFF
            val payloadLen = ((b3 shl 16) or (b2 shl 8) or b1) * 4
            val headerLen = 4
            if (payloadLen <= 0) return 0
            val packetLen = headerLen + payloadLen
            if (plainBuf.size < packetLen) return null
            return packetLen
        } else {
            val payloadLen = (first.toInt() and 0x7F) * 4
            val headerLen = 1
            if (payloadLen <= 0) return 0
            val packetLen = headerLen + payloadLen
            if (plainBuf.size < packetLen) return null
            return packetLen
        }
    }

    private fun nextIntermediateLen(): Int? {
        if (plainBuf.size < 4) return null
        val b0 = (plainBuf.peek(0)?.toInt() ?: return null) and 0xFF
        val b1 = (plainBuf.peek(1)?.toInt() ?: return null) and 0xFF
        val b2 = (plainBuf.peek(2)?.toInt() ?: return null) and 0xFF
        val b3 = (plainBuf.peek(3)?.toInt() ?: return null) and 0xFF
        val payloadLen = ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0) and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (plainBuf.size < packetLen) return null
        return packetLen
    }

    /** Simple byte deque for efficient stream parsing */
    private class ByteArrayDeque {
        private var buf: ByteArray = ByteArray(0)
        var size: Int = 0
            private set

        fun write(data: ByteArray) {
            val newBuf = ByteArray(size + data.size)
            System.arraycopy(buf, 0, newBuf, 0, size)
            System.arraycopy(data, 0, newBuf, size, data.size)
            buf = newBuf
            size = newBuf.size
        }

        fun read(len: Int): ByteArray {
            val result = buf.copyOfRange(0, len)
            val remaining = size - len
            val newBuf = ByteArray(remaining)
            System.arraycopy(buf, len, newBuf, 0, remaining)
            buf = newBuf
            size = remaining
            return result
        }

        fun skip(len: Int) {
            val remaining = size - len
            val newBuf = ByteArray(remaining)
            System.arraycopy(buf, len, newBuf, 0, remaining)
            buf = newBuf
            size = remaining
        }

        fun peek(offset: Int): Byte? {
            return if (offset < size) buf[offset] else null
        }

        fun clear() {
            buf = ByteArray(0)
            size = 0
        }

        fun toByteArray(): ByteArray = buf.copyOfRange(0, size)
    }
}