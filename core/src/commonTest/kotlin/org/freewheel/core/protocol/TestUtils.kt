package org.freewheel.core.protocol

/**
 * Convert a hex string (with optional spaces) to a ByteArray.
 * Handles both uppercase and lowercase hex digits.
 * Example: "55 AA 17 70".hexToByteArray()
 */
internal fun String.hexToByteArray(): ByteArray {
    val hex = this.replace(" ", "").uppercase()
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/**
 * Encode a Short as a 2-byte big-endian array.
 */
internal fun shortToBytesBE(value: Short): ByteArray = byteArrayOf(
    ((value.toInt() shr 8) and 0xFF).toByte(),
    (value.toInt() and 0xFF).toByte()
)

/**
 * Encode an Int as a 2-byte big-endian array (delegates to Short overload).
 */
internal fun shortToBytesBE(value: Int): ByteArray = shortToBytesBE(value.toShort())
