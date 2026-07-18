package org.freewheel.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [LorinUnpacker].
 *
 * InMotion V2 frame format:
 * - Bytes 0-1: Header (AA AA)
 * - Byte 2: Flags (0x11=Initial, 0x14=Default)
 * - Byte 3: Length (data + 1 for command byte)
 * - Byte 4: Command
 * - Bytes 5..(len+3): Data payload
 * - Byte (len+4): Checksum (XOR of bytes 2..len+3)
 *
 * Escape byte: 0xA5 — skip it and use the next byte as literal data.
 * Total frame size in buffer: len + 5
 */
class LorinUnpackerTest {

    private fun feedBytes(unpacker: LorinUnpacker, bytes: ByteArray): Boolean {
        var completed = false
        for (b in bytes) {
            if (unpacker.addChar(b.toInt() and 0xFF)) {
                completed = true
            }
        }
        return completed
    }

    /**
     * Build an InMotion V2 frame.
     * @param flags Frame flags (0x14 = default)
     * @param command Command byte
     * @param data Data payload (length byte = data.size + 1 for command)
     */
    private fun buildFrame(flags: Int = 0x14, command: Int = 0x01, data: ByteArray = ByteArray(0)): ByteArray {
        val len = data.size + 1 // +1 for command byte
        val frame = mutableListOf<Byte>()
        // Header
        frame.add(0xAA.toByte())
        frame.add(0xAA.toByte())
        // Flags
        frame.add(flags.toByte())
        // Length
        frame.add(len.toByte())
        // Command
        frame.add(command.toByte())
        // Data
        for (b in data) frame.add(b)
        // Checksum: XOR of bytes 2..(len+3)
        // = flags XOR len XOR command XOR data[0] XOR ...
        var checksum = flags xor len xor command
        for (b in data) checksum = checksum xor (b.toInt() and 0xFF)
        frame.add(checksum.toByte())

        return frame.toByteArray()
    }

    @Test
    fun completeFrame_returnsTrue() {
        val unpacker = LorinUnpacker()
        val frame = buildFrame(command = 0x01, data = byteArrayOf(0x10, 0x20))
        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Should return true for complete V2 frame")
    }

    @Test
    fun completeFrame_bufferMatchesInput() {
        val unpacker = LorinUnpacker()
        val data = byteArrayOf(0x10, 0x20, 0x30)
        val frame = buildFrame(flags = 0x14, command = 0x02, data = data)
        feedBytes(unpacker, frame)
        val buffer = unpacker.getBuffer()
        // Expected size: len + 5 where len = data.size + 1 = 4
        assertEquals(9, buffer.size, "Buffer should be len+5 = 9")
        assertEquals(0xAA.toByte(), buffer[0])
        assertEquals(0xAA.toByte(), buffer[1])
        assertEquals(0x14.toByte(), buffer[2]) // flags
        assertEquals(4.toByte(), buffer[3])    // length
        assertEquals(0x02.toByte(), buffer[4]) // command
        assertEquals(0x10.toByte(), buffer[5]) // data[0]
        assertEquals(0x20.toByte(), buffer[6]) // data[1]
        assertEquals(0x30.toByte(), buffer[7]) // data[2]
    }

