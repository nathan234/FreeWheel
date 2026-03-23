package org.freewheel.core.utils

import kotlin.math.round

/**
 * Utility functions for byte array manipulation and unit conversion.
 * These are pure Kotlin functions that work on all platforms.
 */
object ByteUtils {

    // Unit conversion multiplier
    const val KM_TO_MILES_MULTIPLIER = 0.62137119223733

    /**
     * Convert kilometers to miles.
     */
    fun kmToMiles(km: Double): Double = km * KM_TO_MILES_MULTIPLIER

    /**
     * Convert kilometers to miles.
     */
    fun kmToMiles(km: Float): Float = (km * KM_TO_MILES_MULTIPLIER).toFloat()

    /**
     * Convert meters per second to kilometers per hour.
     */
    fun metersPerSecondToKmh(speedMs: Double): Double = speedMs * 3.6

    /**
     * Convert Celsius to Fahrenheit.
     */
    fun celsiusToFahrenheit(temp: Double): Double = temp * 9.0 / 5.0 + 32

    /**
     * Read a 16-bit signed integer from byte array at offset (Big Endian).
     * Matches legacy ByteBuffer.getShort() behavior - returns signed value.
     */
    fun getInt2(arr: ByteArray, offset: Int): Int {
        if (arr.size < offset + 2) return 0
        // Build unsigned value first, then convert to signed short, then to int (with sign extension)
        val unsigned = ((arr[offset].toInt() and 0xFF) shl 8) or (arr[offset + 1].toInt() and 0xFF)
        return unsigned.toShort().toInt()
    }

    /**
     * Read a 16-bit signed integer from byte array at offset with reversed byte order.
     * First swaps every 2 bytes, then reads as Big Endian.
     * Matches legacy ByteBuffer.getShort() behavior - returns signed value.
     */
    fun getInt2R(arr: ByteArray, offset: Int): Int {
        if (arr.size < offset + 2) return 0
        val reversed = reverseEvery2(arr, offset, 2)
        val unsigned = ((reversed[0].toInt() and 0xFF) shl 8) or (reversed[1].toInt() and 0xFF)
        return unsigned.toShort().toInt()
    }

    /**
     * Read a 32-bit signed integer from byte array at offset (Big Endian).
     * Matches legacy ByteBuffer.getInt() behavior - returns signed value as Long.
     */
    fun getInt4(arr: ByteArray, offset: Int): Long {
        if (arr.size < offset + 4) return 0L
        // Build as int (which is signed), then convert to long (with sign extension)
        val value = ((arr[offset].toInt() and 0xFF) shl 24) or
                ((arr[offset + 1].toInt() and 0xFF) shl 16) or
                ((arr[offset + 2].toInt() and 0xFF) shl 8) or
                (arr[offset + 3].toInt() and 0xFF)
        return value.toLong()
    }

    /**
     * Read a 32-bit signed integer from byte array at offset with reversed byte order.
     * Matches legacy ByteBuffer.getInt() behavior - returns signed value.
     */
    fun getInt4R(arr: ByteArray, offset: Int): Int {
        if (arr.size < offset + 4) return 0
        val reversed = reverseEvery2(arr, offset, 4)
        return ((reversed[0].toInt() and 0xFF) shl 24) or
                ((reversed[1].toInt() and 0xFF) shl 16) or
                ((reversed[2].toInt() and 0xFF) shl 8) or
                (reversed[3].toInt() and 0xFF)
    }

    /**
     * Convert a short to byte array (Big Endian).
     */
    fun getBytes(input: Short): ByteArray {
        return byteArrayOf(
            ((input.toInt() shr 8) and 0xFF).toByte(),
            (input.toInt() and 0xFF).toByte()
        )
    }

    /**
     * Convert an int to byte array (Big Endian).
     */
    fun getBytes(input: Int): ByteArray {
        return byteArrayOf(
            ((input shr 24) and 0xFF).toByte(),
            ((input shr 16) and 0xFF).toByte(),
            ((input shr 8) and 0xFF).toByte(),
            (input and 0xFF).toByte()
        )
    }

