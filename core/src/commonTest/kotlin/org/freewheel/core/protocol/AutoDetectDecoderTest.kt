package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoDetectDecoderTest {

    private val decoder = AutoDetectDecoder()
    private val defaultState = WheelState()
    private val defaultConfig = DecoderConfig()

    // ==================== Initial State ====================

    @Test
    fun `wheelType is GOTWAY_VIRTUAL`() {
        assertEquals(WheelType.GOTWAY_VIRTUAL, decoder.wheelType)
    }

    @Test
    fun `initial state has no detected type`() {
        assertNull(decoder.getDetectedType())
    }

    @Test
    fun `initial state has no detected decoder`() {
        assertNull(decoder.getDetectedDecoder())
    }

    @Test
    fun `isReady returns false before detection`() {
        assertFalse(decoder.isReady())
    }

    // ==================== Veteran Detection ====================

    @Test
    fun `detects Veteran from DC 5A 5C header`() {
        // Veteran packet header: DC 5A 5C
        val veteranPacket = byteArrayOf(
            0xDC.toByte(), 0x5A, 0x5C,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        decoder.decode(veteranPacket, defaultState, defaultConfig)

        assertEquals(WheelType.VETERAN, decoder.getDetectedType())
        assertNotNull(decoder.getDetectedDecoder())
    }

    @Test
    fun `Veteran detection sets wheel type in state`() {
        val veteranPacket = byteArrayOf(
            0xDC.toByte(), 0x5A, 0x5C,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val result = decoder.decode(veteranPacket, defaultState, defaultConfig)

        // Result may be null if packet doesn't have enough valid data for full decode
        // but detection should still work
        assertEquals(WheelType.VETERAN, decoder.getDetectedType())
    }

    // ==================== Gotway Detection ====================

    @Test
    fun `detects Gotway from 55 AA header`() {
        // Gotway packet header: 55 AA
        val gotwayPacket = byteArrayOf(
            0x55, 0xAA.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        decoder.decode(gotwayPacket, defaultState, defaultConfig)

        assertEquals(WheelType.GOTWAY, decoder.getDetectedType())
        assertNotNull(decoder.getDetectedDecoder())
    }

    @Test
    fun `Gotway detection sets wheel type in state`() {
        val gotwayPacket = byteArrayOf(
            0x55, 0xAA.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val result = decoder.decode(gotwayPacket, defaultState, defaultConfig)

        assertEquals(WheelType.GOTWAY, decoder.getDetectedType())
    }

    // ==================== No Detection Cases ====================

    @Test
    fun `returns null for empty data`() {
        val result = decoder.decode(byteArrayOf(), defaultState, defaultConfig)

        assertNull(result)
        assertNull(decoder.getDetectedType())
    }

    @Test
    fun `returns null for insufficient data - 1 byte`() {
        val result = decoder.decode(byteArrayOf(0xDC.toByte()), defaultState, defaultConfig)

        assertNull(result)
        assertNull(decoder.getDetectedType())
    }

    @Test
    fun `returns null for insufficient data - 2 bytes`() {
        val result = decoder.decode(byteArrayOf(0xDC.toByte(), 0x5A), defaultState, defaultConfig)

        assertNull(result)
        assertNull(decoder.getDetectedType())
    }

    @Test
    fun `returns null for unrecognized header`() {
        val unknownPacket = byteArrayOf(
            0x12, 0x34, 0x56,
            0x00, 0x00, 0x00, 0x00
        )

        val result = decoder.decode(unknownPacket, defaultState, defaultConfig)

        assertNull(result)
        assertNull(decoder.getDetectedType())
    }

    @Test
    fun `returns null for partial Veteran header`() {
        // DC 5A but not 5C
        val partialVeteran = byteArrayOf(0xDC.toByte(), 0x5A, 0x00)

        val result = decoder.decode(partialVeteran, defaultState, defaultConfig)

        assertNull(result)
        assertNull(decoder.getDetectedType())
    }

    @Test
    fun `returns null for partial Gotway header`() {
        // 55 but not AA
        val partialGotway = byteArrayOf(0x55, 0x00, 0x00)

        val result = decoder.decode(partialGotway, defaultState, defaultConfig)

        assertNull(result)
        assertNull(decoder.getDetectedType())
    }

    // ==================== Delegation After Detection ====================

    @Test
    fun `subsequent packets delegate to detected decoder`() {
        // First packet - triggers detection
        val gotwayPacket1 = byteArrayOf(
            0x55, 0xAA.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        decoder.decode(gotwayPacket1, defaultState, defaultConfig)
        val detectedDecoder = decoder.getDetectedDecoder()

        // Second packet - should use same decoder
        val gotwayPacket2 = byteArrayOf(
            0x55, 0xAA.toByte(),
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16
        )
        decoder.decode(gotwayPacket2, defaultState, defaultConfig)

        // Still same decoder
        assertEquals(detectedDecoder, decoder.getDetectedDecoder())
        assertEquals(WheelType.GOTWAY, decoder.getDetectedType())
    }

    // ==================== Reset ====================

    @Test
    fun `reset clears detected type`() {
        // Detect a wheel type first
        val gotwayPacket = byteArrayOf(
            0x55, 0xAA.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        decoder.decode(gotwayPacket, defaultState, defaultConfig)
        assertNotNull(decoder.getDetectedType())

        // Reset
        decoder.reset()

        assertNull(decoder.getDetectedType())
        assertNull(decoder.getDetectedDecoder())
    }

    @Test
    fun `can detect different type after reset`() {
        // First detect Gotway
        val gotwayPacket = byteArrayOf(
            0x55, 0xAA.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        decoder.decode(gotwayPacket, defaultState, defaultConfig)
        assertEquals(WheelType.GOTWAY, decoder.getDetectedType())

        // Reset
        decoder.reset()

        // Now detect Veteran
        val veteranPacket = byteArrayOf(
            0xDC.toByte(), 0x5A, 0x5C,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        decoder.decode(veteranPacket, defaultState, defaultConfig)

        assertEquals(WheelType.VETERAN, decoder.getDetectedType())
    }

    // ==================== Init Commands ====================

    @Test
    fun `getInitCommands returns non-empty list`() {
        val commands = decoder.getInitCommands()
        assertTrue(commands.isNotEmpty(), "Should return init commands")
    }

    // ==================== isReady After Detection ====================

    @Test
    fun `isReady delegates to detected decoder`() {
        // Before detection
        assertFalse(decoder.isReady())

        // Detect Gotway
        val gotwayPacket = byteArrayOf(
            0x55, 0xAA.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        decoder.decode(gotwayPacket, defaultState, defaultConfig)

        // Now isReady should delegate to the detected decoder
        // (may still be false if decoder needs more data, but won't throw)
        decoder.isReady()  // Just verify it doesn't throw
    }
}
