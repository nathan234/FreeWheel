package org.freewheel.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for buildCommand() across all decoders.
 */
class WheelCommandTest {

    // ==================== Kingsong ====================

    @Test
    fun `KingsongDecoder beep returns frame with type 0x88`() {
        val decoder = KingsongDecoder()
        val commands = decoder.buildCommand(WheelCommand.Beep)
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertEquals(20, cmd.data.size, "Kingsong frame must be 20 bytes")
        assertEquals(0xAA.toByte(), cmd.data[0], "Header byte 0")
        assertEquals(0x55.toByte(), cmd.data[1], "Header byte 1")
        assertEquals(0x88.toByte(), cmd.data[16], "Frame type should be 0x88")
        assertEquals(0x14.toByte(), cmd.data[17])
        assertEquals(0x5A.toByte(), cmd.data[18])
        assertEquals(0x5A.toByte(), cmd.data[19])
    }

    @Test
    fun `KingsongDecoder SetLight true returns frame with type 0x73`() {
        val decoder = KingsongDecoder()
        val commands = decoder.buildCommand(WheelCommand.SetLight(true))
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertEquals(20, cmd.data.size)
        assertEquals(0x73.toByte(), cmd.data[16], "Frame type should be 0x73")
        // SetLight(true) maps to mode 1 = 0x13 (light on in KS protocol)
        assertEquals(0x13.toByte(), cmd.data[2], "Light on mode byte")
        assertEquals(0x00.toByte(), cmd.data[3], "Voice preserve byte")
    }

    @Test
    fun `KingsongDecoder SetLight false returns frame with type 0x73`() {
        val decoder = KingsongDecoder()
        val commands = decoder.buildCommand(WheelCommand.SetLight(false))
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertEquals(20, cmd.data.size)
        assertEquals(0x73.toByte(), cmd.data[16], "Frame type should be 0x73")
        // SetLight(false) maps to mode 0 = 0x12 (light off in KS protocol)
        assertEquals(0x12.toByte(), cmd.data[2], "Light off mode byte")
    }

    @Test
    fun `KingsongDecoder Calibrate returns frame with type 0x89`() {
        val decoder = KingsongDecoder()
        val commands = decoder.buildCommand(WheelCommand.Calibrate)
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertEquals(20, cmd.data.size, "Kingsong frame must be 20 bytes")
        assertEquals(0x89.toByte(), cmd.data[16], "Frame type should be 0x89")
        assertEquals(0x5A.toByte(), cmd.data[18], "Trailer byte")
        assertEquals(0x5A.toByte(), cmd.data[19], "Trailer byte")
    }

    @Test
    fun `KingsongDecoder PowerOff returns frame with type 0x40`() {
        val decoder = KingsongDecoder()
        val commands = decoder.buildCommand(WheelCommand.PowerOff)
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertEquals(20, cmd.data.size, "Kingsong frame must be 20 bytes")
        assertEquals(0x40.toByte(), cmd.data[16], "Frame type should be 0x40")
        assertEquals(0x5A.toByte(), cmd.data[18], "Trailer byte")
        assertEquals(0x5A.toByte(), cmd.data[19], "Trailer byte")
    }

