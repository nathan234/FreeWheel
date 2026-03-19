package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.freewheel.core.protocol.DecodeResult

/**
 * Tests for InMotionDecoder (V1 protocol) and InMotionV2Decoder.
 */
class InMotionDecoderTest {

    private val config = DecoderConfig(
        useMph = false,
        useFahrenheit = false,
        useCustomPercents = false
    )


    @Test
    fun `InMotionUnpacker handles header correctly`() {
        val unpacker = InMotionUnpacker()

        // Send AA AA header
        assertFalse(unpacker.addChar(0xAA))
        assertFalse(unpacker.addChar(0xAA))

        // Now in collecting state, but no complete frame
        val buffer = unpacker.getBuffer()
        assertEquals(2, buffer.size)
        assertEquals(0xAA.toByte(), buffer[0])
        assertEquals(0xAA.toByte(), buffer[1])
    }

    @Test
    fun `InMotionUnpacker resets correctly`() {
        val unpacker = InMotionUnpacker()

        // Send some data
        unpacker.addChar(0xAA)
        unpacker.addChar(0xAA)
        unpacker.addChar(0x01)

        // Reset
        unpacker.reset()
        val buffer = unpacker.getBuffer()
        assertEquals(0, buffer.size)
    }

    @Test
    fun `InMotionV2Unpacker handles header correctly`() {
        val unpacker = InMotionV2Unpacker()

        // Send AA AA header
        assertFalse(unpacker.addChar(0xAA))
        assertFalse(unpacker.addChar(0xAA))

        // Now should be looking for flags
        val buffer = unpacker.getBuffer()
        assertEquals(2, buffer.size)
    }

    @Test
    fun `InMotionV2Unpacker handles escape bytes`() {
        val unpacker = InMotionV2Unpacker()

        // Start frame
        unpacker.addChar(0xAA)
        unpacker.addChar(0xAA)

        // Send flags
        unpacker.addChar(0x11) // INITIAL flag

        // Send length (includes command)
        unpacker.addChar(0x02) // 2 bytes: command + 1 data byte

        // Send command
        unpacker.addChar(0x01)

        // Send data byte
        unpacker.addChar(0x01)

        // Send checksum - XOR of (0x11 ^ 0x02 ^ 0x01 ^ 0x01) = 0x13
        val complete = unpacker.addChar(0x13)

        assertTrue(complete)
        val buffer = unpacker.getBuffer()
        assertEquals(7, buffer.size)
    }

    @Test
    fun `InMotionDecoder initialization`() {
        val decoder = InMotionDecoder()
        assertEquals(WheelType.INMOTION, decoder.wheelType)
        assertFalse(decoder.isReady())
    }

    @Test
    fun `InMotionDecoder reset clears state`() {
        val decoder = InMotionDecoder()

        // Process some data (even if invalid, it sets internal state)
        decoder.decode(byteArrayOf(0xAA.toByte(), 0xAA.toByte()), DecoderState(), config)

        // Reset
        decoder.reset()
        assertFalse(decoder.isReady())
    }

    @Test
    fun `InMotionDecoder getKeepAliveCommand returns valid command`() {
        val decoder = InMotionDecoder()
        val command = decoder.getKeepAliveCommand()

        assertNotNull(command)
        assertTrue(command is WheelCommand.SendBytes)
        val bytes = (command as WheelCommand.SendBytes).data

        // Should start with AA AA header
        assertEquals(0xAA.toByte(), bytes[0])
        assertEquals(0xAA.toByte(), bytes[1])
    }

    @Test
    fun `InMotionV2Decoder initialization`() {
        val decoder = InMotionV2Decoder()
        assertEquals(WheelType.INMOTION_V2, decoder.wheelType)
        assertFalse(decoder.isReady())
    }

    @Test
    fun `InMotionV2Decoder reset clears state`() {
        val decoder = InMotionV2Decoder()
        decoder.reset()
        assertFalse(decoder.isReady())
    }

    @Test
    fun `InMotionV2Decoder getInitCommands returns valid commands`() {
        val decoder = InMotionV2Decoder()
        val commands = decoder.getInitCommands()

        assertTrue(commands.isNotEmpty())
        // First command should be car type request
        val firstCmd = commands[0]
        assertTrue(firstCmd is WheelCommand.SendBytes)
    }

