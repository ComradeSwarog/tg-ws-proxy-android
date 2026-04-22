package com.github.tgwsproxy

import org.junit.Assert.*
import org.junit.Test

class RawWebSocketTest {

    @Test
    fun `encodePaddedPayload creates length-prefixed padded data that round-trips correctly`() {
        val original = byteArrayOf(0x01, 0xAA.toByte(), 0x55, 0xBE.toByte(), 0xEF.toByte())
        RawWebSocket.framePaddingEnabled = true
        RawWebSocket.framePaddingMinBytes = 4
        RawWebSocket.framePaddingMaxBytes = 8

        val encoded = RawWebSocket.encodePaddedPayload(original)
        assertTrue("Encoded must have length header (2) + payload + padding", encoded.size >= 2 + original.size + 4)

        val stripped = RawWebSocket.stripPaddingIfPresent(encoded)
        assertArrayEquals("Stripped payload must equal original", original, stripped)

        // cleanup
        RawWebSocket.framePaddingEnabled = false
        RawWebSocket.framePaddingMinBytes = 0
        RawWebSocket.framePaddingMaxBytes = 0
    }

    @Test
    fun `encodePaddedPayload disabled returns original data unchanged`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        RawWebSocket.framePaddingEnabled = false
        val result = RawWebSocket.encodePaddedPayload(data)
        assertArrayEquals(data, result)
    }

    @Test
    fun `stripPaddingIfPresent handles non-padded payload gracefully when disabled`() {
        val data = byteArrayOf(0x09, 0x00, 0xAA.toByte(), 0xBB.toByte())
        RawWebSocket.framePaddingEnabled = false
        val result = RawWebSocket.stripPaddingIfPresent(data)
        assertArrayEquals(data, result)
    }

    @Test
    fun `OP constants match expected values`() {
        assertEquals(0x2, RawWebSocket.OP_BINARY)
        assertEquals(0x8, RawWebSocket.OP_CLOSE)
        assertEquals(0x9, RawWebSocket.OP_PING)
        assertEquals(0xA, RawWebSocket.OP_PONG)
    }

    @Test
    fun `Base64Encoder produces correct output for simple values`() {
        // Access private nested object RawWebSocket.Base64Encoder via reflection
        val clazz = Class.forName("com.github.tgwsproxy.RawWebSocket\$Base64Encoder")
        val instanceField = clazz.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        val encoderInstance = instanceField.get(null)

        val encodeMethod = clazz.getDeclaredMethod("encode", ByteArray::class.java)
        encodeMethod.isAccessible = true

        val empty = encodeMethod.invoke(encoderInstance, ByteArray(0)) as String
        assertEquals("", empty)

        val single = encodeMethod.invoke(encoderInstance, byteArrayOf(0xFB.toByte())) as String
        assertEquals("+w==", single)

        val simple = encodeMethod.invoke(encoderInstance, byteArrayOf(0x61, 0x62, 0x63)) as String
        assertEquals("YWJj", simple)
    }
}