    /**
     * Reverse every pair of bytes in the input array.
     */
    fun reverseEvery2(input: ByteArray): ByteArray = reverseEvery2(input, 0, input.size)

    /**
     * Reverse every pair of bytes in a portion of the input array.
     */
    fun reverseEvery2(input: ByteArray, offset: Int, len: Int): ByteArray {
        if (offset < 0 || len < 0 || offset + len > input.size) return ByteArray(len.coerceAtLeast(0))
        val result = ByteArray(len)
        input.copyInto(result, 0, offset, offset + len)
        var i = 0
        while (i < len - 1) {
            val temp = result[i]
            result[i] = result[i + 1]
            result[i + 1] = temp
            i += 2
        }
        return result
    }

    /**
     * Read a 64-bit unsigned integer from byte array at offset (Little Endian).
     */
    fun longFromBytesLE(bytes: ByteArray, starting: Int): Long {
        if (bytes.size < starting + 8) return 0L
        return ((bytes[starting + 7].toLong() and 0xFF) shl 56) or
                ((bytes[starting + 6].toLong() and 0xFF) shl 48) or
                ((bytes[starting + 5].toLong() and 0xFF) shl 40) or
                ((bytes[starting + 4].toLong() and 0xFF) shl 32) or
                ((bytes[starting + 3].toLong() and 0xFF) shl 24) or
                ((bytes[starting + 2].toLong() and 0xFF) shl 16) or
                ((bytes[starting + 1].toLong() and 0xFF) shl 8) or
                (bytes[starting].toLong() and 0xFF)
    }

    /**
     * Read a 32-bit signed integer from byte array at offset (Little Endian).
     */
    fun signedIntFromBytesLE(bytes: ByteArray, starting: Int): Long {
        if (bytes.size < starting + 4) return 0L
        return (((bytes[starting + 3].toInt() and 0xFF) shl 24) or
                ((bytes[starting + 2].toInt() and 0xFF) shl 16) or
                ((bytes[starting + 1].toInt() and 0xFF) shl 8) or
                (bytes[starting].toInt() and 0xFF)).toLong()
    }

    /**
     * Read a 32-bit integer from byte array at offset with reversed Little Endian order.
     */
    fun intFromBytesRevLE(bytes: ByteArray, starting: Int): Long {
        if (bytes.size < starting + 4) return 0L
        return (((bytes[starting + 1].toInt() and 0xFF) shl 24) or
                ((bytes[starting].toInt() and 0xFF) shl 16) or
                ((bytes[starting + 3].toInt() and 0xFF) shl 8) or
                (bytes[starting + 2].toInt() and 0xFF)).toLong()
    }

    /**
     * Read a 32-bit unsigned integer from byte array at offset (Little Endian).
     */
    fun intFromBytesLE(bytes: ByteArray, starting: Int): Int {
        if (bytes.size < starting + 4) return 0
        return ((bytes[starting + 3].toInt() and 0xFF) shl 24) or
                ((bytes[starting + 2].toInt() and 0xFF) shl 16) or
                ((bytes[starting + 1].toInt() and 0xFF) shl 8) or
                (bytes[starting].toInt() and 0xFF)
    }

    /**
     * Read a 32-bit integer from byte array at offset with reversed Big Endian order.
     */
    fun intFromBytesRevBE(bytes: ByteArray, starting: Int): Int {
        if (bytes.size < starting + 4) return 0
        return ((bytes[starting + 2].toInt() and 0xFF) shl 24) or
                ((bytes[starting + 3].toInt() and 0xFF) shl 16) or
                ((bytes[starting].toInt() and 0xFF) shl 8) or
                (bytes[starting + 1].toInt() and 0xFF)
    }