    @Test
    fun `InMotionV2Decoder getKeepAliveCommand returns valid command`() {
        val decoder = InMotionV2Decoder()
        val command = decoder.getKeepAliveCommand()

        assertTrue(command is WheelCommand.SendBytes)
        val bytes = (command as WheelCommand.SendBytes).data

        // Should start with AA AA header
        assertEquals(0xAA.toByte(), bytes[0])
        assertEquals(0xAA.toByte(), bytes[1])
    }

    @Test
    fun `InMotionDecoder CANMessage standardMessage builds correctly`() {
        val msg = InMotionDecoder.CANMessage.standardMessage()
        assertEquals(InMotionDecoder.IDValue.GetFastInfo.value, msg.id)
        assertEquals(8, msg.len)
        assertEquals(5, msg.ch)
    }

    @Test
    fun `InMotionDecoder CANMessage getSlowData builds correctly`() {
        val msg = InMotionDecoder.CANMessage.getSlowData()
        assertEquals(InMotionDecoder.IDValue.GetSlowInfo.value, msg.id)
        assertEquals(8, msg.len)
        assertEquals(1, msg.type) // RemoteFrame
    }

    @Test
    fun `InMotionDecoder CANMessage setLight builds correctly`() {
        val msgOn = InMotionDecoder.CANMessage.setLight(true)
        assertEquals(1.toByte(), msgOn.data[0])

        val msgOff = InMotionDecoder.CANMessage.setLight(false)
        assertEquals(0.toByte(), msgOff.data[0])
    }

    @Test
    fun `InMotionDecoder Model findById returns correct model`() {
        val v8 = InMotionDecoder.Model.findById("80")
        assertEquals(InMotionDecoder.Model.V8, v8)

        val v10f = InMotionDecoder.Model.findById("141")
        assertEquals(InMotionDecoder.Model.V10F, v10f)

        val unknown = InMotionDecoder.Model.findById("999")
        assertEquals(InMotionDecoder.Model.UNKNOWN, unknown)
    }

    @Test
    fun `InMotionDecoder Model belongsToInputType works correctly`() {
        val v5 = InMotionDecoder.Model.V5
        assertTrue(v5.belongsToInputType("5"))
        assertFalse(v5.belongsToInputType("8"))

        val v8 = InMotionDecoder.Model.V8
        assertTrue(v8.belongsToInputType("8"))
        assertFalse(v8.belongsToInputType("5"))
    }

    @Test
    fun `InMotionDecoder batteryFromVoltage V8 series`() {
        // V8 at full charge (~84V)
        val fullBatt = InMotionDecoder.batteryFromVoltage(8400, InMotionDecoder.Model.V8, true)
        assertEquals(100, fullBatt)

        // V8 at empty (~68V)
        val emptyBatt = InMotionDecoder.batteryFromVoltage(6800, InMotionDecoder.Model.V8, true)
        assertEquals(0, emptyBatt)

        // V8 at mid charge (~76V)
        val midBatt = InMotionDecoder.batteryFromVoltage(7600, InMotionDecoder.Model.V8, true)
        assertTrue(midBatt in 40..60, "Mid battery should be around 50%: $midBatt")
    }

    @Test
    fun `InMotionV2Decoder Model findById returns correct model`() {
        val v11 = InMotionV2Decoder.Model.findById(6, 1)
        assertEquals(InMotionV2Decoder.Model.V11, v11)

        val v12hs = InMotionV2Decoder.Model.findById(7, 1)
        assertEquals(InMotionV2Decoder.Model.V12HS, v12hs)

        val v13 = InMotionV2Decoder.Model.findById(8, 1)
        assertEquals(InMotionV2Decoder.Model.V13, v13)

        val unknown = InMotionV2Decoder.Model.findById(99, 99)
        assertEquals(InMotionV2Decoder.Model.UNKNOWN, unknown)
    }

