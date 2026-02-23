package com.cooper.wheellog.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [InMotionUnpacker].
 *
 * InMotion V1 frame format:
 * - Bytes 0-1: Header (AA AA)
 * - Bytes 2-5: ID (4 bytes)
 * - Bytes 6-13: Data (8 bytes) — byte 6 = extended length
 * - Byte 14: Length field (basic packet length; 0xFE = extended)
 * - Byte 15: ch
 * - Byte 16: format
 * - Byte 17: type
 * - For extended (len=0xFE): extra data, checksum, footer
 * - Footer: 55 55
 *
 * Escape byte: 0xA5 — when encountered, skip it and use the next byte as data.
 */
class InMotionUnpackerTest {

    private fun feedBytes(unpacker: InMotionUnpacker, bytes: ByteArray): Boolean {
        var completed = false
        for (b in bytes) {
            if (unpacker.addChar(b.toInt() and 0xFF)) {
                completed = true
            }
        }
        return completed
    }

    /**
     * Build a basic InMotion V1 frame (non-extended).
     * Structure: AA AA [id x4] [data x8] [len] [ch] [format] [type] [checksum] 55 55
     * Total: 2 + 4 + 8 + 1 + 1 + 1 + 1 + 1 + 2 = 21 bytes
     */
    private fun buildBasicFrame(dataPayload: ByteArray = ByteArray(8)): ByteArray {
        val frame = mutableListOf<Byte>()
        // Header
        frame.add(0xAA.toByte())
        frame.add(0xAA.toByte())
        // ID (4 bytes)
        frame.addAll(listOf(0x01, 0x02, 0x03, 0x04).map { it.toByte() })
        // Data (8 bytes)
        for (b in dataPayload) frame.add(b)
        if (dataPayload.size < 8) {
            repeat(8 - dataPayload.size) { frame.add(0) }
        }
        // Length (basic, non-0xFE)
        frame.add(0x10.toByte())
        // ch, format, type
        frame.add(0x00.toByte())
        frame.add(0x00.toByte())
        frame.add(0x01.toByte())
        // Checksum
        frame.add(0x00.toByte())
        // Footer
        frame.add(0x55.toByte())
        frame.add(0x55.toByte())

        return frame.toByteArray()
    }

    @Test
    fun basicFrame_returnsTrue() {
        val unpacker = InMotionUnpacker()
        val frame = buildBasicFrame()
        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Should complete basic InMotion frame")
    }

    @Test
    fun basicFrame_bufferMatchesInput() {
        val unpacker = InMotionUnpacker()
        val frame = buildBasicFrame()
        feedBytes(unpacker, frame)
        val buffer = unpacker.getBuffer()
        // Buffer should contain the frame (AA AA excluded from count? No — buffer includes header)
        assertEquals(0xAA.toByte(), buffer[0])
        assertEquals(0xAA.toByte(), buffer[1])
        assertEquals(0x55.toByte(), buffer[buffer.size - 1])
        assertEquals(0x55.toByte(), buffer[buffer.size - 2])
    }

    @Test
    fun partialFrame_returnsFalse() {
        val unpacker = InMotionUnpacker()
        // Just the header
        val partial = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x01, 0x02)
        val result = feedBytes(unpacker, partial)
        assertFalse(result, "Should return false for partial frame")
    }

    @Test
    fun escapeByteHandling_skipsA5() {
        val unpacker = InMotionUnpacker()
        // Build a frame where a data byte is escaped
        // Before the footer, insert an escape: A5 AA should be treated as just AA
        val frame = mutableListOf<Byte>()
        // Header
        frame.add(0xAA.toByte())
        frame.add(0xAA.toByte())
        // ID (4 bytes)
        frame.addAll(listOf(0x01, 0x02, 0x03, 0x04).map { it.toByte() })
        // Data — first byte is escaped AA: A5 AA (but only AA goes into buffer)
        frame.add(0xA5.toByte()) // escape prefix — skipped
        frame.add(0xAA.toByte()) // escaped byte — this is the actual data
        // Rest of data (7 more bytes to make 8 data bytes in buffer)
        repeat(7) { frame.add(0x00.toByte()) }
        // Length (basic)
        frame.add(0x10.toByte())
        // ch, format, type
        frame.add(0x00.toByte())
        frame.add(0x00.toByte())
        frame.add(0x01.toByte())
        // Checksum
        frame.add(0x00.toByte())
        // Footer
        frame.add(0x55.toByte())
        frame.add(0x55.toByte())

        val result = feedBytes(unpacker, frame.toByteArray())
        assertTrue(result, "Should handle escaped bytes and complete frame")
        // Buffer should NOT contain the A5 escape byte
        val buffer = unpacker.getBuffer()
        assertEquals(0xAA.toByte(), buffer[6], "Escaped AA should be in data position 6")
    }

    @Test
    fun doubleEscapeA5_handledCorrectly() {
        // 0xA5 0xA5: first A5 is escape prefix, second A5 is literal A5
        val unpacker = InMotionUnpacker()
        val frame = mutableListOf<Byte>()
        // Header
        frame.add(0xAA.toByte())
        frame.add(0xAA.toByte())
        // ID (4 bytes)
        repeat(4) { frame.add(0x01.toByte()) }
        // Data — first byte is escaped A5: A5 A5 → literal A5
        frame.add(0xA5.toByte()) // escape prefix
        frame.add(0xA5.toByte()) // literal A5
        repeat(7) { frame.add(0x00.toByte()) }
        // Length (basic)
        frame.add(0x10.toByte())
        // ch, format, type
        repeat(3) { frame.add(0x00.toByte()) }
        // Checksum
        frame.add(0x00.toByte())
        // Footer
        frame.add(0x55.toByte())
        frame.add(0x55.toByte())

        val result = feedBytes(unpacker, frame.toByteArray())
        assertTrue(result, "Should handle A5 A5 escape correctly")
        val buffer = unpacker.getBuffer()
        assertEquals(0xA5.toByte(), buffer[6], "Double-escaped A5 should be literal A5 in buffer")
    }

    @Test
    fun garbageBeforeHeader_isSkipped() {
        val unpacker = InMotionUnpacker()
        val garbage = byteArrayOf(0x01, 0x02, 0xFF.toByte())
        feedBytes(unpacker, garbage)

        val frame = buildBasicFrame()
        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Should find frame after garbage")
    }

    @Test
    fun reset_allowsNextFrame() {
        val unpacker = InMotionUnpacker()
        val frame = buildBasicFrame()
        assertTrue(feedBytes(unpacker, frame), "First frame should complete")

        unpacker.reset()

        assertTrue(feedBytes(unpacker, frame), "Second frame should complete after reset")
    }
}
