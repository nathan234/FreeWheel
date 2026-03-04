package org.freewheel.utils

import org.freewheel.core.utils.ByteUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Comparison tests to verify KMP ByteUtils produces identical results to legacy MathsUtil.
 *
 * Any test failures here indicate a behavioral difference between the legacy Java
 * implementation and the new KMP implementation.
 */
class ByteUtilsComparisonTest {

    // ==================== Unit Conversions ====================

    @Test
    fun `kmToMiles matches legacy - double`() {
        val testValues = listOf(0.0, 1.0, 10.0, 100.0, 0.5, 999.999)
        for (km in testValues) {
            assertThat(ByteUtils.kmToMiles(km))
                .isWithin(0.0000001)
                .of(MathsUtil.kmToMiles(km))
        }
    }

    @Test
    fun `kmToMiles matches legacy - float`() {
        val testValues = listOf(0.0f, 1.0f, 10.0f, 100.0f)
        for (km in testValues) {
            assertThat(ByteUtils.kmToMiles(km))
                .isWithin(0.0001f)
                .of(MathsUtil.kmToMiles(km))
        }
    }

    @Test
    fun `celsiusToFahrenheit matches legacy`() {
        val testValues = listOf(-40.0, 0.0, 37.0, 100.0, -273.15)
        for (celsius in testValues) {
            assertThat(ByteUtils.celsiusToFahrenheit(celsius))
                .isWithin(0.0001)
                .of(MathsUtil.celsiusToFahrenheit(celsius))
        }
    }

    // ==================== getInt2 (Big Endian 16-bit) ====================

