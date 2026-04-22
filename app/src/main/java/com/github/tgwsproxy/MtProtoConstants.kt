package com.github.tgwsproxy

/**
 * MTProto protocol constants.
 * Mirrors proxy/utils.py from the original Python project.
 */
object MtProtoConstants {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    val PROTO_TAG_ABRIDGED = byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte())

    const val PROTO_ABRIDGED_INT = 0xEFEFEFEF.toInt()
    const val PROTO_INTERMEDIATE_INT = 0xEEEEEEEE.toInt()
    const val PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDD.toInt()

    val RESERVED_FIRST_BYTES = setOf(0xEF.toByte())
    val RESERVED_STARTS = listOf(
        byteArrayOf(0x48, 0x45, 0x41, 0x44),  // HEAD
        byteArrayOf(0x50, 0x4F, 0x53, 0x54),  // POST
        byteArrayOf(0x47, 0x45, 0x54, 0x20),  // GET
        byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
        byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte()),
        byteArrayOf(0x16, 0x03, 0x01, 0x02),
    )
    val RESERVED_CONTINUE = byteArrayOf(0, 0, 0, 0)

    // TLS constants for Fake TLS
    const val TLS_RECORD_HANDSHAKE: Byte = 0x16
    const val TLS_RECORD_CCS: Byte = 0x14
    const val TLS_RECORD_APPDATA: Byte = 0x17

    const val CLIENT_RANDOM_OFFSET = 11
    const val CLIENT_RANDOM_LEN = 32
    const val SESSION_ID_OFFSET = 44
    const val SESSION_ID_LEN = 32
    const val TIMESTAMP_TOLERANCE = 120
    const val TLS_APPDATA_MAX = 16384

    val ZERO_64 = ByteArray(64)

    fun humanBytes(n: Long): String {
        var value = n.toDouble()
        for (unit in listOf("B", "KB", "MB", "GB")) {
            if (kotlin.math.abs(value) < 1024) {
                return "%.1f%s".format(value, unit)
            }
            value /= 1024
        }
        return "%.1fTB".format(value)
    }
}