package org.freewheel.core.util

import org.freewheel.core.utils.ByteUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ByteUtilsTest {

    // ==================== Unit Conversions ====================

    @Test
    fun `kmToMiles converts kilometers to miles correctly`() {
        assertEquals(0.0, ByteUtils.kmToMiles(0.0), 0.0001)
        assertEquals(0.62137, ByteUtils.kmToMiles(1.0), 0.0001)
        assertEquals(6.2137, ByteUtils.kmToMiles(10.0), 0.001)
        assertEquals(62.137, ByteUtils.kmToMiles(100.0), 0.01)
    }

    @Test
    fun `kmToMiles Float overload works correctly`() {
        assertEquals(0.0f, ByteUtils.kmToMiles(0.0f), 0.0001f)
        assertEquals(0.62137f, ByteUtils.kmToMiles(1.0f), 0.0001f)
    }

    @Test
    fun `celsiusToFahrenheit converts correctly`() {
        assertEquals(32.0, ByteUtils.celsiusToFahrenheit(0.0), 0.0001)
        assertEquals(212.0, ByteUtils.celsiusToFahrenheit(100.0), 0.0001)
        assertEquals(-40.0, ByteUtils.celsiusToFahrenheit(-40.0), 0.0001) // Same in both scales
        assertEquals(98.6, ByteUtils.celsiusToFahrenheit(37.0), 0.0001)
    }

    @Test
    fun `metersPerSecondToKmh converts correctly`() {
        assertEquals(0.0, ByteUtils.metersPerSecondToKmh(0.0), 0.0001)
        assertEquals(3.6, ByteUtils.metersPerSecondToKmh(1.0), 0.0001)
        assertEquals(36.0, ByteUtils.metersPerSecondToKmh(10.0), 0.0001)
        assertEquals(100.8, ByteUtils.metersPerSecondToKmh(28.0), 0.0001)
    }

    // ==================== Big Endian Reads ====================

    @Test
    fun `getInt2 reads 16-bit Big Endian correctly`() {
        // 0x1234 = 4660
        val bytes = byteArrayOf(0x12, 0x34)
        assertEquals(0x1234, ByteUtils.getInt2(bytes, 0))
    }

    @Test
    fun `getInt2 reads with offset`() {
        val bytes = byteArrayOf(0x00, 0x12, 0x34, 0x00)
        assertEquals(0x1234, ByteUtils.getInt2(bytes, 1))
    }

    @Test
    fun `getInt2 returns zero for insufficient bytes`() {
        val bytes = byteArrayOf(0x12)
        assertEquals(0, ByteUtils.getInt2(bytes, 0))
    }

    @Test
    fun `getInt2 returns zero when offset exceeds array`() {
        val bytes = byteArrayOf(0x12, 0x34)
        assertEquals(0, ByteUtils.getInt2(bytes, 5))
    }

    @Test
    fun `getInt2 handles high byte values as signed`() {
        // 0xFF00 = -256 (signed short extended to int)
        val bytes = byteArrayOf(0xFF.toByte(), 0x00)
        assertEquals(-256, ByteUtils.getInt2(bytes, 0))
    }

    @Test
    fun `getInt4 reads 32-bit Big Endian correctly`() {
        // 0x12345678 = 305419896
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertEquals(0x12345678L, ByteUtils.getInt4(bytes, 0))
    }

    @Test
    fun `getInt4 returns zero for insufficient bytes`() {
        val bytes = byteArrayOf(0x12, 0x34)
        assertEquals(0L, ByteUtils.getInt4(bytes, 0))
    }

    @Test
    fun `getInt4 handles high byte values as signed`() {
        // 0xFFFFFFFF = -1 (signed int extended to long)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1L, ByteUtils.getInt4(bytes, 0))
    }

    // ==================== Reversed Byte Order Reads ====================

    @Test
    fun `getInt2R reads with reversed byte pairs`() {
        // Input: [0x12, 0x34] -> reversed: [0x34, 0x12] -> BE read: 0x3412
        val bytes = byteArrayOf(0x12, 0x34)
        assertEquals(0x3412, ByteUtils.getInt2R(bytes, 0))
    }

    @Test
    fun `getInt2R returns zero for insufficient bytes`() {
        val bytes = byteArrayOf(0x12)
        assertEquals(0, ByteUtils.getInt2R(bytes, 0))
    }

    @Test
    fun `getInt4R reads with reversed byte pairs`() {
        // Input: [0x12, 0x34, 0x56, 0x78] -> pairs reversed: [0x34, 0x12, 0x78, 0x56]
        // BE read: 0x34127856
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertEquals(0x34127856, ByteUtils.getInt4R(bytes, 0))
    }

    @Test
    fun `getInt4R returns zero for insufficient bytes`() {
        val bytes = byteArrayOf(0x12, 0x34)
        assertEquals(0, ByteUtils.getInt4R(bytes, 0))
    }

    // ==================== Little Endian Reads ====================

    @Test
    fun `shortFromBytesLE reads 16-bit Little Endian correctly`() {
        // LE: [0x34, 0x12] = 0x1234
        val bytes = byteArrayOf(0x34, 0x12)
        assertEquals(0x1234, ByteUtils.shortFromBytesLE(bytes, 0))
    }

    @Test
    fun `shortFromBytesLE returns zero for insufficient bytes`() {
        val bytes = byteArrayOf(0x12)
        assertEquals(0, ByteUtils.shortFromBytesLE(bytes, 0))
    }

    @Test
    fun `shortFromBytesBE reads 16-bit Big Endian correctly`() {
        // BE: [0x12, 0x34] = 0x1234
        val bytes = byteArrayOf(0x12, 0x34)
        assertEquals(0x1234, ByteUtils.shortFromBytesBE(bytes, 0))
    }

    @Test
    fun `signedShortFromBytesLE reads signed 16-bit Little Endian`() {
        // LE: [0xFF, 0xFF] = -1 (signed)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, ByteUtils.signedShortFromBytesLE(bytes, 0))
    }

    @Test
    fun `signedShortFromBytesLE reads positive values`() {
        // LE: [0x01, 0x00] = 1
        val bytes = byteArrayOf(0x01, 0x00)
        assertEquals(1, ByteUtils.signedShortFromBytesLE(bytes, 0))
    }

    @Test
    fun `signedShortFromBytesBE reads signed 16-bit Big Endian`() {
        // BE: [0xFF, 0xFF] = -1 (signed)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, ByteUtils.signedShortFromBytesBE(bytes, 0))
    }

    @Test
    fun `signedShortFromBytesBE reads negative values`() {
        // BE: [0x80, 0x00] = -32768 (min short)
        val bytes = byteArrayOf(0x80.toByte(), 0x00)
        assertEquals(-32768, ByteUtils.signedShortFromBytesBE(bytes, 0))
    }

    @Test
    fun `intFromBytesLE reads 32-bit Little Endian correctly`() {
        // LE: [0x78, 0x56, 0x34, 0x12] = 0x12345678
        val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(0x12345678, ByteUtils.intFromBytesLE(bytes, 0))
    }

    @Test
    fun `intFromBytesLE returns zero for insufficient bytes`() {
        val bytes = byteArrayOf(0x12, 0x34)
        assertEquals(0, ByteUtils.intFromBytesLE(bytes, 0))
    }

    @Test
    fun `intFromBytesBE reads 32-bit Big Endian as unsigned Long`() {
        // BE: [0xFF, 0xFF, 0xFF, 0xFF] = 4294967295 (unsigned)
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(0xFFFFFFFFL, ByteUtils.intFromBytesBE(bytes, 0))
    }

    @Test
    fun `signedIntFromBytesLE reads 32-bit signed Little Endian`() {
        // LE: [0xFF, 0xFF, 0xFF, 0xFF] = -1 as signed, but function returns as Long
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1L, ByteUtils.signedIntFromBytesLE(bytes, 0))
    }

    @Test
    fun `longFromBytesLE reads 64-bit Little Endian correctly`() {
        // LE: [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08]
        // = 0x0807060504030201
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        assertEquals(0x0807060504030201L, ByteUtils.longFromBytesLE(bytes, 0))
    }

    @Test
    fun `longFromBytesLE returns zero for insufficient bytes`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertEquals(0L, ByteUtils.longFromBytesLE(bytes, 0))
    }

    // ==================== Reversed Endian Reads ====================

    @Test
    fun `intFromBytesRevLE reads with reversed Little Endian`() {
        // Input: [A, B, C, D] -> pattern: B<<24 | A<<16 | D<<8 | C
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        // Result: 0x34 << 24 | 0x12 << 16 | 0x78 << 8 | 0x56 = 0x34127856
        assertEquals(0x34127856L, ByteUtils.intFromBytesRevLE(bytes, 0))
    }

    @Test
    fun `intFromBytesRevBE reads with reversed Big Endian`() {
        // Input: [A, B, C, D] -> pattern: C<<24 | D<<16 | A<<8 | B
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        // Result: 0x56 << 24 | 0x78 << 16 | 0x12 << 8 | 0x34 = 0x56781234
        assertEquals(0x56781234, ByteUtils.intFromBytesRevBE(bytes, 0))
    }

    // ==================== Byte Conversion (to bytes) ====================

    @Test
    fun `getBytes Short converts to Big Endian byte array`() {
        val bytes = ByteUtils.getBytes(0x1234.toShort())
        assertEquals(2, bytes.size)
        assertEquals(0x12.toByte(), bytes[0])
        assertEquals(0x34.toByte(), bytes[1])
    }

    @Test
    fun `getBytes Short handles negative values`() {
        val bytes = ByteUtils.getBytes((-1).toShort()) // 0xFFFF
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xFF.toByte(), bytes[1])
    }

    @Test
    fun `getBytes Int converts to Big Endian byte array`() {
        val bytes = ByteUtils.getBytes(0x12345678)
        assertEquals(4, bytes.size)
        assertEquals(0x12.toByte(), bytes[0])
        assertEquals(0x34.toByte(), bytes[1])
        assertEquals(0x56.toByte(), bytes[2])
        assertEquals(0x78.toByte(), bytes[3])
    }

    @Test
    fun `getBytes Int handles negative values`() {
        val bytes = ByteUtils.getBytes(-1) // 0xFFFFFFFF
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xFF.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[2])
        assertEquals(0xFF.toByte(), bytes[3])
    }

    // ==================== reverseEvery2 ====================

    @Test
    fun `reverseEvery2 swaps byte pairs`() {
        val input = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val result = ByteUtils.reverseEvery2(input)
        assertEquals(0x02.toByte(), result[0])
        assertEquals(0x01.toByte(), result[1])
        assertEquals(0x04.toByte(), result[2])
        assertEquals(0x03.toByte(), result[3])
    }

    @Test
    fun `reverseEvery2 with offset and length`() {
        val input = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x00)
        val result = ByteUtils.reverseEvery2(input, 1, 4)
        assertEquals(4, result.size)
        assertEquals(0x02.toByte(), result[0])
        assertEquals(0x01.toByte(), result[1])
        assertEquals(0x04.toByte(), result[2])
        assertEquals(0x03.toByte(), result[3])
    }

    @Test
    fun `reverseEvery2 handles odd length by leaving last byte`() {
        val input = byteArrayOf(0x01, 0x02, 0x03)
        val result = ByteUtils.reverseEvery2(input)
        assertEquals(0x02.toByte(), result[0])
        assertEquals(0x01.toByte(), result[1])
        assertEquals(0x03.toByte(), result[2]) // Last byte unchanged
    }

    @Test
    fun `reverseEvery2 handles empty array`() {
        val input = byteArrayOf()
        val result = ByteUtils.reverseEvery2(input)
        assertEquals(0, result.size)
    }

    // ==================== Clamp ====================

    @Test
    fun `clamp Double returns value within range`() {
        assertEquals(5.0, ByteUtils.clamp(5.0, 0.0, 10.0))
    }

    @Test
    fun `clamp Double clamps to min`() {
        assertEquals(0.0, ByteUtils.clamp(-5.0, 0.0, 10.0))
    }

    @Test
    fun `clamp Double clamps to max`() {
        assertEquals(10.0, ByteUtils.clamp(15.0, 0.0, 10.0))
    }

    @Test
    fun `clamp Float works correctly`() {
        assertEquals(5.0f, ByteUtils.clamp(5.0f, 0.0f, 10.0f))
        assertEquals(0.0f, ByteUtils.clamp(-5.0f, 0.0f, 10.0f))
        assertEquals(10.0f, ByteUtils.clamp(15.0f, 0.0f, 10.0f))
    }

    @Test
    fun `clamp Int works correctly`() {
        assertEquals(5, ByteUtils.clamp(5, 0, 10))
        assertEquals(0, ByteUtils.clamp(-5, 0, 10))
        assertEquals(10, ByteUtils.clamp(15, 0, 10))
    }

    // ==================== Hex Conversion ====================

    @Test
    fun `bytesToHex converts correctly`() {
        val bytes = byteArrayOf(0x00, 0x0F, 0xFF.toByte(), 0xAB.toByte())
        assertEquals("000FFFAB", ByteUtils.bytesToHex(bytes))
    }

    @Test
    fun `bytesToHex handles empty array`() {
        assertEquals("", ByteUtils.bytesToHex(byteArrayOf()))
    }

    @Test
    fun `bytesToHex pads single digit hex values`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x0A, 0x0F)
        assertEquals("01020A0F", ByteUtils.bytesToHex(bytes))
    }

    @Test
    fun `hexToBytes converts correctly`() {
        val bytes = ByteUtils.hexToBytes("000FFFAB")
        assertEquals(4, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x0F.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[2])
        assertEquals(0xAB.toByte(), bytes[3])
    }

    @Test
    fun `hexToBytes handles spaces`() {
        val bytes = ByteUtils.hexToBytes("00 0F FF AB")
        assertEquals(4, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0x0F.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[2])
        assertEquals(0xAB.toByte(), bytes[3])
    }

    @Test
    fun `hexToBytes handles empty string`() {
        val bytes = ByteUtils.hexToBytes("")
        assertEquals(0, bytes.size)
    }

    @Test
    fun `hexToBytes handles lowercase`() {
        val bytes = ByteUtils.hexToBytes("abcdef")
        assertEquals(3, bytes.size)
        assertEquals(0xAB.toByte(), bytes[0])
        assertEquals(0xCD.toByte(), bytes[1])
        assertEquals(0xEF.toByte(), bytes[2])
    }

    @Test
    fun `hex round trip preserves data`() {
        val original = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte())
        val hex = ByteUtils.bytesToHex(original)
        val restored = ByteUtils.hexToBytes(hex)
        assertTrue(original.contentEquals(restored))
    }

    // ==================== formatDecimal ====================

    @Test
    fun `formatDecimal with 2 decimal places`() {
        assertEquals("3.14", ByteUtils.formatDecimal(3.14159, 2))
    }

    @Test
    fun `formatDecimal rounds correctly`() {
        // kotlin.math.round uses banker's rounding (round half to even)
        assertEquals("3.14", ByteUtils.formatDecimal(3.144, 2))
        assertEquals("3.15", ByteUtils.formatDecimal(3.146, 2))
        assertEquals("3.15", ByteUtils.formatDecimal(3.149, 2))
    }

    @Test
    fun `formatDecimal pads with zeros`() {
        assertEquals("3.00", ByteUtils.formatDecimal(3.0, 2))
        assertEquals("3.10", ByteUtils.formatDecimal(3.1, 2))
    }

    @Test
    fun `formatDecimal with 0 decimal places`() {
        assertEquals("4", ByteUtils.formatDecimal(3.7, 0))  // Rounds to nearest integer
        assertEquals("3", ByteUtils.formatDecimal(3.4, 0))
    }

    @Test
    fun `formatDecimal with 1 decimal place`() {
        assertEquals("3.1", ByteUtils.formatDecimal(3.14, 1))
    }

    @Test
    fun `formatDecimal with 4 decimal places`() {
        assertEquals("3.1416", ByteUtils.formatDecimal(3.14159, 4))
    }

    @Test
    fun `formatDecimal handles negative values`() {
        assertEquals("-3.14", ByteUtils.formatDecimal(-3.14159, 2))
    }

    @Test
    fun `formatDecimal handles zero`() {
        assertEquals("0.00", ByteUtils.formatDecimal(0.0, 2))
    }

    // ==================== formatHex ====================

    @Test
    fun `formatHex pads single digit`() {
        assertEquals("0F", ByteUtils.formatHex(0x0F.toByte()))
    }

    @Test
    fun `formatHex handles zero`() {
        assertEquals("00", ByteUtils.formatHex(0x00.toByte()))
    }

    @Test
    fun `formatHex handles max value`() {
        assertEquals("FF", ByteUtils.formatHex(0xFF.toByte()))
    }

    @Test
    fun `formatHex handles mid values`() {
        assertEquals("AB", ByteUtils.formatHex(0xAB.toByte()))
    }

    // ==================== Edge Cases ====================

    @Test
    fun `reading from empty array returns zero`() {
        val empty = byteArrayOf()
        assertEquals(0, ByteUtils.getInt2(empty, 0))
        assertEquals(0L, ByteUtils.getInt4(empty, 0))
        assertEquals(0, ByteUtils.shortFromBytesLE(empty, 0))
        assertEquals(0L, ByteUtils.longFromBytesLE(empty, 0))
    }

    @Test
    fun `reading with offset at boundary returns zero`() {
        val bytes = byteArrayOf(0x12, 0x34)
        assertEquals(0, ByteUtils.getInt2(bytes, 1)) // Only 1 byte left
        assertEquals(0, ByteUtils.shortFromBytesLE(bytes, 1))
    }

    @Test
    fun `getInt2 and shortFromBytesBE produce same result`() {
        val bytes = byteArrayOf(0x12, 0x34)
        assertEquals(ByteUtils.getInt2(bytes, 0), ByteUtils.shortFromBytesBE(bytes, 0))
    }

    @Test
    fun `reading with different offsets works correctly`() {
        val bytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        // These are signed values: 0xAABB = -21829, 0xBBCC = -17460, 0xCCDD = -13091
        assertEquals(0xAABB.toShort().toInt(), ByteUtils.getInt2(bytes, 0))
        assertEquals(0xBBCC.toShort().toInt(), ByteUtils.getInt2(bytes, 1))
        assertEquals(0xCCDD.toShort().toInt(), ByteUtils.getInt2(bytes, 2))
    }
}
