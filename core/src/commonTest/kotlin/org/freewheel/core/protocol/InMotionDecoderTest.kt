package org.freewheel.core.protocol

import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.domain.settings.SettingsCommandId
import org.freewheel.core.domain.settings.WheelSettings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.freewheel.core.protocol.DecodeResult

/**
 * Tests for InMotionDecoder (V1 protocol) and LorinDecoder.
 */
class InMotionDecoderTest {

    private val config = DecoderConfig(
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
    fun `LorinUnpacker handles header correctly`() {
        val unpacker = LorinUnpacker()

        // Send AA AA header
        assertFalse(unpacker.addChar(0xAA))
        assertFalse(unpacker.addChar(0xAA))

        // Now should be looking for flags
        val buffer = unpacker.getBuffer()
        assertEquals(2, buffer.size)
    }

    @Test
    fun `LorinUnpacker handles escape bytes`() {
        val unpacker = LorinUnpacker()

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
    fun `InMotionDecoder sends configured password six times before slow discovery`() {
        val decoder = InMotionDecoder()
        decoder.updateConfig(config.copy(wheelPassword = "123456"))

        repeat(6) {
            val command = decoder.getKeepAliveCommand()
            assertNotNull(command)
            assertTrue(command is WheelCommand.SendBytes)
            assertContentEquals(
                InMotionDecoder.CANMessage.getPassword("123456").writeBuffer(),
                command.data,
                "Password attempt ${it + 1} should use the configured six-digit PIN"
            )
        }

        assertContentEquals(
            InMotionDecoder.CANMessage.getSlowData().writeBuffer(),
            (decoder.getKeepAliveCommand() as WheelCommand.SendBytes).data,
            "After six password attempts the decoder should continue with slow discovery"
        )
    }

    @Test
    fun `InMotionDecoder uses default password when configured PIN is invalid`() {
        listOf("12ab56", "١٢٣٤٥٦").forEach { invalidPin ->
            val decoder = InMotionDecoder()
            decoder.updateConfig(config.copy(wheelPassword = invalidPin))

            assertContentEquals(
                InMotionDecoder.CANMessage.getPassword("000000").writeBuffer(),
                (decoder.getKeepAliveCommand() as WheelCommand.SendBytes).data
            )
        }
    }

    @Test
    fun `InMotionDecoder password acknowledgement stops retries early`() {
        val decoder = InMotionDecoder()
        decoder.updateConfig(config.copy(wheelPassword = "654321"))

        assertContentEquals(
            InMotionDecoder.CANMessage.getPassword("654321").writeBuffer(),
            (decoder.getKeepAliveCommand() as WheelCommand.SendBytes).data
        )
        val result = decoder.decode(
            InMotionDecoder.CANMessage.getPassword("654321").writeBuffer(),
            DecoderState(),
            config
        )
        assertTrue(result is DecodeResult.Success)

        assertContentEquals(
            InMotionDecoder.CANMessage.getSlowData().writeBuffer(),
            (decoder.getKeepAliveCommand() as WheelCommand.SendBytes).data,
            "A PIN response should immediately advance to slow discovery"
        )
    }

    @Test
    fun `InMotionDecoder reset replays authentication with configured password`() {
        val decoder = InMotionDecoder()
        decoder.updateConfig(config.copy(wheelPassword = "234567"))
        acknowledgePassword(decoder, "234567")

        decoder.reset()

        assertContentEquals(
            InMotionDecoder.CANMessage.getPassword("234567").writeBuffer(),
            (decoder.getKeepAliveCommand() as WheelCommand.SendBytes).data
        )
    }

    @Test
    fun `InMotionDecoder switches to fast keep-alive after valid slow response`() {
        val decoder = InMotionDecoder()
        acknowledgePassword(decoder)
        val slowResponse = InMotionDecoder.CANMessage.standardMessage().apply {
            id = InMotionDecoder.IDValue.GetSlowInfo.value
            len = 0xFE
            format = 1
            data = byteArrayOf(108, 0, 0, 0, 0, 0, 0, 0)
            exData = ByteArray(108).also { data ->
                data[104] = 6 // Combined with byte 107 below: model ID "86" (V8F)
                data[107] = 8
            }
        }

        val result = decoder.decode(slowResponse.writeBuffer(), DecoderState(), config)
        assertTrue(result is DecodeResult.Success)
        assertTrue(decoder.isReady())

        val command = decoder.getKeepAliveCommand()
        assertTrue(command is WheelCommand.SendBytes)
        assertContentEquals(
            InMotionDecoder.CANMessage.standardMessage().writeBuffer(),
            command.data,
            "A resolved decoder should poll fast telemetry"
        )
    }

    @Test
    fun `InMotionDecoder slow response surfaces authoritative V1 settings`() {
        val decoder = InMotionDecoder()
        val slowData = ByteArray(133).apply {
            this[104] = 6
            this[107] = 8 // Model ID "86" (V8F)

            val pedalTiltRaw = 5 * 65536 // 5 degrees; protocol reports tenths to the UI
            this[56] = (pedalTiltRaw and 0xFF).toByte()
            this[57] = ((pedalTiltRaw shr 8) and 0xFF).toByte()
            this[58] = ((pedalTiltRaw shr 16) and 0xFF).toByte()
            this[59] = ((pedalTiltRaw shr 24) and 0xFF).toByte()

            val maxSpeedRaw = 45_000
            this[60] = (maxSpeedRaw and 0xFF).toByte()
            this[61] = ((maxSpeedRaw shr 8) and 0xFF).toByte()
            this[80] = 1 // Headlight on
            this[124] = (75 + 28).toByte() // Pedal sensitivity 75%

            val speakerVolumeRaw = 6_400
            this[125] = (speakerVolumeRaw and 0xFF).toByte()
            this[126] = ((speakerVolumeRaw shr 8) and 0xFF).toByte()
            this[129] = 1 // Handle button enabled (legacy stores inverse disabled flag)
            this[130] = 1 // LEDs on
            this[132] = 1 // Classic ride mode
        }
        val slowResponse = InMotionDecoder.CANMessage.standardMessage().apply {
            id = InMotionDecoder.IDValue.GetSlowInfo.value
            len = 0xFE
            format = 1
            data = byteArrayOf(133.toByte(), 0, 0, 0, 0, 0, 0, 0)
            exData = slowData
        }

        val result = decoder.decode(slowResponse.writeBuffer(), DecoderState(), config)
        assertTrue(result is DecodeResult.Success)
        val settings = result.data.assertSettings() as WheelSettings.InMotionV1

        assertEquals(1, settings.lightMode)
        assertEquals(1, settings.ledMode)
        assertEquals(true, settings.handleButton)
        assertEquals(true, settings.rideMode)
        assertEquals(45, settings.maxSpeed)
        assertEquals(50, settings.pedalTilt)
        assertEquals(75, settings.pedalSensitivity)
        assertEquals(64, settings.speakerVolume)

        // Pin the values consumed by the shared Android/iOS settings UI.
        assertEquals(1, SettingsCommandId.LIGHT_MODE.readInt(settings))
        assertEquals(true, SettingsCommandId.LED.readBool(settings))
        assertEquals(true, SettingsCommandId.HANDLE_BUTTON.readBool(settings))
        assertEquals(true, SettingsCommandId.RIDE_MODE.readBool(settings))
        assertEquals(45, SettingsCommandId.MAX_SPEED.readInt(settings))
        assertEquals(5, SettingsCommandId.PEDAL_TILT.readInt(settings))
        assertEquals(75, SettingsCommandId.PEDAL_SENSITIVITY.readInt(settings))
        assertEquals(64, SettingsCommandId.SPEAKER_VOLUME.readInt(settings))
    }

    @Test
    fun `InMotionDecoder setting acknowledgement re-arms slow settings refresh`() {
        val decoder = InMotionDecoder()
        acknowledgePassword(decoder)
        val slowResponse = InMotionDecoder.CANMessage.standardMessage().apply {
            id = InMotionDecoder.IDValue.GetSlowInfo.value
            len = 0xFE
            format = 1
            data = byteArrayOf(108, 0, 0, 0, 0, 0, 0, 0)
            exData = ByteArray(108).also { data ->
                data[104] = 6
                data[107] = 8
            }
        }
        decoder.decode(slowResponse.writeBuffer(), DecoderState(), config)
        assertContentEquals(
            InMotionDecoder.CANMessage.standardMessage().writeBuffer(),
            (decoder.getKeepAliveCommand() as WheelCommand.SendBytes).data
        )

        val lightAck = InMotionDecoder.CANMessage.setLight(true)
        val result = decoder.decode(lightAck.writeBuffer(), DecoderState(), config)
        assertTrue(result is DecodeResult.Success)

        assertContentEquals(
            InMotionDecoder.CANMessage.getSlowData().writeBuffer(),
            (decoder.getKeepAliveCommand() as WheelCommand.SendBytes).data,
            "A setting acknowledgement should trigger authoritative settings readback"
        )
    }

    private fun acknowledgePassword(decoder: InMotionDecoder, password: String = "000000") {
        assertContentEquals(
            InMotionDecoder.CANMessage.getPassword(password).writeBuffer(),
            (decoder.getKeepAliveCommand() as WheelCommand.SendBytes).data
        )
        val result = decoder.decode(
            InMotionDecoder.CANMessage.getPassword(password).writeBuffer(),
            DecoderState(),
            config
        )
        assertTrue(result is DecodeResult.Success)
    }

    @Test
    fun `LorinDecoder initialization`() {
        val decoder = LorinDecoder()
        assertEquals(WheelType.LORIN, decoder.wheelType)
        assertFalse(decoder.isReady())
    }

    @Test
    fun `LorinDecoder reset clears state`() {
        val decoder = LorinDecoder()
        decoder.reset()
        assertFalse(decoder.isReady())
    }

    @Test
    fun `LorinDecoder getInitCommands returns valid commands`() {
        val decoder = LorinDecoder()
        val commands = decoder.getInitCommands()

        assertTrue(commands.isNotEmpty())
        // First command should be car type request
        val firstCmd = commands[0]
        assertTrue(firstCmd is WheelCommand.SendBytes)
    }

    @Test
    fun `LorinDecoder getKeepAliveCommand returns valid command`() {
        val decoder = LorinDecoder()
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
    fun `LorinDecoder Model findById returns correct model`() {
        val v11 = LorinDecoder.Model.findById(6, 1)
        assertEquals(LorinDecoder.Model.V11, v11)

        val v12hs = LorinDecoder.Model.findById(7, 1)
        assertEquals(LorinDecoder.Model.V12HS, v12hs)

        val v13 = LorinDecoder.Model.findById(8, 1)
        assertEquals(LorinDecoder.Model.V13, v13)

        val unknown = LorinDecoder.Model.findById(99, 99)
        assertEquals(LorinDecoder.Model.UNKNOWN, unknown)
    }

    @Test
    fun `LorinDecoder static message builders work`() {
        val carTypeMsg = LorinDecoder.getCarTypeMessage()
        assertTrue(carTypeMsg.isNotEmpty())
        assertEquals(0xAA.toByte(), carTypeMsg[0])
        assertEquals(0xAA.toByte(), carTypeMsg[1])

        val serialMsg = LorinDecoder.getSerialNumberMessage()
        assertTrue(serialMsg.isNotEmpty())

        val versionsMsg = LorinDecoder.getVersionsMessage()
        assertTrue(versionsMsg.isNotEmpty())

        val settingsMsg = LorinDecoder.getCurrentSettingsMessage()
        assertTrue(settingsMsg.isNotEmpty())

        val realTimeMsg = LorinDecoder.getRealTimeDataMessage()
        assertTrue(realTimeMsg.isNotEmpty())

        val statsMsg = LorinDecoder.getStatisticsMessage()
        assertTrue(statsMsg.isNotEmpty())

        val lightOnMsg = LorinDecoder.setLightMessage(true)
        assertTrue(lightOnMsg.isNotEmpty())

        val lockMsg = LorinDecoder.setLockMessage(true)
        assertTrue(lockMsg.isNotEmpty())

        val beepMsg = LorinDecoder.playBeepMessage()
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
    fun `LorinDecoder keepAliveIntervalMs is correct`() {
        val decoder = LorinDecoder()
        assertEquals(250L, decoder.keepAliveIntervalMs)
    }
}
