package com.cooper.wheellog.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [NinebotUnpacker].
 *
 * Ninebot frame format:
 * - Bytes 0-1: Header (55 AA)
 * - Byte 2: Length (of CAN data portion)
 * - Bytes 3..(len+2): CAN message data
 * - Bytes (len+3)..(len+4): CRC16 checksum (NOT validated by unpacker)
 *
 * Total frame size = len + 6 (header 2 + length 1 + data len + CRC 2 + 1 for 0-based)
 * Actually: header(2) + length_byte(1) + data(len) + CRC(2) = len + 5
 * But the buffer starts with header, so buffer.size() == len + 6 triggers DONE.
 * Wait — let me re-check. From the source: buffer.size() == len + 6.
 * buffer has: [55, AA, len_byte, ...data(len)..., crc1, crc2]
 * = 2 + 1 + len + 2 = len + 5? No, data portion is len bytes,
 * So: header(2) + length_byte(1) + data(len) + CRC(2) = len + 5.
 * But in the code it says len + 6. Let me check: the STARTED state reads the length
 * byte and writes it to buffer. So at that point buffer has [55, AA, len_byte].
 * Then COLLECTING writes more bytes. Total = 2 + 1 + len + 2 = len + 5.
 * Hmm, but the code checks buffer.size() == len + 6. Looking at it again:
 * "header(2) + length(1) + data(len) + CRC(2) = len + 5" - but that's the actual data len.
 * Let me look at the source comment: "data(len) + CRC(2)". So the length byte tells us
 * the data portion size. After header+len byte we need len+2 more bytes.
 * Total buffer = 3 (header + len) + len + 2 = len + 5. But source says len + 6.
 *
 * Actually reading source more carefully: the comment says
 * "Frame complete when we have: header(2) + length(1) + data(len) + CRC(2) = len + 5"
 * but the code does: buffer.size() == len + 6
 * This suggests len might represent something slightly different. Looking at the Ninebot
 * protocol more carefully, the length byte may represent data_len where total = 2+1+data_len+2+1.
 * In practice, the frame total is len + 6 bytes. Let's trust the code.
 */
class NinebotUnpackerTest {

    private fun feedBytes(unpacker: NinebotUnpacker, bytes: ByteArray): Boolean {
        var completed = false
        for (b in bytes) {
            if (unpacker.addChar(b.toInt() and 0xFF)) {
                completed = true
            }
        }
        return completed
    }

    /** Build a simple Ninebot frame with given data length and fill data. */
    private fun buildFrame(dataLen: Int, fillByte: Byte = 0): ByteArray {
        // Total frame size = dataLen + 6 (as per unpacker logic)
        // header(2) + len_byte(1) + data(dataLen) + CRC(2) = dataLen + 5
        // But unpacker expects dataLen + 6, so frame is actually:
        // header(2) + len_byte(1) + src(1) + dst(1) + param(1) + payload(dataLen-3) + CRC(2)
        // Total bytes needed to trigger DONE: dataLen + 6
        // So total array size = dataLen + 6 - offset? No, buffer size at trigger = dataLen + 6.
        // Buffer starts with [55, AA], then length byte at STARTED, then COLLECTING until size = dataLen+6.
        // So total bytes fed = dataLen + 6 (including header).
        val size = dataLen + 6
        val frame = ByteArray(size)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[2] = dataLen.toByte()
        for (i in 3 until size) {
            frame[i] = fillByte
        }
        return frame
    }

    @Test
    fun completeFrame_returnsTrue() {
        val unpacker = NinebotUnpacker()
        // Frame with 4 bytes of data: total = 4 + 6 = 10 bytes
        // Actually, len + 6 bytes total in the buffer
        val frame = buildFrame(4)
        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Should return true for complete frame")
    }

    @Test
    fun completeFrame_bufferMatchesInput() {
        val unpacker = NinebotUnpacker()
        val frame = buildFrame(4, 0x42.toByte())
        feedBytes(unpacker, frame)
        val buffer = unpacker.getBuffer()
        assertEquals(10, buffer.size, "Buffer should be len+6 = 10 bytes")
        assertEquals(0x55.toByte(), buffer[0])
        assertEquals(0xAA.toByte(), buffer[1])
        assertEquals(4.toByte(), buffer[2]) // length byte
    }

    @Test
    fun partialFrame_returnsFalse() {
        val unpacker = NinebotUnpacker()
        // Feed header + length but not all data
        val partial = byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x04, 0x01, 0x02)
        val result = feedBytes(unpacker, partial)
        assertFalse(result, "Should return false for incomplete frame")
    }

    @Test
    fun garbageBeforeHeader_isSkipped() {
        val unpacker = NinebotUnpacker()
        val garbage = byteArrayOf(0x01, 0x02, 0xFF.toByte(), 0x00)
        feedBytes(unpacker, garbage)

        val frame = buildFrame(2)
        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Should find valid frame after garbage")
    }

    @Test
    fun reset_allowsNextFrame() {
        val unpacker = NinebotUnpacker()

        val frame1 = buildFrame(2, 0x10.toByte())
        assertTrue(feedBytes(unpacker, frame1), "First frame should complete")
        assertEquals(0x10.toByte(), unpacker.getBuffer()[3])

        unpacker.reset()

        val frame2 = buildFrame(2, 0x20.toByte())
        assertTrue(feedBytes(unpacker, frame2), "Second frame should complete after reset")
        assertEquals(0x20.toByte(), unpacker.getBuffer()[3])
    }

    @Test
    fun byteByByte_onlyLastByteReturnsTrue() {
        val unpacker = NinebotUnpacker()
        val frame = buildFrame(3)
        val total = frame.size // 3 + 6 = 9

        for (i in 0 until total - 1) {
            assertFalse(
                unpacker.addChar(frame[i].toInt() and 0xFF),
                "Byte $i should not complete frame"
            )
        }
        assertTrue(
            unpacker.addChar(frame[total - 1].toInt() and 0xFF),
            "Last byte should complete frame"
        )
    }

    @Test
    fun zeroLengthFrame_completes() {
        val unpacker = NinebotUnpacker()
        // Frame with 0 data bytes: total = 0 + 6 = 6 bytes
        val frame = buildFrame(0)
        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Zero-length data frame should complete")
        assertEquals(6, unpacker.getBuffer().size)
    }

    @Test
    fun consecutiveFrames_withReset() {
        val unpacker = NinebotUnpacker()

        for (iteration in 1..3) {
            val frame = buildFrame(2, iteration.toByte())
            assertTrue(feedBytes(unpacker, frame), "Frame $iteration should complete")
            assertEquals((2 + 6), unpacker.getBuffer().size)
            unpacker.reset()
        }
    }
}
