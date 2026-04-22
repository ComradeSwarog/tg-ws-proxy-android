package com.github.tgwsproxy

import org.junit.Assert.*
import org.junit.Test

class RawWebSocketTest {

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