    @Test
    fun `InMotionV2Decoder static message builders work`() {
        val carTypeMsg = InMotionV2Decoder.getCarTypeMessage()
        assertTrue(carTypeMsg.isNotEmpty())
        assertEquals(0xAA.toByte(), carTypeMsg[0])
        assertEquals(0xAA.toByte(), carTypeMsg[1])

        val serialMsg = InMotionV2Decoder.getSerialNumberMessage()
        assertTrue(serialMsg.isNotEmpty())

        val versionsMsg = InMotionV2Decoder.getVersionsMessage()
        assertTrue(versionsMsg.isNotEmpty())

        val settingsMsg = InMotionV2Decoder.getCurrentSettingsMessage()
        assertTrue(settingsMsg.isNotEmpty())

        val realTimeMsg = InMotionV2Decoder.getRealTimeDataMessage()
        assertTrue(realTimeMsg.isNotEmpty())

        val statsMsg = InMotionV2Decoder.getStatisticsMessage()
        assertTrue(statsMsg.isNotEmpty())

        val lightOnMsg = InMotionV2Decoder.setLightMessage(true)
        assertTrue(lightOnMsg.isNotEmpty())

        val lockMsg = InMotionV2Decoder.setLockMessage(true)
        assertTrue(lockMsg.isNotEmpty())

        val beepMsg = InMotionV2Decoder.playBeepMessage()
        assertTrue(beepMsg.isNotEmpty())
    }

    @Test
    fun `InMotionDecoder getModelString returns correct names`() {
        assertEquals("InMotion V8", InMotionDecoder.getModelString(InMotionDecoder.Model.V8))
        assertEquals("InMotion V10F", InMotionDecoder.getModelString(InMotionDecoder.Model.V10F))
        assertEquals("Solowheel Glide 3", InMotionDecoder.getModelString(InMotionDecoder.Model.Glide3))
        assertEquals("Unknown", InMotionDecoder.getModelString(InMotionDecoder.Model.UNKNOWN))
    }

    // ==================== Bounds Check Tests ====================

    @Test
    fun `truncated alert frame returns null`() {
        val msg = InMotionDecoder.CANMessage.standardMessage()
        // Alert message accesses data[0..7], but set data to only 4 bytes
        msg.data = byteArrayOf(0x05, 0x00, 0x00, 0x00)
        msg.id = InMotionDecoder.IDValue.Alert.value
        val result = msg.parseAlertInfoMessage(DecoderState())
        assertNull(result, "Alert with < 8 data bytes should return null")
    }

    @Test
    fun `full alert frame parses successfully`() {
        val msg = InMotionDecoder.CANMessage.standardMessage()
        msg.data = ByteArray(8) // 8 bytes, all zeros
        msg.id = InMotionDecoder.IDValue.Alert.value
        val result = msg.parseAlertInfoMessage(DecoderState())
        assertNotNull(result, "Alert with 8 data bytes should parse")
    }

    @Test
    fun `truncated slow info frame returns null`() {
        val msg = InMotionDecoder.CANMessage.standardMessage()
        msg.id = InMotionDecoder.IDValue.GetSlowInfo.value
        // Set exData to < 108 bytes — should return null
        msg.exData = ByteArray(50) // Too short
        val result = msg.parseSlowInfoMessage(DecoderState())
        assertNull(result, "SlowInfo with < 108 exData bytes should return null")
    }

    @Test
    fun `slow info frame with 108 bytes parses successfully`() {
        val msg = InMotionDecoder.CANMessage.standardMessage()
        msg.id = InMotionDecoder.IDValue.GetSlowInfo.value
        msg.exData = ByteArray(108) // Minimum valid size
        val result = msg.parseSlowInfoMessage(DecoderState())
        assertNotNull(result, "SlowInfo with 108 exData bytes should parse")
    }

    @Test
    fun `InMotionDecoder keepAliveIntervalMs is correct`() {
        val decoder = InMotionDecoder()
        assertEquals(250L, decoder.keepAliveIntervalMs)
    }

    @Test
    fun `InMotionV2Decoder keepAliveIntervalMs is correct`() {
        val decoder = InMotionV2Decoder()
        assertEquals(250L, decoder.keepAliveIntervalMs)
    }
}