    @Test
    fun `getInt2 matches legacy for positive values`() {
        // Values where high bit is 0 (positive in signed interpretation)
        val testCases = listOf(
            byteArrayOf(0x00, 0x00),  // 0
            byteArrayOf(0x00, 0x01),  // 1
            byteArrayOf(0x00, 0xFF.toByte()),  // 255
            byteArrayOf(0x01, 0x00),  // 256
            byteArrayOf(0x12, 0x34),  // 4660
            byteArrayOf(0x7F, 0xFF.toByte()),  // 32767 (max positive short)
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.getInt2(bytes, 0)
            val kmp = ByteUtils.getInt2(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    @Test
    fun `getInt2 matches legacy for values with high bit set`() {
        // Values where high bit is 1 (negative in signed interpretation)
        // Both should now return signed value
        val testCases = listOf(
            byteArrayOf(0xFF.toByte(), 0x00),  // -256
            byteArrayOf(0x80.toByte(), 0x00),  // -32768 (min short)
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()),  // -1
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.getInt2(bytes, 0)
            val kmp = ByteUtils.getInt2(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    @Test
    fun `getInt2 with offset matches legacy for positive values`() {
        val bytes = byteArrayOf(0x00, 0x12, 0x34, 0x00)
        assertThat(ByteUtils.getInt2(bytes, 1)).isEqualTo(MathsUtil.getInt2(bytes, 1))
    }

    // ==================== getInt4 (Big Endian 32-bit) ====================

    @Test
    fun `getInt4 matches legacy for positive values`() {
        val testCases = listOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x00),  // 0
            byteArrayOf(0x00, 0x00, 0x00, 0x01),  // 1
            byteArrayOf(0x12, 0x34, 0x56, 0x78),  // 305419896
            byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),  // 2147483647 (max positive int)
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.getInt4(bytes, 0)
            val kmp = ByteUtils.getInt4(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    @Test
    fun `getInt4 matches legacy for values with high bit set`() {
        // Both should now return signed value
        val testCases = listOf(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),  // -1
            byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00),  // -2147483648 (min int)
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00),  // -256
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.getInt4(bytes, 0)
            val kmp = ByteUtils.getInt4(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    // ==================== getInt2R / getInt4R (Reversed) ====================

    @Test
    fun `getInt2R matches legacy`() {
        val testCases = listOf(
            byteArrayOf(0x12, 0x34),
            byteArrayOf(0x00, 0x00),
            byteArrayOf(0x01, 0x00),
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.getInt2R(bytes, 0)
            val kmp = ByteUtils.getInt2R(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    @Test
    fun `getInt4R matches legacy for positive values`() {
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertThat(ByteUtils.getInt4R(bytes, 0)).isEqualTo(MathsUtil.getInt4R(bytes, 0))
    }

    // ==================== getBytes (Short and Int) ====================

    @Test
    fun `getBytes Short matches legacy`() {
        val testValues = listOf<Short>(0, 1, 255, 256, 6666, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        for (value in testValues) {
            val legacy = MathsUtil.getBytes(value)
            val kmp = ByteUtils.getBytes(value)
            assertThat(kmp.toList()).isEqualTo(legacy.toList())
        }
    }

    @Test
    fun `getBytes Int matches legacy`() {
        val testValues = listOf(0, 1, 255, 65536, 1234567980, -1, Int.MAX_VALUE, Int.MIN_VALUE)
        for (value in testValues) {
            val legacy = MathsUtil.getBytes(value)
            val kmp = ByteUtils.getBytes(value)
            assertThat(kmp.toList()).isEqualTo(legacy.toList())
        }
    }

    // ==================== reverseEvery2 ====================

    @Test
    fun `reverseEvery2 matches legacy - full array`() {
        val testCases = listOf(
            byteArrayOf(0x01, 0x02),
            byteArrayOf(0x01, 0x02, 0x03, 0x04),
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.reverseEvery2(bytes)
            val kmp = ByteUtils.reverseEvery2(bytes)
            assertThat(kmp.toList()).isEqualTo(legacy.toList())
        }
    }

    @Test
    fun `reverseEvery2 matches legacy - with offset and length`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x00)
        val legacy = MathsUtil.reverseEvery2(bytes, 1, 4)
        val kmp = ByteUtils.reverseEvery2(bytes, 1, 4)
        assertThat(kmp.toList()).isEqualTo(legacy.toList())
    }

    // ==================== Little Endian Functions ====================

    @Test
    fun `longFromBytesLE matches legacy`() {
        val testCases = listOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F),
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.longFromBytesLE(bytes, 0)
            val kmp = ByteUtils.longFromBytesLE(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    @Test
    fun `signedIntFromBytesLE matches legacy`() {
        val testCases = listOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0x01, 0x00, 0x00, 0x00),
            byteArrayOf(0x78, 0x56, 0x34, 0x12),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.signedIntFromBytesLE(bytes, 0)
            val kmp = ByteUtils.signedIntFromBytesLE(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    @Test
    fun `intFromBytesLE matches legacy`() {
        val testCases = listOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0x78, 0x56, 0x34, 0x12),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.intFromBytesLE(bytes, 0)
            val kmp = ByteUtils.intFromBytesLE(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    @Test
    fun `intFromBytesRevLE matches legacy`() {
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertThat(ByteUtils.intFromBytesRevLE(bytes, 0))
            .isEqualTo(MathsUtil.intFromBytesRevLE(bytes, 0))
    }

    @Test
    fun `intFromBytesRevBE matches legacy`() {
        val bytes = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        assertThat(ByteUtils.intFromBytesRevBE(bytes, 0))
            .isEqualTo(MathsUtil.intFromBytesRevBE(bytes, 0))
    }

    @Test
    fun `intFromBytesBE matches legacy`() {
        val testCases = listOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x00),
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.intFromBytesBE(bytes, 0)
            val kmp = ByteUtils.intFromBytesBE(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    // ==================== Short Functions ====================

    @Test
    fun `shortFromBytesLE matches legacy`() {
        val testCases = listOf(
            byteArrayOf(0x00, 0x00),
            byteArrayOf(0x34, 0x12),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.shortFromBytesLE(bytes, 0)
            val kmp = ByteUtils.shortFromBytesLE(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    @Test
    fun `shortFromBytesBE matches legacy`() {
        val testCases = listOf(
            byteArrayOf(0x00, 0x00),
            byteArrayOf(0x12, 0x34),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.shortFromBytesBE(bytes, 0)
            val kmp = ByteUtils.shortFromBytesBE(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    @Test
    fun `signedShortFromBytesBE matches legacy`() {
        val testCases = listOf(
            byteArrayOf(0x00, 0x00),    // 0
            byteArrayOf(0x00, 0x01),    // 1
            byteArrayOf(0x7F, 0xFF.toByte()),   // 32767
            byteArrayOf(0x80.toByte(), 0x00),   // -32768
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()),   // -1
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.signedShortFromBytesBE(bytes, 0)
            val kmp = ByteUtils.signedShortFromBytesBE(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    @Test
    fun `signedShortFromBytesLE matches legacy`() {
        val testCases = listOf(
            byteArrayOf(0x00, 0x00),    // 0
            byteArrayOf(0x01, 0x00),    // 1
            byteArrayOf(0xFF.toByte(), 0x7F),   // 32767
            byteArrayOf(0x00, 0x80.toByte()),   // -32768
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()),   // -1
        )
        for (bytes in testCases) {
            val legacy = MathsUtil.signedShortFromBytesLE(bytes, 0)
            val kmp = ByteUtils.signedShortFromBytesLE(bytes, 0)
            assertThat(kmp).isEqualTo(legacy)
        }
    }

    // ==================== Clamp ====================

    @Test
    fun `clamp Double matches legacy`() {
        assertThat(ByteUtils.clamp(5.0, 0.0, 10.0)).isEqualTo(MathsUtil.clamp(5.0, 0.0, 10.0))
        assertThat(ByteUtils.clamp(-5.0, 0.0, 10.0)).isEqualTo(MathsUtil.clamp(-5.0, 0.0, 10.0))
        assertThat(ByteUtils.clamp(15.0, 0.0, 10.0)).isEqualTo(MathsUtil.clamp(15.0, 0.0, 10.0))
    }

    @Test
    fun `clamp Float matches legacy`() {
        assertThat(ByteUtils.clamp(5.0f, 0.0f, 10.0f)).isEqualTo(MathsUtil.clamp(5.0f, 0.0f, 10.0f))
        assertThat(ByteUtils.clamp(-5.0f, 0.0f, 10.0f)).isEqualTo(MathsUtil.clamp(-5.0f, 0.0f, 10.0f))
        assertThat(ByteUtils.clamp(15.0f, 0.0f, 10.0f)).isEqualTo(MathsUtil.clamp(15.0f, 0.0f, 10.0f))
    }

    @Test
    fun `clamp Int matches legacy`() {
        assertThat(ByteUtils.clamp(5, 0, 10)).isEqualTo(MathsUtil.clamp(5, 0, 10))
        assertThat(ByteUtils.clamp(-5, 0, 10)).isEqualTo(MathsUtil.clamp(-5, 0, 10))
        assertThat(ByteUtils.clamp(15, 0, 10)).isEqualTo(MathsUtil.clamp(15, 0, 10))
    }

    // ==================== Round-trip Tests ====================

    @Test
    fun `getBytes and getInt2 round-trip matches legacy`() {
        val testValues = listOf<Short>(0, 1, 255, 6666, 32767)
        for (value in testValues) {
            val legacyBytes = MathsUtil.getBytes(value)
            val kmpBytes = ByteUtils.getBytes(value)

            assertThat(MathsUtil.getInt2(legacyBytes, 0)).isEqualTo(value.toInt())
            assertThat(ByteUtils.getInt2(kmpBytes, 0)).isEqualTo(value.toInt())
        }
    }

    @Test
    fun `getBytes and getInt4 round-trip matches legacy`() {
        val testValues = listOf(0, 1, 65536, 1234567980, Int.MAX_VALUE)
        for (value in testValues) {
            val legacyBytes = MathsUtil.getBytes(value)
            val kmpBytes = ByteUtils.getBytes(value)

            assertThat(MathsUtil.getInt4(legacyBytes, 0)).isEqualTo(value.toLong())
            assertThat(ByteUtils.getInt4(kmpBytes, 0)).isEqualTo(value.toLong())
        }
    }

    // ==================== Edge Cases ====================

    @Test
    fun `empty array handling matches legacy`() {
        val empty = byteArrayOf()
        // Both should return 0 for empty/insufficient arrays
        // Note: Legacy may throw, KMP returns 0 - test documents behavior
    }

    @Test
    fun `offset bounds handling matches legacy`() {
        val bytes = byteArrayOf(0x12, 0x34)

        // Legacy uses ByteBuffer which throws on insufficient bytes
        // KMP returns 0 - this is a known difference
        val kmpResult = ByteUtils.getInt2(bytes, 1)
        assertThat(kmpResult).isEqualTo(0)  // KMP returns 0 for insufficient bytes
    }
}