    @Test
    fun `KingsongDecoder SetLock returns empty`() {
        val decoder = KingsongDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.SetLock(true)).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.SetLock(false)).isEmpty())
    }

    @Test
    fun `KingsongDecoder ResetTrip returns empty`() {
        val decoder = KingsongDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.ResetTrip).isEmpty())
    }

    @Test
    fun `KingsongDecoder unsupported command returns empty list`() {
        val decoder = KingsongDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.SetMilesMode(true)).isEmpty())
    }

    // ==================== Gotway ====================

    @Test
    fun `GotwayDecoder Calibrate returns two-step c then y with 300ms delay`() {
        val decoder = GotwayDecoder()
        val commands = decoder.buildCommand(WheelCommand.Calibrate)
        assertEquals(2, commands.size, "Calibrate should produce 2 steps")
        val step1 = commands[0] as WheelCommand.SendBytes
        assertEquals("c", step1.data.decodeToString(), "First step sends 'c'")
        val step2 = commands[1] as WheelCommand.SendDelayed
        assertEquals("y", step2.data.decodeToString(), "Second step sends 'y'")
        assertEquals(300L, step2.delayMs, "Delay should be 300ms")
    }

    @Test
    fun `GotwayDecoder PowerOff returns empty`() {
        val decoder = GotwayDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.PowerOff).isEmpty())
    }

    @Test
    fun `GotwayDecoder SetLock returns empty`() {
        val decoder = GotwayDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.SetLock(true)).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.SetLock(false)).isEmpty())
    }

    @Test
    fun `GotwayDecoder ResetTrip returns empty`() {
        val decoder = GotwayDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.ResetTrip).isEmpty())
    }

    // ==================== Veteran ====================

    @Test
    fun `VeteranDecoder beep returns SendBytes with b`() {
        val decoder = VeteranDecoder()
        val commands = decoder.buildCommand(WheelCommand.Beep)
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertEquals("b", cmd.data.decodeToString())
    }

    @Test
    fun `VeteranDecoder SetLight true returns SetLightON`() {
        val decoder = VeteranDecoder()
        val commands = decoder.buildCommand(WheelCommand.SetLight(true))
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertEquals("SetLightON", cmd.data.decodeToString())
    }

    @Test
    fun `VeteranDecoder SetLight false returns SetLightOFF`() {
        val decoder = VeteranDecoder()
        val commands = decoder.buildCommand(WheelCommand.SetLight(false))
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertEquals("SetLightOFF", cmd.data.decodeToString())
    }

    @Test
    fun `VeteranDecoder ResetTrip returns CLEARMETER`() {
        val decoder = VeteranDecoder()
        val commands = decoder.buildCommand(WheelCommand.ResetTrip)
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertEquals("CLEARMETER", cmd.data.decodeToString())
    }

    @Test
    fun `VeteranDecoder Calibrate returns empty`() {
        val decoder = VeteranDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.Calibrate).isEmpty())
    }

    @Test
    fun `VeteranDecoder PowerOff returns empty`() {
        val decoder = VeteranDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.PowerOff).isEmpty())
    }

    @Test
    fun `VeteranDecoder SetLock returns empty`() {
        val decoder = VeteranDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.SetLock(true)).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.SetLock(false)).isEmpty())
    }

    // ==================== InMotion V1 ====================

    @Test
    fun `InMotionDecoder beep returns non-empty result`() {
        val decoder = InMotionDecoder()
        val commands = decoder.buildCommand(WheelCommand.Beep)
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertTrue(cmd.data.isNotEmpty(), "Beep command should produce bytes")
    }

    @Test
    fun `InMotionDecoder SetLight returns non-empty result`() {
        val decoder = InMotionDecoder()
        val commandsOn = decoder.buildCommand(WheelCommand.SetLight(true))
        assertEquals(1, commandsOn.size)
        assertTrue((commandsOn[0] as WheelCommand.SendBytes).data.isNotEmpty())

        val commandsOff = decoder.buildCommand(WheelCommand.SetLight(false))
        assertEquals(1, commandsOff.size)
        assertTrue((commandsOff[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `InMotionDecoder Calibrate returns non-empty result`() {
        val decoder = InMotionDecoder()
        val commands = decoder.buildCommand(WheelCommand.Calibrate)
        assertEquals(1, commands.size)
        assertTrue((commands[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `InMotionDecoder PowerOff returns non-empty result`() {
        val decoder = InMotionDecoder()
        val commands = decoder.buildCommand(WheelCommand.PowerOff)
        assertEquals(1, commands.size)
        assertTrue((commands[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `InMotionDecoder SetLock returns empty`() {
        val decoder = InMotionDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.SetLock(true)).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.SetLock(false)).isEmpty())
    }

    @Test
    fun `InMotionDecoder ResetTrip returns empty`() {
        val decoder = InMotionDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.ResetTrip).isEmpty())
    }

    @Test
    fun `InMotionDecoder unsupported command returns empty list`() {
        val decoder = InMotionDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.SetMilesMode(true)).isEmpty())
    }

    // ==================== InMotion V2 ====================

    @Test
    fun `InMotionV2Decoder beep returns non-empty result`() {
        val decoder = InMotionV2Decoder()
        val commands = decoder.buildCommand(WheelCommand.Beep)
        assertEquals(1, commands.size)
        val cmd = commands[0] as WheelCommand.SendBytes
        assertTrue(cmd.data.isNotEmpty(), "Beep command should produce bytes")
    }

    @Test
    fun `InMotionV2Decoder SetLight returns non-empty result`() {
        val decoder = InMotionV2Decoder()
        val commandsOn = decoder.buildCommand(WheelCommand.SetLight(true))
        assertEquals(1, commandsOn.size)
        assertTrue((commandsOn[0] as WheelCommand.SendBytes).data.isNotEmpty())

        val commandsOff = decoder.buildCommand(WheelCommand.SetLight(false))
        assertEquals(1, commandsOff.size)
        assertTrue((commandsOff[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `InMotionV2Decoder Calibrate returns non-empty result`() {
        val decoder = InMotionV2Decoder()
        val commands = decoder.buildCommand(WheelCommand.Calibrate)
        assertEquals(1, commands.size)
        assertTrue((commands[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `InMotionV2Decoder PowerOff returns non-empty result`() {
        val decoder = InMotionV2Decoder()
        val commands = decoder.buildCommand(WheelCommand.PowerOff)
        assertEquals(1, commands.size)
        assertTrue((commands[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `InMotionV2Decoder SetLock returns non-empty result`() {
        val decoder = InMotionV2Decoder()
        val commandsOn = decoder.buildCommand(WheelCommand.SetLock(true))
        assertEquals(1, commandsOn.size)
        assertTrue((commandsOn[0] as WheelCommand.SendBytes).data.isNotEmpty())

        val commandsOff = decoder.buildCommand(WheelCommand.SetLock(false))
        assertEquals(1, commandsOff.size)
        assertTrue((commandsOff[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `InMotionV2Decoder ResetTrip returns empty`() {
        val decoder = InMotionV2Decoder()
        assertTrue(decoder.buildCommand(WheelCommand.ResetTrip).isEmpty())
    }

    @Test
    fun `InMotionV2Decoder unsupported command returns empty list`() {
        val decoder = InMotionV2Decoder()
        assertTrue(decoder.buildCommand(WheelCommand.SetMilesMode(true)).isEmpty())
    }

    // ==================== Ninebot ====================

    @Test
    fun `NinebotDecoder returns empty list for all commands`() {
        val decoder = NinebotDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.Beep).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.SetLight(true)).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.SetLight(false)).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.Calibrate).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.PowerOff).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.SetLock(true)).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.ResetTrip).isEmpty())
    }

    // ==================== Ninebot Z ====================

    @Test
    fun `NinebotZDecoder SetLight returns non-empty result`() {
        val decoder = NinebotZDecoder()
        // NinebotZ now supports SetLight via DriveFlags
        val commandsOn = decoder.buildCommand(WheelCommand.SetLight(true))
        assertEquals(1, commandsOn.size)
        assertTrue((commandsOn[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `NinebotZDecoder Calibrate returns non-empty CAN message`() {
        val decoder = NinebotZDecoder()
        val commands = decoder.buildCommand(WheelCommand.Calibrate)
        assertEquals(1, commands.size)
        assertTrue((commands[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `NinebotZDecoder SetLock true returns non-empty CAN message`() {
        val decoder = NinebotZDecoder()
        val commands = decoder.buildCommand(WheelCommand.SetLock(true))
        assertEquals(1, commands.size)
        assertTrue((commands[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `NinebotZDecoder SetLock false returns non-empty CAN message`() {
        val decoder = NinebotZDecoder()
        val commands = decoder.buildCommand(WheelCommand.SetLock(false))
        assertEquals(1, commands.size)
        assertTrue((commands[0] as WheelCommand.SendBytes).data.isNotEmpty())
    }

    @Test
    fun `NinebotZDecoder PowerOff returns empty`() {
        val decoder = NinebotZDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.PowerOff).isEmpty())
    }

    @Test
    fun `NinebotZDecoder ResetTrip returns empty`() {
        val decoder = NinebotZDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.ResetTrip).isEmpty())
    }

    @Test
    fun `NinebotZDecoder unsupported command returns empty list`() {
        val decoder = NinebotZDecoder()
        assertTrue(decoder.buildCommand(WheelCommand.Beep).isEmpty())
        assertTrue(decoder.buildCommand(WheelCommand.SetMilesMode(true)).isEmpty())
    }
}
