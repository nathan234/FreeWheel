package org.freewheel.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [VeteranUnpacker] error counters.
 *
 * Veteran frame format:
 * - Bytes 0-2: Header (DC 5A 5C)
 * - Byte 3: Length
 * - Bytes 4+: Data payload
 * - Last 4 bytes: CRC32 (for newer firmware, len > 38)
 *
 * Payload fields are not framing sentinels. New wheel firmware may assign new
 * values to them, so only the header, declared length, and CRC establish frame
 * validity.
 */
class VeteranUnpackerTest {

    private fun feedBytes(unpacker: VeteranUnpacker, bytes: ByteArray): Boolean {
        var completed = false
        for (b in bytes) {
            if (unpacker.addChar(b.toInt() and 0xFF)) {
                completed = true
            }
        }
        return completed
    }

    /**
     * Build a valid Veteran frame with the given payload length.
     * The header (DC 5A 5C) and length byte are prepended.
     */
    private fun buildValidFrame(payloadLen: Int = 36): ByteArray {
        val frame = ByteArray(payloadLen + 4) // header(3) + len(1) + payload
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A.toByte()
        frame[2] = 0x5C.toByte()
        frame[3] = payloadLen.toByte()
        return frame
    }

    @Test
    fun stats_initiallyZero() {
        val unpacker = VeteranUnpacker()
        assertEquals(0, unpacker.stats.errorResets)
        assertEquals(0, unpacker.stats.bytesDiscarded)
    }

    @Test
    fun stats_validFrame_doesNotIncrement() {
        val unpacker = VeteranUnpacker()
        val frame = buildValidFrame(36)
        feedBytes(unpacker, frame)
        assertEquals(0, unpacker.stats.errorResets)
        assertEquals(0, unpacker.stats.bytesDiscarded)
    }

    @Test
    fun stats_payloadFieldChanges_doNotRejectFrame() {
        val unpacker = VeteranUnpacker()
        val frame = buildValidFrame(36)
        frame[22] = 0x01.toByte()
        frame[23] = 0x04.toByte()
        frame[30] = 0x08.toByte()

        val result = feedBytes(unpacker, frame)
        assertTrue(result, "Changing payload fields must not invalidate framing")
        assertEquals(0, unpacker.stats.errorResets)
        assertEquals(0, unpacker.stats.bytesDiscarded)
    }

    @Test
    fun stats_crcMismatch_incrementsCounters() {
        val unpacker = VeteranUnpacker()
        // Build a frame with len > 38 to trigger CRC check, but wrong CRC
        val payloadLen = 42
        val frame = buildValidFrame(payloadLen)
        // CRC bytes are the last 4 bytes of payload — leave as zeros (wrong CRC)

        val result = feedBytes(unpacker, frame)
        assertFalse(result, "Frame with bad CRC should be rejected")
        assertEquals(1, unpacker.stats.errorResets)
        assertTrue(unpacker.stats.bytesDiscarded > 0)
    }

    @Test
    fun stats_persistAcrossReset() {
        val unpacker = VeteranUnpacker()
        val frame = buildValidFrame(42) // invalid zero CRC

        feedBytes(unpacker, frame)
        unpacker.reset()

        assertEquals(1, unpacker.stats.errorResets, "Stats should persist across reset()")
    }

    @Test
    fun stats_clearedByResetStats() {
        val unpacker = VeteranUnpacker()
        val frame = buildValidFrame(42) // invalid zero CRC

        feedBytes(unpacker, frame)
        unpacker.resetStats()

        assertEquals(0, unpacker.stats.errorResets)
        assertEquals(0, unpacker.stats.bytesDiscarded)
    }

    @Test
    fun stats_multipleErrors_accumulate() {
        val unpacker = VeteranUnpacker()
        val frame = buildValidFrame(42) // invalid zero CRC

        feedBytes(unpacker, frame)
        feedBytes(unpacker, frame)

        assertEquals(2, unpacker.stats.errorResets)
    }
}