    /**
     * Read a 32-bit unsigned integer from byte array at offset (Big Endian).
     */
    fun intFromBytesBE(bytes: ByteArray, starting: Int): Long {
        if (bytes.size < starting + 4) return 0L
        return (((bytes[starting].toInt() and 0xFF) shl 24) or
                ((bytes[starting + 1].toInt() and 0xFF) shl 16) or
                ((bytes[starting + 2].toInt() and 0xFF) shl 8) or
                (bytes[starting + 3].toInt() and 0xFF)).toLong() and 0xFFFFFFFFL
    }

    /**
     * Read a 16-bit unsigned integer from byte array at offset (Little Endian).
     */
    fun shortFromBytesLE(bytes: ByteArray, starting: Int): Int {
        if (bytes.size < starting + 2) return 0
        return ((bytes[starting + 1].toInt() and 0xFF) shl 8) or
                (bytes[starting].toInt() and 0xFF)
    }

    /**
     * Read a 16-bit unsigned integer from byte array at offset (Big Endian).
     */
    fun shortFromBytesBE(bytes: ByteArray, starting: Int): Int {
        if (bytes.size < starting + 2) return 0
        return ((bytes[starting].toInt() and 0xFF) shl 8) or
                (bytes[starting + 1].toInt() and 0xFF)
    }

    /**
     * Read a 16-bit signed integer from byte array at offset (Big Endian).
     */
    fun signedShortFromBytesBE(bytes: ByteArray, starting: Int): Int {
        if (bytes.size < starting + 2) return 0
        return (bytes[starting].toInt() shl 8) or (bytes[starting + 1].toInt() and 0xFF)
    }

    /**
     * Read a 16-bit signed integer from byte array at offset (Little Endian).
     */
    fun signedShortFromBytesLE(bytes: ByteArray, starting: Int): Int {
        if (bytes.size < starting + 2) return 0
        return (bytes[starting + 1].toInt() shl 8) or (bytes[starting].toInt() and 0xFF)
    }

    /**
     * Clamp a double value between min and max.
     */
    fun clamp(value: Double, min: Double, max: Double): Double =
        maxOf(min, minOf(max, value))

    /**
     * Clamp a float value between min and max.
     */
    fun clamp(value: Float, min: Float, max: Float): Float =
        maxOf(min, minOf(max, value))

    /**
     * Clamp an int value between min and max.
     */
    fun clamp(value: Int, min: Int, max: Int): Int =
        maxOf(min, minOf(max, value))

    /**
     * Convert byte array to hex string for debugging.
     */
    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { byte ->
            val hex = (byte.toInt() and 0xFF).toString(16).uppercase()
            if (hex.length == 1) "0$hex" else hex
        }

    /**
     * Convert hex string to byte array.
     */
    fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.replace(" ", "")
        if (cleanHex.isEmpty() || cleanHex.length % 2 != 0) return ByteArray(0)
        return try {
            ByteArray(cleanHex.length / 2) { i ->
                cleanHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            ByteArray(0)
        }
    }

    /**
     * Format a double with the specified number of decimal places.
     * Kotlin Multiplatform compatible alternative to String.format("%.2f", value).
     */
    fun formatDecimal(value: Double, decimals: Int = 2): String {
        val multiplier = pow10(decimals)
        val rounded = round(value * multiplier) / multiplier
        val parts = rounded.toString().split(".")
        val intPart = parts[0]
        val decPart = if (parts.size > 1) parts[1] else ""
        return if (decimals > 0) {
            "$intPart.${decPart.padEnd(decimals, '0').take(decimals)}"
        } else {
            intPart
        }
    }

    /**
     * Power of 10 helper for formatting.
     */
    private fun pow10(n: Int): Double {
        var result = 1.0
        repeat(n) { result *= 10.0 }
        return result
    }

    /**
     * Format a byte as 2-digit hex string.
     */
    fun formatHex(byte: Byte): String {
        val hex = (byte.toInt() and 0xFF).toString(16).uppercase()
        return if (hex.length == 1) "0$hex" else hex
    }
}
