package org.freewheel.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for NinebotZDecoder.
 *
 * The NinebotZ protocol uses XOR-encrypted CAN messages, making it impractical
 * to construct raw BLE frames for decode() testing without a known gamma key.
 * These tests focus on the public API surface: keep-alive, state management,
 * gamma accessors, BMS reading mode, and buildCommand behavior.
 */
class NinebotZDecoderTest {

    private val decoder = NinebotZDecoder()

    @Test
    fun `keepAliveIntervalMs is 125`() {
        assertEquals(125L, decoder.keepAliveIntervalMs)
    }

    @Test
    fun `initial state isReady is false`() {
        assertFalse(decoder.isReady())
    }

    @Test
    fun `getKeepAliveCommand at INIT returns non-null`() {
        // At INIT state, the decoder should return a getBleVersion command
        val command = decoder.getKeepAliveCommand()
        assertNotNull(command)
        assertTrue(command is WheelCommand.SendBytes)
    }

    @Test
    fun `setBmsReadingMode does not change state when not READY`() {
        // Fresh decoder is in INIT state. Enabling BMS reading should not
        // transition to BMS1_SN since the precondition is connectionState == READY.
        val commandBefore = decoder.getKeepAliveCommand()
        decoder.setBmsReadingMode(true)
        val commandAfter = decoder.getKeepAliveCommand()

        // Both should be the same INIT-level command (getBleVersion)
        assertNotNull(commandBefore)
        assertNotNull(commandAfter)
        assertTrue(commandBefore is WheelCommand.SendBytes)
        assertTrue(commandAfter is WheelCommand.SendBytes)

        // The byte payloads should be identical since state didn't change
        assertEquals(
            (commandBefore as WheelCommand.SendBytes).data.toList(),
            (commandAfter as WheelCommand.SendBytes).data.toList()
        )
    }

    @Test
    fun `getGamma returns copy of gamma`() {
        val gamma = decoder.getGamma()

        // Modify the returned array
        gamma[0] = 0xFF.toByte()

        // Original gamma should be unchanged (still all zeros)
        val gammaAgain = decoder.getGamma()
        assertEquals(0.toByte(), gammaAgain[0])
    }

    @Test
    fun `setGamma with valid 16-byte key`() {
        val key = ByteArray(16) { (it + 1).toByte() }
        decoder.setGamma(key)

        val gamma = decoder.getGamma()
        assertEquals(key.toList(), gamma.toList())
    }

    @Test
    fun `setGamma with wrong size is rejected`() {
        // Try setting a 15-byte key
        val badKey = ByteArray(15) { 0xAB.toByte() }
        decoder.setGamma(badKey)

        // Gamma should still be all zeros
        val gamma = decoder.getGamma()
        assertEquals(ByteArray(16) { 0 }.toList(), gamma.toList())
    }

    @Test
    fun `reset clears gamma and state`() {
        // Set a gamma key first
        val key = ByteArray(16) { 0x42 }
        decoder.setGamma(key)

        // Reset
        decoder.reset()

        // Gamma should be back to all zeros
        val gamma = decoder.getGamma()
        assertEquals(ByteArray(16) { 0 }.toList(), gamma.toList())

        // isReady should be false (back to INIT)
        assertFalse(decoder.isReady())
    }

    @Test
    fun `buildCommand SetSpeakerVolume returns non-empty`() {
        val commands = decoder.buildCommand(WheelCommand.SetSpeakerVolume(5))
        assertTrue(commands.isNotEmpty(), "SetSpeakerVolume should produce commands")
        assertTrue(commands.first() is WheelCommand.SendBytes)
    }

    @Test
    fun `buildCommand Beep returns empty`() {
        // NinebotZ does not support Beep - falls through to else -> emptyList()
        val commands = decoder.buildCommand(WheelCommand.Beep)
        assertTrue(commands.isEmpty(), "Beep should not be supported by NinebotZ")
    }

    @Test
    fun `buildCommand SetLight returns non-empty`() {
        val commands = decoder.buildCommand(WheelCommand.SetLight(true))
        assertTrue(commands.isNotEmpty(), "SetLight should produce commands")
        assertTrue(commands.first() is WheelCommand.SendBytes)
    }
}