    @Test
    fun partialFrame_returnsFalse() {
        val unpacker = LorinUnpacker()
        val partial = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x14, 0x03)
        val result = feedBytes(unpacker, partial)
        assertFalse(result, "Should return false for partial frame")
    }

    @Test
    fun escapeInData_skipsA5() {
        val unpacker = LorinUnpacker()
        // Build a frame where one data byte is 0xAA (needs escape)
        // Wire format: AA AA [flags] [len] [cmd] A5 AA [more data] [checksum]
        val rawBytes = mutableListOf<Byte>()
        rawBytes.add(0xAA.toByte()) // header
        rawBytes.add(0xAA.toByte()) // header
        rawBytes.add(0x14.toByte()) // flags
        rawBytes.add(0x02.toByte()) // len (1 cmd + 1 data byte)
        rawBytes.add(0x01.toByte()) // command
        rawBytes.add(0xA5.toByte()) // escape prefix
        rawBytes.add(0xAA.toByte()) // escaped AA → literal AA
        // Checksum: flags XOR len XOR cmd XOR data = 0x14 XOR 0x02 XOR 0x01 XOR 0xAA
        val checksum = 0x14 xor 0x02 xor 0x01 xor 0xAA
        rawBytes.add(checksum.toByte())

        val result = feedBytes(unpacker, rawBytes.toByteArray())
        assertTrue(result, "Should handle escaped AA byte")
        val buffer = unpacker.getBuffer()
        // Buffer should contain: AA AA 14 02 01 AA <checksum> = 7 bytes (len+5 = 2+5 = 7)
        assertEquals(7, buffer.size)
        assertEquals(0xAA.toByte(), buffer[5], "Escaped byte should be literal AA in buffer")
    }

    @Test
    fun consecutiveEscapedBytes_decodedCorrectly() {
        val unpacker = LorinUnpacker()
        // Frame with two consecutive escaped bytes in data: literal 0xAA followed by literal 0xA5
        // Wire: AA AA [flags] [len] [cmd] A5 AA A5 A5 [checksum]
        //                                 ^^^^^ ^^^^^ escaped 0xAA, escaped 0xA5
        val rawBytes = mutableListOf<Byte>()
        rawBytes.add(0xAA.toByte()) // header
        rawBytes.add(0xAA.toByte()) // header
        rawBytes.add(0x14.toByte()) // flags
        rawBytes.add(0x03.toByte()) // len (1 cmd + 2 data bytes)
        rawBytes.add(0x01.toByte()) // command
        rawBytes.add(0xA5.toByte()) // escape prefix
        rawBytes.add(0xAA.toByte()) // escaped AA → literal 0xAA
        rawBytes.add(0xA5.toByte()) // escape prefix
        rawBytes.add(0xA5.toByte()) // escaped A5 → literal 0xA5
        // Checksum: flags XOR len XOR cmd XOR data[0] XOR data[1]
        val checksum = 0x14 xor 0x03 xor 0x01 xor 0xAA xor 0xA5
        rawBytes.add(checksum.toByte())

        val result = feedBytes(unpacker, rawBytes.toByteArray())
        assertTrue(result, "Should handle consecutive escaped bytes")
        val buffer = unpacker.getBuffer()
        // Buffer: AA AA 14 03 01 AA A5 <checksum> = 8 bytes (len+5 = 3+5 = 8)
        assertEquals(8, buffer.size)
        assertEquals(0xAA.toByte(), buffer[5], "First escaped byte should be literal 0xAA")
        assertEquals(0xA5.toByte(), buffer[6], "Second escaped byte should be literal 0xA5")
    }

    @Test
    fun escapedA5_followedByEscapePrefix_decodedCorrectly() {
        val unpacker = LorinUnpacker()
        // Wire: A5 A5 A5 AA → literal 0xA5 then literal 0xAA
        // This catches the bug where oldC retains 0xA5 after decoding an escaped 0xA5
        val rawBytes = mutableListOf<Byte>()
        rawBytes.add(0xAA.toByte()) // header
        rawBytes.add(0xAA.toByte()) // header
        rawBytes.add(0x14.toByte()) // flags
        rawBytes.add(0x03.toByte()) // len (1 cmd + 2 data bytes)
        rawBytes.add(0x01.toByte()) // command
        rawBytes.add(0xA5.toByte()) // escape prefix
        rawBytes.add(0xA5.toByte()) // escaped A5 → literal 0xA5
        rawBytes.add(0xA5.toByte()) // escape prefix (must NOT be treated as literal)
        rawBytes.add(0xAA.toByte()) // escaped AA → literal 0xAA
        val checksum = 0x14 xor 0x03 xor 0x01 xor 0xA5 xor 0xAA
        rawBytes.add(checksum.toByte())

        val result = feedBytes(unpacker, rawBytes.toByteArray())
        assertTrue(result, "Should handle escaped 0xA5 followed by another escape sequence")
        val buffer = unpacker.getBuffer()
        assertEquals(8, buffer.size)
        assertEquals(0xA5.toByte(), buffer[5], "First data byte should be literal 0xA5")
        assertEquals(0xAA.toByte(), buffer[6], "Second data byte should be literal 0xAA")
    }

    @Test
    fun escapeInFlags_handledCorrectly() {
        val unpacker = LorinUnpacker()
        // If flags byte were 0xAA, it would need escaping on the wire
        val rawBytes = mutableListOf<Byte>()
        rawBytes.add(0xAA.toByte()) // header
        rawBytes.add(0xAA.toByte()) // header
        rawBytes.add(0xA5.toByte()) // escape prefix for flags
        rawBytes.add(0xAA.toByte()) // flags = 0xAA (escaped)
        rawBytes.add(0x01.toByte()) // len = 1 (just command, no data)
        rawBytes.add(0x01.toByte()) // command
        val checksum = 0xAA xor 0x01 xor 0x01
        rawBytes.add(checksum.toByte())

        val result = feedBytes(unpacker, rawBytes.toByteArray())
        assertTrue(result, "Should handle escaped flags byte")
        val buffer = unpacker.getBuffer()
        // Buffer: AA AA AA 01 01 <checksum> = 6 bytes (len+5 = 1+5 = 6)
        assertEquals(6, buffer.size)
        assertEquals(0xAA.toByte(), buffer[2], "Flags should be literal AA")
    }

    @Test
    fun garbageBeforeHeader_isSkipped() {
        val unpacker = LorinUnpacker()
        val garbage = byteArrayOf(0x01, 0x02, 0xFF.toByte())
        feedBytes(unpacker, garbage)

        val frame = buildFrame()
        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Should find frame after garbage")
    }

    @Test
    fun reset_allowsNextFrame() {
        val unpacker = LorinUnpacker()
        val frame1 = buildFrame(command = 0x01)
        assertTrue(feedBytes(unpacker, frame1), "First frame should complete")

        unpacker.reset()

        val frame2 = buildFrame(command = 0x02)
        assertTrue(feedBytes(unpacker, frame2), "Second frame should complete after reset")
        assertEquals(0x02.toByte(), unpacker.getBuffer()[4], "Command byte should be 0x02")
    }

    @Test
    fun byteByByte_onlyLastByteReturnsTrue() {
        val unpacker = LorinUnpacker()
        val frame = buildFrame(data = byteArrayOf(0x10))
        val total = frame.size

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
    fun initialFlags_frame() {
        val unpacker = LorinUnpacker()
        val frame = buildFrame(flags = 0x11, command = 0x02, data = byteArrayOf(0x01))
        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Should complete frame with initial flags 0x11")
        assertEquals(0x11.toByte(), unpacker.getBuffer()[2])
    }

    @Test
    fun emptyData_frame() {
        val unpacker = LorinUnpacker()
        // len = 1 (just command, no data)
        val frame = buildFrame(command = 0x05, data = ByteArray(0))
        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Should complete frame with no data payload")
        val buffer = unpacker.getBuffer()
        // Size: len + 5 = 1 + 5 = 6
        assertEquals(6, buffer.size)
    }
}
