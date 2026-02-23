package com.cooper.wheellog.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [GotwayUnpacker].
 *
 * Gotway frame format: 24 bytes total.
 * - Bytes 0-1: Header (55 AA)
 * - Bytes 2-17: Data payload (16 bytes)
 * - Byte 18: Frame type
 * - Byte 19: Footer byte
 * - Bytes 20-23: Footer (5A 5A 5A 5A)
 */
class GotwayUnpackerTest {

    private fun feedBytes(unpacker: GotwayUnpacker, bytes: ByteArray): Boolean {
        var completed = false
        for (b in bytes) {
            if (unpacker.addChar(b.toInt() and 0xFF)) {
                completed = true
            }
        }
        return completed
    }

    @Test
    fun completeFrame_returnsTrue() {
        val unpacker = GotwayUnpacker()
        // Build a valid 24-byte frame: header + 16 data bytes + frame type + footer byte + 5A 5A 5A 5A
        val frame = ByteArray(24)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        // Bytes 2-17: data payload (zeros)
        frame[18] = 0x04.toByte() // frame type
        frame[19] = 0x18.toByte() // footer byte
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()

        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Should return true for complete 24-byte frame")
    }

    @Test
    fun completeFrame_bufferMatchesInput() {
        val unpacker = GotwayUnpacker()
        val frame = ByteArray(24)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        for (i in 2..17) frame[i] = i.toByte()
        frame[18] = 0x04.toByte()
        frame[19] = 0x18.toByte()
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()

        feedBytes(unpacker, frame)
        val buffer = unpacker.getBuffer()
        assertEquals(24, buffer.size)
        assertEquals(0x55.toByte(), buffer[0])
        assertEquals(0xAA.toByte(), buffer[1])
        assertEquals(0x5A.toByte(), buffer[23])
    }

    @Test
    fun partialFrame_returnsFalse() {
        val unpacker = GotwayUnpacker()
        // Feed only first 10 bytes of a frame
        val partial = byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val result = feedBytes(unpacker, partial)
        assertFalse(result, "Should return false for incomplete frame")
    }

    @Test
    fun invalidFooter_rejectsFrame() {
        val unpacker = GotwayUnpacker()
        val frame = ByteArray(24)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[18] = 0x04.toByte()
        frame[19] = 0x18.toByte()
        frame[20] = 0x5A.toByte()
        frame[21] = 0xFF.toByte() // invalid footer byte
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()

        val result = feedBytes(unpacker, frame)
        assertFalse(result, "Should reject frame with invalid footer")
    }

    @Test
    fun garbageBeforeHeader_isSkipped() {
        val unpacker = GotwayUnpacker()
        // Feed garbage bytes, then a valid frame
        val garbage = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
        feedBytes(unpacker, garbage)

        val frame = ByteArray(24)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[18] = 0x04.toByte()
        frame[19] = 0x18.toByte()
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()

        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Should find valid frame after garbage")
    }

    @Test
    fun garbagePattern1_recovers() {
        // Pattern: 55 AA 5A 55 AA → resets to 55 AA and continues collecting
        val unpacker = GotwayUnpacker()
        val garbageAndFrame = ByteArray(3 + 22) // 55 AA 5A, then rest of frame starting from 55 AA
        garbageAndFrame[0] = 0x55.toByte()
        garbageAndFrame[1] = 0xAA.toByte()
        garbageAndFrame[2] = 0x5A.toByte()
        // New frame header
        garbageAndFrame[3] = 0x55.toByte()
        garbageAndFrame[4] = 0xAA.toByte()
        // Data payload (bytes 5-20 = indices 2-17 of the real frame)
        for (i in 5..20) garbageAndFrame[i] = 0
        garbageAndFrame[21] = 0x04.toByte() // frame type
        garbageAndFrame[22] = 0x18.toByte() // footer byte
        garbageAndFrame[23] = 0x5A.toByte()
        garbageAndFrame[24] = 0x5A.toByte()

        // The unpacker has to keep collecting after reassembly, so the total frame
        // needs to reach 24 bytes from the reassembled header
        // After reassembly at position 5 (size==5): buffer = [55 AA], so 22 more bytes needed
        // We feed bytes 5..24 (20 bytes), so buffer reaches 22 bytes. Need 2 more.
        val remaining = byteArrayOf(0x5A.toByte(), 0x5A.toByte())

        val result1 = feedBytes(unpacker, garbageAndFrame)
        // May or may not complete in the first batch; feed remaining
        val result2 = if (!result1) feedBytes(unpacker, remaining) else true
        assertTrue(result1 || result2, "Should recover from garbage pattern 1")
    }

    @Test
    fun reset_allowsNextFrame() {
        val unpacker = GotwayUnpacker()
        val frame = ByteArray(24)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[18] = 0x04.toByte()
        frame[19] = 0x18.toByte()
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()

        assertTrue(feedBytes(unpacker, frame), "First frame should complete")
        unpacker.reset()

        // Second frame with different data
        frame[2] = 0x42.toByte()
        assertTrue(feedBytes(unpacker, frame), "Second frame should complete after reset")
        assertEquals(0x42.toByte(), unpacker.getBuffer()[2])
    }

    @Test
    fun byteByByte_onlyLastByteReturnsTrue() {
        val unpacker = GotwayUnpacker()
        val frame = ByteArray(24)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[18] = 0x04.toByte()
        frame[19] = 0x18.toByte()
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()

        for (i in 0 until 23) {
            assertFalse(
                unpacker.addChar(frame[i].toInt() and 0xFF),
                "Byte $i should not complete frame"
            )
        }
        assertTrue(
            unpacker.addChar(frame[23].toInt() and 0xFF),
            "Last byte should complete frame"
        )
    }
}
