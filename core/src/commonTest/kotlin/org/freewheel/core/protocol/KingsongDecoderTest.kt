package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import kotlin.math.roundToInt
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.protocol.DecodeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for KingsongDecoder.
 *
 * Frame format (20 bytes):
 * - Bytes 0-1:   Header (AA 55)
 * - Bytes 2-15:  Data payload (varies by frame type)
 * - Byte 16:     Frame type (0xA9=live, 0xBB=name, 0xB3=serial, 0xF1=BMS, etc.)
 * - Byte 17:     0x14 (constant), or pNum for BMS frames
 * - Bytes 18-19: Footer (5A 5A)
 *
 * Byte order: KS uses a reversed-pairs encoding. ByteUtils.getInt2R swaps each
 * pair of bytes then reads big-endian, which is equivalent to little-endian
 * reading of the original bytes. Frame builders in this file write values in LE
 * at the payload positions so that getInt2R returns the intended value.
 */
class KingsongDecoderTest {

    private val decoder = KingsongDecoder()
    private val defaultState = WheelState()
    private val defaultConfig = DecoderConfig()

    // Frame builders are in KingsongFrameBuilders.kt (same package, top-level internal functions)

    // ==================== Validation Tests ====================

    @Test
    fun `short data is rejected`() {
        decoder.reset()
        for (size in 0..19) {
            val data = ByteArray(size) { 0x55 }
            val result = decoder.decode(data, defaultState, defaultConfig)
            if (size < 20) {
                assertTrue(result is DecodeResult.Buffering, "Should return Buffering for data of size $size")
            }
        }
    }

    @Test
    fun `wrong header is rejected`() {
        decoder.reset()
        // Neither AA 55 nor 55 AA
        val data = ByteArray(20)
        data[0] = 0x12
        data[1] = 0x34
        data[16] = 0xA9.toByte() // valid frame type
        data[18] = 0x5A
        data[19] = 0x5A

        val result = decoder.decode(data, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Buffering, "Wrong header should return Buffering")
    }

    @Test
    fun `AA 55 header is accepted`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6000)
        assertEquals(0xAA.toByte(), packet[0])
        assertEquals(0x55.toByte(), packet[1])

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success, "AA 55 header should be accepted")
    }

    // ==================== Live Data (0xA9) Tests ====================

    @Test
    fun `live data 0xA9 parses voltage`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6505)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.hasNewData, "0xA9 should set hasNewData")
        assertEquals(6505, decoded.newState!!.voltage, "Raw voltage should be 6505")
        assertEquals(65.05, decoded.newState!!.voltageV, 0.01, "Voltage should be 65.05V")
    }

    @Test
    fun `live data 0xA9 parses speed`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6000, speed = 515)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        // Speed is stored directly as the raw value from getInt2R
        assertEquals(515, decoded.newState!!.speed)
        assertEquals(5.15, decoded.newState!!.speedKmh, 0.01, "Speed should be 5.15 km/h")
    }

    @Test
    fun `live data 0xA9 parses current`() {
        decoder.reset()
        // Current is read as: (data[10] and 0xFF) + (data[11] shl 8)
        // In frame coords, data[10] = packet[10], data[11] = packet[11]
        // In our builder, current goes into data[8-9] of the 14-byte payload,
        // which maps to frame bytes 10-11.
        val packet = buildKsLivePacket(voltage = 6000, current = 215)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(215, decoded.newState!!.current, "Current should be 215 (2.15A)")
    }

    @Test
    fun `live data 0xA9 parses temperature`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6000, temperature = 3600)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(3600, decoded.newState!!.temperature, "Raw temperature should be 3600")
        assertEquals(36, decoded.newState!!.temperatureC, "Temperature should be 36C")
    }

    @Test
    fun `live data 0xA9 calculates power from current and voltage`() {
        decoder.reset()
        // Power = ((current / 100.0) * voltage).roundToInt()
        // current=1000 (10.00A), voltage=6000 (60.00V)
        // power = (1000 / 100.0) * 6000 = 10.0 * 6000 = 60000
        val packet = buildKsLivePacket(voltage = 6000, current = 1000)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        val expectedPower = ((1000 / 100.0) * 6000).roundToInt()
        assertEquals(expectedPower, decoded.newState!!.power)
    }

    @Test
    fun `live data 0xA9 calculates battery percentage for 67v wheel`() {
        decoder.reset()
        // Without a model set, uses 67v battery calculation (default)
        // Standard percent: voltage 6000 -> (6000 - 5000) / 16 = 62
        val packet = buildKsLivePacket(voltage = 6000)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(62, decoded.newState!!.batteryLevel, "Battery should be 62% for 67v default")
    }

    @Test
    fun `live data 0xA9 calculates battery percentage for 84v wheel`() {
        decoder.reset()
        // First set model to KS-S18 (84v wheel)
        val namePacket = buildKsNamePacket("KS-S18-0205")
        decoder.decode(namePacket, defaultState, defaultConfig)

        // Standard percent for 84v: voltage 6505 -> (6505 - 6250) / 20 = 12
        val livePacket = buildKsLivePacket(voltage = 6505)
        val result = decoder.decode(livePacket, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(12, decoded.newState!!.batteryLevel, "Battery should be 12% for 84v KS-S18")
    }

    @Test
    fun `live data 0xA9 sets wheelType to KINGSONG`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6000)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(WheelType.KINGSONG, decoded.newState!!.wheelType)
    }

    @Test
    fun `live data 0xA9 sets mode string when indicator is 0xE0`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6000, mode = 2)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals("2", decoded.newState!!.modeStr, "Mode string should reflect the mode byte")
    }

    // ==================== Name/Type (0xBB) Tests ====================

    @Test
    fun `name frame 0xBB sets model and name`() {
        decoder.reset()
        val packet = buildKsNamePacket("KS-S18-0205")
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals("KS-S18-0205", decoded.newState!!.name, "Name should be KS-S18-0205")
        assertEquals("KS-S18", decoded.newState!!.model, "Model should be KS-S18")
    }

    @Test
    fun `name frame 0xBB extracts version from last segment`() {
        decoder.reset()
        val packet = buildKsNamePacket("KS-S18-0205")
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals("2.05", decoded.newState!!.version, "Version should be 2.05")
    }

    @Test
    fun `name frame 0xBB handles multi-segment model names`() {
        decoder.reset()
        val packet = buildKsNamePacket("KS-16X-1234")
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals("KS-16X", decoded.newState!!.model, "Model should be KS-16X")
        assertEquals("12.34", decoded.newState!!.version, "Version should be 12.34")
    }

    @Test
    fun `name frame 0xBB handles name without dashes`() {
        decoder.reset()
        val packet = buildKsNamePacket("ROCKWHEEL")
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals("ROCKWHEEL", decoded.newState!!.model, "Single-segment name should be model")
    }

    // ==================== Serial Number (0xB3) Tests ====================

    @Test
    fun `serial frame 0xB3 triggers REQUEST_ALARMS command`() {
        decoder.reset()
        val packet = buildKsFrame(0xB3)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.commands.isNotEmpty(), "Serial frame should trigger alarm request command")
        assertTrue(
            decoded.commands[0] is WheelCommand.SendBytes,
            "Command should be SendBytes"
        )
    }

    @Test
    fun `serial frame 0xB3 sets serialNumber`() {
        decoder.reset()
        // Build a frame with serial number bytes in payload
        val data = ByteArray(14)
        "SN12345".encodeToByteArray().copyInto(data, 0, 0, 7)
        val packet = buildKsFrame(0xB3, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(
            decoded.newState!!.serialNumber.isNotEmpty(),
            "Serial number should be set"
        )
    }

    // ==================== Distance/Time (0xB9) Tests ====================

    @Test
    fun `distance frame 0xB9 parses fan and charging status`() {
        decoder.reset()
        val packet = buildKsDistancePacket(fanStatus = 1, chargingStatus = 1)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1, decoded.newState!!.fanStatus, "Fan status should be 1")
        assertEquals(1, decoded.newState!!.chargingStatus, "Charging status should be 1")
    }

    @Test
    fun `distance frame 0xB9 parses temperature2`() {
        decoder.reset()
        val packet = buildKsDistancePacket(temperature2 = 4000)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(4000, decoded.newState!!.temperature2, "Temperature2 should be 4000")
        assertEquals(40, decoded.newState!!.temperature2C, "Temperature2 should be 40C")
    }

    // ==================== CPU Load/PWM (0xF5) Tests ====================

    @Test
    fun `cpu load frame 0xF5 parses cpuLoad and output`() {
        decoder.reset()
        // In the decoder: cpuLoad = data[14].toInt(), output = (data[15] and 0xFF) * 100
        // data[14] is frame byte 14, data[15] is frame byte 15
        // In the 14-byte payload (data region starting at byte 2):
        //   payload[12] = frame byte 14 = cpuLoad
        //   payload[13] = frame byte 15 = output raw
        val data = ByteArray(14)
        data[12] = 64  // cpuLoad = 64
        data[13] = 12  // output raw -> output = 12 * 100 = 1200
        val packet = buildKsFrame(0xF5, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(64, decoded.newState!!.cpuLoad, "CPU load should be 64")
        assertEquals(1200, decoded.newState!!.output, "Output should be 1200")
        // calculatedPwm = output / 10000.0 = 1200 / 10000.0 = 0.12
        assertEquals(0.12, decoded.newState!!.calculatedPwm, 0.001)
    }

    // ==================== Speed Limit (0xF6) Tests ====================

    @Test
    fun `speed limit frame 0xF6 parses speed limit`() {
        decoder.reset()
        // speedLimit = getInt2R(data, 2) / 100.0
        // Build with speed limit = 3205 -> 32.05 km/h
        val data = ByteArray(14)
        data[0] = (3205 and 0xFF).toByte()
        data[1] = ((3205 shr 8) and 0xFF).toByte()
        val packet = buildKsFrame(0xF6, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(32.05, decoded.newState!!.speedLimit, 0.01, "Speed limit should be 32.05 km/h")
    }

    // ==================== Max Speed/Alerts (0xA4) Tests ====================

    @Test
    fun `alert frame 0xA4 triggers command response`() {
        decoder.reset()
        val data = ByteArray(14)
        data[2] = 30  // alarm1Speed at data[4] in frame coords = payload[2]
        data[4] = 35  // alarm2Speed
        data[6] = 40  // alarm3Speed
        data[8] = 50  // maxSpeed
        val packet = buildKsFrame(0xA4, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.hasNewData, "0xA4 should set hasNewData")
        assertTrue(
            decoded.commands.isNotEmpty(),
            "0xA4 frame should trigger alarm request response"
        )
    }

    @Test
    fun `alert frame 0xB5 does not trigger command response`() {
        decoder.reset()
        val packet = buildKsFrame(0xB5)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.hasNewData, "0xB5 should set hasNewData")
        assertTrue(
            decoded.commands.isEmpty(),
            "0xB5 frame should NOT trigger alarm request (only 0xA4 does)"
        )
    }

    // ==================== isReady Tests ====================

    @Test
    fun `isReady is false initially`() {
        decoder.reset()
        assertFalse(decoder.isReady(), "Should not be ready initially")
    }

    @Test
    fun `isReady is false after name only`() {
        decoder.reset()
        val namePacket = buildKsNamePacket("KS-16X-0100")
        decoder.decode(namePacket, defaultState, defaultConfig)

        assertFalse(decoder.isReady(), "Should not be ready with name but no voltage")
    }

    @Test
    fun `isReady is false after voltage only`() {
        decoder.reset()
        val livePacket = buildKsLivePacket(voltage = 6000)
        decoder.decode(livePacket, defaultState, defaultConfig)

        assertFalse(decoder.isReady(), "Should not be ready with voltage but no name")
    }

    @Test
    fun `isReady is true after name and voltage`() {
        decoder.reset()
        var state = defaultState

        val namePacket = buildKsNamePacket("KS-16X-0100")
        val nameResult = decoder.decode(namePacket, state, defaultConfig)
        assertTrue(nameResult is DecodeResult.Success)
        state = (nameResult as DecodeResult.Success).data.newState!!

        val livePacket = buildKsLivePacket(voltage = 6000)
        val liveResult = decoder.decode(livePacket, state, defaultConfig)
        assertTrue(liveResult is DecodeResult.Success)

        assertTrue(decoder.isReady(), "Should be ready after name + voltage")
    }

    @Test
    fun `isReady requires positive voltage`() {
        decoder.reset()
        var state = defaultState

        val namePacket = buildKsNamePacket("KS-16X-0100")
        val nameResult = decoder.decode(namePacket, state, defaultConfig)
        assertTrue(nameResult is DecodeResult.Success)
        state = (nameResult as DecodeResult.Success).data.newState!!

        // voltage = 0 should not set hasReceivedVoltage
        val livePacket = buildKsLivePacket(voltage = 0)
        decoder.decode(livePacket, state, defaultConfig)

        assertFalse(decoder.isReady(), "Zero voltage should not satisfy isReady")
    }

    // ==================== Reset Tests ====================

    @Test
    fun `reset clears ready state`() {
        decoder.reset()
        var state = defaultState

        // Get to ready state
        val namePacket = buildKsNamePacket("KS-16X-0100")
        val nameResult = decoder.decode(namePacket, state, defaultConfig)
        assertTrue(nameResult is DecodeResult.Success)
        state = (nameResult as DecodeResult.Success).data.newState!!

        val livePacket = buildKsLivePacket(voltage = 6000)
        decoder.decode(livePacket, state, defaultConfig)
        assertTrue(decoder.isReady(), "Should be ready before reset")

        decoder.reset()
        assertFalse(decoder.isReady(), "Should not be ready after reset")
    }

    @Test
    fun `reset allows re-initialization`() {
        decoder.reset()
        var state = defaultState

        // First connection
        val namePacket = buildKsNamePacket("KS-S18-0205")
        val nameResult = decoder.decode(namePacket, state, defaultConfig)
        assertTrue(nameResult is DecodeResult.Success)
        state = (nameResult as DecodeResult.Success).data.newState!!
        val livePacket = buildKsLivePacket(voltage = 6505)
        decoder.decode(livePacket, state, defaultConfig)
        assertTrue(decoder.isReady())

        // Reset for new connection
        decoder.reset()
        state = defaultState

        // Second connection with different wheel (KS-14D is a 67v wheel)
        val namePacket2 = buildKsNamePacket("KS-14D-0100")
        val nameResult2 = decoder.decode(namePacket2, state, defaultConfig)
        assertTrue(nameResult2 is DecodeResult.Success)
        state = (nameResult2 as DecodeResult.Success).data.newState!!
        assertEquals("KS-14D", state.model, "Model should reflect new wheel after reset")

        val livePacket2 = buildKsLivePacket(voltage = 6000)
        val liveResult2 = decoder.decode(livePacket2, state, defaultConfig)
        assertTrue(liveResult2 is DecodeResult.Success)
        val decoded2 = (liveResult2 as DecodeResult.Success).data
        assertTrue(decoder.isReady())
        // 67v battery: (6000 - 5000) / 16 = 62
        assertEquals(62, decoded2.newState!!.batteryLevel, "Battery should use 67v calc for KS-14D")
    }

    // ==================== buildCommand Tests ====================

    @Test
    fun `buildCommand Beep returns non-empty list`() {
        val commands = decoder.buildCommand(WheelCommand.Beep)
        assertTrue(commands.isNotEmpty(), "Beep should return at least one command")
        assertTrue(commands[0] is WheelCommand.SendBytes)
    }

    @Test
    fun `buildCommand Beep has correct frame structure`() {
        val commands = decoder.buildCommand(WheelCommand.Beep)
        val sendBytes = commands[0] as WheelCommand.SendBytes
        val frame = sendBytes.data
        assertEquals(20, frame.size, "Frame should be 20 bytes")
        assertEquals(0xAA.toByte(), frame[0], "Byte 0 should be AA")
        assertEquals(0x55.toByte(), frame[1], "Byte 1 should be 55")
        // Beep command type = 0x88
        assertEquals(0x88.toByte(), frame[16], "Frame type should be 0x88 (beep)")
        assertEquals(0x14.toByte(), frame[17])
        assertEquals(0x5A.toByte(), frame[18])
        assertEquals(0x5A.toByte(), frame[19])
    }

    @Test
    fun `buildCommand Calibrate returns non-empty list`() {
        val commands = decoder.buildCommand(WheelCommand.Calibrate)
        assertTrue(commands.isNotEmpty())
        assertTrue(commands[0] is WheelCommand.SendBytes)
    }

    @Test
    fun `buildCommand PowerOff returns non-empty list`() {
        val commands = decoder.buildCommand(WheelCommand.PowerOff)
        assertTrue(commands.isNotEmpty())
        assertTrue(commands[0] is WheelCommand.SendBytes)
    }

    @Test
    fun `buildCommand SetLight on returns light mode 1`() {
        val commands = decoder.buildCommand(WheelCommand.SetLight(true))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        // SetLight(true) delegates to SetLightMode(1), mode 1 -> 0x13 at data[2]
        assertEquals(0x13.toByte(), frame[2], "Light on should send 0x13")
    }

    @Test
    fun `buildCommand SetLight off returns light mode 0`() {
        val commands = decoder.buildCommand(WheelCommand.SetLight(false))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        // SetLight(false) delegates to SetLightMode(0), mode 0 -> 0x12 at data[2]
        assertEquals(0x12.toByte(), frame[2], "Light off should send 0x12")
    }

    @Test
    fun `buildCommand SetPedalsMode encodes mode and marker`() {
        val commands = decoder.buildCommand(WheelCommand.SetPedalsMode(1))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(1.toByte(), frame[2], "Pedals mode should be at data[2]")
        assertEquals(0xE0.toByte(), frame[3], "Marker byte should be 0xE0 at data[3]")
    }

    @Test
    fun `buildCommand SetKingsongAlarms encodes all alarm speeds`() {
        val commands = decoder.buildCommand(
            WheelCommand.SetKingsongAlarms(
                alarm1Speed = 20,
                alarm2Speed = 30,
                alarm3Speed = 40,
                maxSpeed = 50
            )
        )
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(20.toByte(), frame[2], "Alarm1 speed should be at data[2]")
        assertEquals(30.toByte(), frame[4], "Alarm2 speed should be at data[4]")
        assertEquals(40.toByte(), frame[6], "Alarm3 speed should be at data[6]")
        assertEquals(50.toByte(), frame[8], "Max speed should be at data[8]")
    }

    @Test
    fun `buildCommand RequestAlarmSettings returns non-empty list`() {
        val commands = decoder.buildCommand(WheelCommand.RequestAlarmSettings)
        assertTrue(commands.isNotEmpty())
    }

    @Test
    fun `buildCommand RequestBmsData returns correct frame type`() {
        // bmsNum=1, dataType=0 (serial) -> frame type 0xE1
        val commands = decoder.buildCommand(WheelCommand.RequestBmsData(bmsNum = 1, dataType = 0))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0xE1.toByte(), frame[16], "BMS1 serial request should use type 0xE1")
    }

    @Test
    fun `buildCommand RequestBmsData bms2 firmware returns correct frame type`() {
        // bmsNum=2, dataType=2 (firmware) -> frame type 0xE5 + 1 = 0xE6
        val commands = decoder.buildCommand(WheelCommand.RequestBmsData(bmsNum = 2, dataType = 2))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0xE6.toByte(), frame[16], "BMS2 firmware request should use type 0xE6")
    }

    @Test
    fun `buildCommand unsupported returns empty list`() {
        val commands = decoder.buildCommand(WheelCommand.SetMaxSpeed(30))
        assertTrue(commands.isEmpty(), "Unsupported command should return empty list")
    }

    // ==================== getInitCommands Tests ====================

    @Test
    fun `getInitCommands returns name serial alarm and settings requests`() {
        val commands = decoder.getInitCommands()
        assertEquals(5, commands.size, "Should return 5 init commands")
        assertTrue(commands[0] is WheelCommand.SendBytes, "First should be SendBytes (name request)")
        assertTrue(commands[1] is WheelCommand.SendDelayed, "Second should be SendDelayed (serial)")
        assertTrue(commands[2] is WheelCommand.SendDelayed, "Third should be SendDelayed (alarms)")
        assertTrue(commands[3] is WheelCommand.SendDelayed, "Fourth should be SendDelayed (light status)")
        assertTrue(commands[4] is WheelCommand.SendDelayed, "Fifth should be SendDelayed (lift sensor)")
    }

    // ==================== BMS Data (0xF1) Tests ====================

    @Test
    fun `BMS pNum 0x00 sets voltage and current`() {
        decoder.reset()
        // BMS voltage=8400 -> 84.00V, current=500 -> 5.00A
        val packet = buildKsBmsInfoFrame(voltage = 8400, current = 500)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        val bms = decoded.newState!!.bms1
        assertNotNull(bms, "bms1 snapshot should be present")
        assertEquals(84.0, bms.voltage, 0.01, "BMS voltage should be 84.00V")
        assertEquals(5.0, bms.current, 0.01, "BMS current should be 5.00A")
    }

    @Test
    fun `BMS pNum 0x00 sets capacity and cycles`() {
        decoder.reset()
        // remCap=200 -> 200*10=2000, factoryCap=300 -> 300*10=3000
        val packet = buildKsBmsInfoFrame(
            voltage = 8400,
            current = 500,
            remCap = 200,
            factoryCap = 300,
            fullCycles = 50
        )
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        val bms = decoded.newState!!.bms1!!
        assertEquals(2000, bms.remCap, "Remaining capacity should be 2000 (200*10)")
        assertEquals(3000, bms.factoryCap, "Factory capacity should be 3000 (300*10)")
        assertEquals(50, bms.fullCycles, "Full cycles should be 50")
        // remPerc = (remCap / (factoryCap / 100.0)).roundToInt() = (2000 / 30.0).roundToInt() = 67
        assertEquals(67, bms.remPerc, "Remaining percent should be 67%")
    }

    @Test
    fun `BMS pNum 0x01 sets temperatures`() {
        decoder.reset()
        // Temperature formula: (raw - 2730) / 10.0
        // raw = 2980 -> (2980 - 2730) / 10.0 = 25.0C
        val data = ByteArray(14)
        val raw = 2980
        // Fill 7 temperature slots (each LE 2 bytes)
        for (i in 0 until 7) {
            val v = raw + i * 10  // slightly different temps
            data[i * 2] = (v and 0xFF).toByte()
            data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val packet = buildKsFrame(0xF1, data, byte17 = 0x01)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        val bms = decoded.newState!!.bms1!!
        assertEquals(25.0, bms.temp1, 0.01, "Temp1 should be 25.0C")
        assertEquals(26.0, bms.temp2, 0.01, "Temp2 should be 26.0C")
    }

    @Test
    fun `BMS pNum 0x02 through 0x05 accumulate cell voltages`() {
        decoder.reset()
        var state = defaultState

        // pNum 0x02: cells 0-6
        val cells02 = listOf(4200, 4190, 4180, 4170, 4160, 4150, 4140)
        val packet02 = buildKsBmsCellFrame(0x02, cells02)
        val result02 = decoder.decode(packet02, state, defaultConfig)
        assertTrue(result02 is DecodeResult.Success)
        state = (result02 as DecodeResult.Success).data.newState!!

        // pNum 0x03: cells 7-13
        val cells03 = listOf(4130, 4120, 4110, 4100, 4090, 4080, 4070)
        val packet03 = buildKsBmsCellFrame(0x03, cells03)
        val result03 = decoder.decode(packet03, state, defaultConfig)
        assertTrue(result03 is DecodeResult.Success)
        state = (result03 as DecodeResult.Success).data.newState!!

        // pNum 0x04: cells 14-20
        val cells04 = listOf(4060, 4050, 4040, 4030, 4020, 4010, 4000)
        val packet04 = buildKsBmsCellFrame(0x04, cells04)
        val result04 = decoder.decode(packet04, state, defaultConfig)
        assertTrue(result04 is DecodeResult.Success)
        state = (result04 as DecodeResult.Success).data.newState!!

        // pNum 0x05: cells 21-27
        val cells05 = listOf(3990, 3980, 3970, 3960, 3950, 3940, 3930)
        val packet05 = buildKsBmsCellFrame(0x05, cells05)
        val result05 = decoder.decode(packet05, state, defaultConfig)
        assertTrue(result05 is DecodeResult.Success)
        state = (result05 as DecodeResult.Success).data.newState!!

        // Verify cells accumulated across frames
        val bms = state.bms1!!
        assertEquals(4.200, bms.cells[0], 0.001, "Cell 0 should be 4.200V")
        assertEquals(4.190, bms.cells[1], 0.001, "Cell 1 should be 4.190V")
        assertEquals(4.130, bms.cells[7], 0.001, "Cell 7 should be 4.130V")
        assertEquals(4.060, bms.cells[14], 0.001, "Cell 14 should be 4.060V")
        assertEquals(3.990, bms.cells[21], 0.001, "Cell 21 should be 3.990V")
    }

    @Test
    fun `BMS pNum 0x06 sets last cells and triggers cell stats`() {
        decoder.reset()
        var state = defaultState

        // First set model to get correct cell count (default = 16 cells for unknown model)
        // Use all cells with known voltages across pNum 0x02 and 0x03
        val cells02 = listOf(4200, 4100, 4150, 4180, 4120, 4130, 4170)
        val packet02 = buildKsBmsCellFrame(0x02, cells02)
        state = (decoder.decode(packet02, state, defaultConfig) as DecodeResult.Success).data.newState!!

        val cells03 = listOf(4160, 4140, 4110, 4190, 4090, 4050, 4080)
        val packet03 = buildKsBmsCellFrame(0x03, cells03)
        state = (decoder.decode(packet03, state, defaultConfig) as DecodeResult.Success).data.newState!!

        // pNum 0x06: last 2 cells (cells[28] and cells[29]) plus tempMosEnv
        // For a 16-cell wheel, cells 28-29 are beyond range but still written
        val data06 = ByteArray(14)
        val cell28 = 4060
        val cell29 = 4070
        data06[0] = (cell28 and 0xFF).toByte()
        data06[1] = ((cell28 shr 8) and 0xFF).toByte()
        data06[2] = (cell29 and 0xFF).toByte()
        data06[3] = ((cell29 shr 8) and 0xFF).toByte()
        // tempMosEnv at data[8-9] (offset 10 in frame) -> (raw - 2730) / 10.0
        val tempRaw = 2980
        data06[8] = (tempRaw and 0xFF).toByte()
        data06[9] = ((tempRaw shr 8) and 0xFF).toByte()
        val packet06 = buildKsFrame(0xF1, data06, byte17 = 0x06)
        state = (decoder.decode(packet06, state, defaultConfig) as DecodeResult.Success).data.newState!!

        val bms = state.bms1!!
        // Cell stats computed over getCellsForWheel() = 16 cells (default model)
        // Min should be 4.050 (cell 12 = index 5 in cells03), max should be 4.200 (cell 0)
        assertEquals(4.050, bms.minCell, 0.001, "Min cell should be 4.050V")
        assertEquals(4.200, bms.maxCell, 0.001, "Max cell should be 4.200V")
        assertEquals(0.150, bms.cellDiff, 0.001, "Cell diff should be 0.150V")
        // minCellNum and maxCellNum are 1-indexed
        assertEquals(13, bms.minCellNum, "Min cell number should be 13 (1-indexed)")
        assertEquals(1, bms.maxCellNum, "Max cell number should be 1 (1-indexed)")
    }

    @Test
    fun `BMS data is associated with correct bms number`() {
        decoder.reset()
        var state = defaultState

        // 0xF1 -> bms1
        val bms1Packet = buildKsBmsInfoFrame(voltage = 8400, current = 500)
        state = (decoder.decode(bms1Packet, state, defaultConfig) as DecodeResult.Success).data.newState!!
        assertEquals(84.0, state.bms1!!.voltage, 0.01, "BMS1 voltage should be 84.00V")

        // 0xF2 -> bms2
        val data = ByteArray(14)
        val bms2Voltage = 8200
        data[0] = (bms2Voltage and 0xFF).toByte()
        data[1] = ((bms2Voltage shr 8) and 0xFF).toByte()
        val bms2Packet = buildKsFrame(0xF2, data, byte17 = 0x00)
        state = (decoder.decode(bms2Packet, state, defaultConfig) as DecodeResult.Success).data.newState!!

        assertEquals(82.0, state.bms2!!.voltage, 0.01, "BMS2 voltage should be 82.00V")
        // BMS1 should still be intact
        assertEquals(84.0, state.bms1!!.voltage, 0.01, "BMS1 should still be 84.00V")
    }

    // ==================== BMS Serial/Firmware Tests ====================

    @Test
    fun `BMS serial frame 0xE1 returns Unhandled but stores serial`() {
        decoder.reset()
        // BMS serial frames (0xE1/0xE2) return null state from processBmsSerial
        // but still set the serial in the SmartBms object
        val data = ByteArray(14)
        "BMS-SN123".encodeToByteArray().copyInto(data, 0, 0, 9)
        val packet = buildKsFrame(0xE1, data)

        // processBmsSerial returns null, so decode returns Unhandled (no state update)
        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Unhandled, "BMS serial frame should return Unhandled (no state update path)")
    }

    @Test
    fun `BMS firmware frame 0xE5 returns Unhandled`() {
        decoder.reset()
        val packet = buildKsFrame(0xE5)
        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Unhandled, "BMS firmware frame should return Unhandled")
    }

    // ==================== Unknown Frame Type Tests ====================

    @Test
    fun `unknown frame type returns Unhandled`() {
        decoder.reset()
        val packet = buildKsFrame(0xFF)  // 0xFF is not a known frame type
        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Unhandled, "Unknown frame type should return Unhandled")
    }

    // ==================== Multi-Frame Integration Test ====================

    @Test
    fun `full initialization sequence produces correct state`() {
        // Simulate a real connection: name -> serial -> live data
        decoder.reset()
        var state = defaultState

        // 1. Name packet
        val namePacket = buildKsNamePacket("KS-S18-0205")
        val nameResult = decoder.decode(namePacket, state, defaultConfig)
        assertTrue(nameResult is DecodeResult.Success)
        val nameDecoded = (nameResult as DecodeResult.Success).data
        state = nameDecoded.newState!!
        assertEquals("KS-S18", state.model)
        assertEquals("2.05", state.version)
        assertFalse(decoder.isReady())

        // 2. Serial packet (triggers alarm request)
        val serialPacket = buildKsFrame(0xB3)
        val serialResult = decoder.decode(serialPacket, state, defaultConfig)
        assertTrue(serialResult is DecodeResult.Success)
        val serialDecoded = (serialResult as DecodeResult.Success).data
        state = serialDecoded.newState!!
        assertTrue(serialDecoded.commands.isNotEmpty(), "Serial should trigger alarm request")
        assertFalse(decoder.isReady(), "Still not ready without voltage")

        // 3. Live data packet
        val livePacket = buildKsLivePacket(voltage = 6505, speed = 515, current = 215, temperature = 1300)
        val liveResult = decoder.decode(livePacket, state, defaultConfig)
        assertTrue(liveResult is DecodeResult.Success)
        val liveDecoded = (liveResult as DecodeResult.Success).data
        state = liveDecoded.newState!!

        assertTrue(decoder.isReady(), "Should be ready after name + voltage")
        assertEquals(6505, state.voltage)
        assertEquals(515, state.speed)
        assertEquals(215, state.current)
        assertEquals(1300, state.temperature)
        assertEquals(13, state.temperatureC)
        assertEquals(WheelType.KINGSONG, state.wheelType)
        assertEquals("KS-S18", state.model)
        // 84v battery: (6505 - 6250) / 20 = 12
        assertEquals(12, state.batteryLevel)
    }

    // ==================== wheelType Property ====================

    @Test
    fun `wheelType is KINGSONG`() {
        assertEquals(WheelType.KINGSONG, decoder.wheelType)
    }

    // ==================== Hardware Faults (0xF5) Tests ====================

    @Test
    fun `cpu load frame 0xF5 no faults sets hwFaults to 0`() {
        decoder.reset()
        // All diagnostic bytes (2-9) are zero
        val data = ByteArray(14)
        data[12] = 50 // cpuLoad
        data[13] = 10 // output raw
        val packet = buildKsFrame(0xF5, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(0, decoded.newState!!.hwFaults, "No faults should give hwFaults=0")
        assertEquals("", decoded.newState!!.alert, "No faults should give empty alert")
        assertEquals(50, decoded.newState!!.cpuLoad, "CPU load should still be parsed")
    }

    @Test
    fun `cpu load frame 0xF5 current amplitude fault`() {
        decoder.reset()
        val data = ByteArray(14)
        // Bytes 2-3 (data[0-1]): current amplitude — non-zero = fault
        data[0] = 0x01
        val packet = buildKsFrame(0xF5, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(KingsongDecoder.HwFault.CURRENT_AMPLITUDE, decoded.newState!!.hwFaults)
        assertTrue(decoded.newState!!.alert.contains("Current amplitude fault"))
    }

    @Test
    fun `cpu load frame 0xF5 temperature fault`() {
        decoder.reset()
        val data = ByteArray(14)
        // Bytes 4-5 (data[2-3]): temperature — non-zero = fault
        data[2] = 0x01
        val packet = buildKsFrame(0xF5, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(KingsongDecoder.HwFault.TEMPERATURE, decoded.newState!!.hwFaults)
        assertTrue(decoded.newState!!.alert.contains("Temperature fault"))
    }

    @Test
    fun `cpu load frame 0xF5 motor phase short fault`() {
        decoder.reset()
        val data = ByteArray(14)
        // Byte 6 (data[4]): motor phase short
        data[4] = 0x01
        val packet = buildKsFrame(0xF5, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(KingsongDecoder.HwFault.MOTOR_PHASE_SHORT, decoded.newState!!.hwFaults)
        assertTrue(decoded.newState!!.alert.contains("Motor phase short"))
    }

    @Test
    fun `cpu load frame 0xF5 gyroscope error`() {
        decoder.reset()
        val data = ByteArray(14)
        // Byte 7 (data[5]): gyroscope error
        data[5] = 0x01
        val packet = buildKsFrame(0xF5, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(KingsongDecoder.HwFault.GYROSCOPE_ERROR, decoded.newState!!.hwFaults)
        assertTrue(decoded.newState!!.alert.contains("Gyroscope error"))
    }

    @Test
    fun `cpu load frame 0xF5 motor hall error`() {
        decoder.reset()
        val data = ByteArray(14)
        // Byte 8 (data[6]): motor hall error
        data[6] = 0x01
        val packet = buildKsFrame(0xF5, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(KingsongDecoder.HwFault.MOTOR_HALL_ERROR, decoded.newState!!.hwFaults)
        assertTrue(decoded.newState!!.alert.contains("Motor hall error"))
    }

    @Test
    fun `cpu load frame 0xF5 sn board error`() {
        decoder.reset()
        val data = ByteArray(14)
        // Byte 9 (data[7]): SN board error
        data[7] = 0x01
        val packet = buildKsFrame(0xF5, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(KingsongDecoder.HwFault.SN_BOARD_ERROR, decoded.newState!!.hwFaults)
        assertTrue(decoded.newState!!.alert.contains("SN board error"))
    }

    @Test
    fun `cpu load frame 0xF5 multiple faults`() {
        decoder.reset()
        val data = ByteArray(14)
        // Current amplitude + gyroscope
        data[0] = 0x01  // bytes 2-3 non-zero
        data[5] = 0x01  // byte 7 non-zero
        data[12] = 75   // cpuLoad
        val packet = buildKsFrame(0xF5, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        val expected = KingsongDecoder.HwFault.CURRENT_AMPLITUDE or KingsongDecoder.HwFault.GYROSCOPE_ERROR
        assertEquals(expected, decoded.newState!!.hwFaults, "Should have both faults set")
        assertTrue(decoded.newState!!.alert.contains("Current amplitude fault"))
        assertTrue(decoded.newState!!.alert.contains("Gyroscope error"))
        assertEquals(75, decoded.newState!!.cpuLoad, "cpuLoad should be preserved")
    }

    // ==================== Speed Limit / BMS SOC / Energy / Fault Code (0xF6) Tests ====================

    @Test
    fun `speed limit frame 0xF6 parses BMS SOC with off-by-1 correction`() {
        decoder.reset()
        val data = ByteArray(14)
        // Speed limit at data[0-1]
        data[0] = (3000 and 0xFF).toByte()
        data[1] = ((3000 shr 8) and 0xFF).toByte()
        // BMS SOC at byte 4 (data[2]) — raw 86 → 85% (raw 1-101 maps to 0-100)
        data[2] = 86.toByte()
        val packet = buildKsFrame(0xF6, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(85, decoded.newState!!.bmsSoc, "BMS SOC raw 86 should be 85%")
        assertEquals(30.0, decoded.newState!!.speedLimit, 0.01, "Speed limit should still be parsed")
    }

    @Test
    fun `speed limit frame 0xF6 BMS SOC raw 1 maps to 0`() {
        decoder.reset()
        val data = ByteArray(14)
        data[2] = 1.toByte() // raw 1 → 0%
        val packet = buildKsFrame(0xF6, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(0, decoded.newState!!.bmsSoc, "BMS SOC raw 1 should be 0%")
    }

    @Test
    fun `speed limit frame 0xF6 BMS SOC raw 101 maps to 100`() {
        decoder.reset()
        val data = ByteArray(14)
        data[2] = 101.toByte() // raw 101 → 100%
        val packet = buildKsFrame(0xF6, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(100, decoded.newState!!.bmsSoc, "BMS SOC raw 101 should be 100%")
    }

    @Test
    fun `speed limit frame 0xF6 BMS SOC raw 0 is unknown`() {
        decoder.reset()
        val data = ByteArray(14)
        data[2] = 0.toByte() // raw 0 → unknown (-1)
        val packet = buildKsFrame(0xF6, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(-1, decoded.newState!!.bmsSoc, "BMS SOC raw 0 should be unknown (-1)")
    }

    @Test
    fun `speed limit frame 0xF6 BMS SOC out of range is unknown`() {
        decoder.reset()
        val data = ByteArray(14)
        data[2] = 120.toByte() // > 101 → unknown (-1)
        val packet = buildKsFrame(0xF6, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(-1, decoded.newState!!.bmsSoc, "BMS SOC > 101 should be unknown (-1)")
    }

    @Test
    fun `speed limit frame 0xF6 parses energy Wh`() {
        decoder.reset()
        val data = ByteArray(14)
        // Energy at bytes 6-9 (data[4-7]), LE 32-bit
        val energy = 12345
        data[4] = (energy and 0xFF).toByte()
        data[5] = ((energy shr 8) and 0xFF).toByte()
        data[6] = ((energy shr 16) and 0xFF).toByte()
        data[7] = ((energy shr 24) and 0xFF).toByte()
        val packet = buildKsFrame(0xF6, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(12345L, decoded.newState!!.totalEnergyWh, "Energy should be 12345 Wh")
    }

    @Test
    fun `speed limit frame 0xF6 parses fault code`() {
        decoder.reset()
        val data = ByteArray(14)
        // Fault code at bytes 14-15 (data[12-13]) via getInt2R (LE)
        val faultCode = 42
        data[12] = (faultCode and 0xFF).toByte()
        data[13] = ((faultCode shr 8) and 0xFF).toByte()
        val packet = buildKsFrame(0xF6, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(42, decoded.newState!!.faultCode, "Fault code should be 42")
        assertEquals("Fault code: 42", decoded.newState!!.error, "Error string should contain fault code")
    }

    @Test
    fun `speed limit frame 0xF6 zero fault code clears error`() {
        decoder.reset()
        val data = ByteArray(14)
        // Fault code bytes are zero (default)
        val packet = buildKsFrame(0xF6, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(0, decoded.newState!!.faultCode, "Fault code should be 0")
        assertEquals("", decoded.newState!!.error, "Error should be empty for zero fault code")
    }

    // ==================== 16-bit Alarm Speed (0xA4/0xB5) Tests ====================

    @Test
    fun `alert frame 0xA4 reads 16-bit alarm speeds and surfaces in state`() {
        decoder.reset()
        val data = ByteArray(14)
        // alarm1Speed at frame bytes 4-5 (data[2-3]) — LE
        val alarm1 = 300  // > 255, requires 16-bit
        data[2] = (alarm1 and 0xFF).toByte()
        data[3] = ((alarm1 shr 8) and 0xFF).toByte()
        // alarm2Speed at frame bytes 6-7 (data[4-5])
        val alarm2 = 350
        data[4] = (alarm2 and 0xFF).toByte()
        data[5] = ((alarm2 shr 8) and 0xFF).toByte()
        // alarm3Speed at frame bytes 8-9 (data[6-7])
        val alarm3 = 400
        data[6] = (alarm3 and 0xFF).toByte()
        data[7] = ((alarm3 shr 8) and 0xFF).toByte()
        // maxSpeed at frame bytes 10-11 (data[8-9])
        val maxSpd = 500
        data[8] = (maxSpd and 0xFF).toByte()
        data[9] = ((maxSpd shr 8) and 0xFF).toByte()
        val packet = buildKsFrame(0xA4, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.commands.isNotEmpty(), "0xA4 should trigger alarm request")
        assertEquals(300, decoded.newState!!.ksAlarm1Speed, "Alarm 1 speed should be surfaced")
        assertEquals(350, decoded.newState!!.ksAlarm2Speed, "Alarm 2 speed should be surfaced")
        assertEquals(400, decoded.newState!!.ksAlarm3Speed, "Alarm 3 speed should be surfaced")
        assertEquals(500, decoded.newState!!.ksTiltbackSpeed, "Tiltback speed should be surfaced")
    }

    @Test
    fun `alert frame 0xB5 reads 16-bit alarm speeds backward compatible`() {
        decoder.reset()
        val data = ByteArray(14)
        // 8-bit compatible values — high byte is 0
        data[2] = 30  // alarm1
        data[4] = 35  // alarm2
        data[6] = 40  // alarm3
        data[8] = 50  // maxSpeed
        val packet = buildKsFrame(0xB5, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.commands.isEmpty(), "0xB5 should NOT trigger command")
        assertEquals(30, decoded.newState!!.ksAlarm1Speed, "Alarm 1 should be 30")
        assertEquals(35, decoded.newState!!.ksAlarm2Speed, "Alarm 2 should be 35")
        assertEquals(40, decoded.newState!!.ksAlarm3Speed, "Alarm 3 should be 40")
        assertEquals(50, decoded.newState!!.ksTiltbackSpeed, "Tiltback should be 50")
    }

    // ==================== Keep-Alive Tests ====================

    @Test
    fun `keepAliveIntervalMs is 2500`() {
        assertEquals(2500L, decoder.keepAliveIntervalMs)
    }

    @Test
    fun `getKeepAliveCommand returns valid frame`() {
        val command = decoder.getKeepAliveCommand()
        assertNotNull(command)
        assertTrue(command is WheelCommand.SendBytes)
        val frame = (command as WheelCommand.SendBytes).data
        assertEquals(20, frame.size)
        assertEquals(0xAA.toByte(), frame[0])
        assertEquals(0x55.toByte(), frame[1])
        assertEquals(0x5E.toByte(), frame[16], "Keep-alive frame type should be 0x5E")
        assertEquals(0x14.toByte(), frame[17])
        assertEquals(0x5A.toByte(), frame[18])
        assertEquals(0x5A.toByte(), frame[19])
    }

    // ==================== Lock/Unlock Tests ====================

    @Test
    fun `buildCommand SetLock returns empty list for both lock and unlock`() {
        // Lock requires password protocol (0x41/0x42) — not implemented yet
        val lockCmds = decoder.buildCommand(WheelCommand.SetLock(locked = true))
        assertTrue(lockCmds.isEmpty(), "SetLock(true) should return empty")
        val unlockCmds = decoder.buildCommand(WheelCommand.SetLock(locked = false))
        assertTrue(unlockCmds.isEmpty(), "SetLock(false) should return empty")
    }

    @Test
    fun `lock status frame 0x5F locked`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 1 // byte 2 in frame = data[0] in payload = locked
        val packet = buildKsFrame(0x5F, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1, decoded.newState!!.lockState, "Lock status 1 should set lockState=1")
    }

    @Test
    fun `lock status frame 0x5F locked value 2`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 2 // byte 2 = 2 also means locked
        val packet = buildKsFrame(0x5F, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1, decoded.newState!!.lockState, "Lock status 2 should also set lockState=1")
    }

    @Test
    fun `lock status frame 0x5F unlocked`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 0 // byte 2 = 0 = unlocked
        val packet = buildKsFrame(0x5F, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(0, decoded.newState!!.lockState, "Lock status 0 should set lockState=0")
    }

    @Test
    fun `lock result frame 0xB1 success`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 1 // cmd=1 at byte 2
        data[1] = 0 // result=0 at byte 3
        val packet = buildKsFrame(0xB1, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1, decoded.newState!!.lockState, "Lock result cmd=1,result=0 should set lockState=1")
    }

    @Test
    fun `lock result frame 0xB1 unlock`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 0 // cmd=0 at byte 2 (not cmd=1)
        data[1] = 0 // result=0 at byte 3
        val packet = buildKsFrame(0xB1, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(0, decoded.newState!!.lockState, "Lock result cmd!=1 should set lockState=0")
    }

    @Test
    fun `LOCK is not in supported commands`() {
        assertFalse(
            SettingsCommandId.LOCK in KingsongDecoder.SUPPORTED_COMMANDS,
            "LOCK should not be in SUPPORTED_COMMANDS (requires password protocol)"
        )
    }

    // ==================== Bug Fix Tests ====================

    @Test
    fun `light mode command does not clobber voice`() {
        val commands = decoder.buildCommand(WheelCommand.SetLightMode(1))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x00.toByte(), frame[3], "Byte[3] should be 0 to preserve voice on")
    }

    @Test
    fun `SetLock returns empty list`() {
        val commands = decoder.buildCommand(WheelCommand.SetLock(true))
        assertTrue(commands.isEmpty(), "SetLock should return empty (needs password protocol)")
    }

    // ==================== New 0xB9 Fields Tests ====================

    @Test
    fun `distance frame 0xB9 parses rideTime`() {
        decoder.reset()
        val data = ByteArray(14)
        // rideTime at bytes 6-7 → data[4-5] in 14-byte payload
        val rideTime = 3600  // 1 hour in seconds
        data[4] = (rideTime and 0xFF).toByte()
        data[5] = ((rideTime shr 8) and 0xFF).toByte()
        val packet = buildKsFrame(0xB9, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(3600, decoded.newState!!.rideTime, "Ride time should be 3600 seconds")
    }

    @Test
    fun `distance frame 0xB9 parses topSpeed`() {
        decoder.reset()
        val data = ByteArray(14)
        // topSpeed at bytes 8-9 → data[6-7] in 14-byte payload
        val topSpeed = 4200  // 42.00 km/h
        data[6] = (topSpeed and 0xFF).toByte()
        data[7] = ((topSpeed shr 8) and 0xFF).toByte()
        val packet = buildKsFrame(0xB9, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(4200, decoded.newState!!.topSpeed, "Top speed should be 4200 (42.00 km/h)")
    }

    @Test
    fun `distance frame 0xB9 parses lightMode`() {
        decoder.reset()
        val data = ByteArray(14)
        // lightMode at byte 10 → data[8] in 14-byte payload
        data[8] = 0x14.toByte()  // 0x14 = auto → normalized to 2
        val packet = buildKsFrame(0xB9, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(2, decoded.newState!!.lightMode, "Light mode 0x14 should normalize to 2 (auto)")
    }

    @Test
    fun `distance frame 0xB9 parses lightMode off`() {
        decoder.reset()
        val data = ByteArray(14)
        data[8] = 0x12.toByte()  // 0x12 = off → normalized to 0
        val packet = buildKsFrame(0xB9, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(0, decoded.newState!!.lightMode, "Light mode 0x12 should normalize to 0 (off)")
    }

    @Test
    fun `distance frame 0xB9 parses mute`() {
        decoder.reset()
        val data = ByteArray(14)
        // voiceOff at byte 11 → data[9] in 14-byte payload
        data[9] = 1.toByte()  // 1 = voice off = muted
        val packet = buildKsFrame(0xB9, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.newState!!.mute, "Voice off flag 1 should set mute=true")
    }

    @Test
    fun `distance frame 0xB9 parses not muted`() {
        decoder.reset()
        val data = ByteArray(14)
        data[9] = 0.toByte()  // 0 = voice on = not muted
        val packet = buildKsFrame(0xB9, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertFalse(decoded.newState!!.mute, "Voice off flag 0 should set mute=false")
    }

    // ==================== New 0xF6 Fields Tests ====================

    @Test
    fun `speed limit frame 0xF6 parses totalOnTime`() {
        decoder.reset()
        val data = ByteArray(14)
        // totalOnTime at bytes 12-13 → data[10-11] in 14-byte payload
        val onTime = 12345  // within signed 16-bit range
        data[10] = (onTime and 0xFF).toByte()
        data[11] = ((onTime shr 8) and 0xFF).toByte()
        val packet = buildKsFrame(0xF6, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(12345, decoded.newState!!.totalOnTime, "Total on time should be 12345 seconds")
    }

    // ==================== New Frame Type Tests ====================

    @Test
    fun `ride mode confirm 0xA2 success updates pedalsMode`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 1  // success flag at byte 2
        data[1] = 0  // success flag at byte 3
        data[2] = 2  // new mode at byte 4 (2=study/soft)
        val packet = buildKsFrame(0xA2, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(2, decoded.newState!!.pedalsMode, "Pedals mode should be updated to 2 on success")
    }

    @Test
    fun `ride mode confirm 0xA2 failure does not update`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 0  // failure
        data[1] = 0
        data[2] = 2
        val packet = buildKsFrame(0xA2, data)

        val stateWithMode = defaultState.copy(pedalsMode = 1)
        val result = decoder.decode(packet, stateWithMode, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1, decoded.newState!!.pedalsMode, "Pedals mode should not change on failure")
    }

    @Test
    fun `battery temp 0xC9 parses temperature and charging`() {
        decoder.reset()
        val data = ByteArray(14)
        // temperature at bytes 4-5 → data[2-3]
        val temp = 3500
        data[2] = (temp and 0xFF).toByte()
        data[3] = ((temp shr 8) and 0xFF).toByte()
        // charge flag at byte 15 bit4 → data[13]
        data[13] = 0x10.toByte()  // bit4 set = charging
        val packet = buildKsFrame(0xC9, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(3500, decoded.newState!!.temperature2, "Battery temp should be 3500")
        assertEquals(1, decoded.newState!!.chargingStatus, "Charging flag should be 1")
    }

    @Test
    fun `battery temp 0xC9 not charging`() {
        decoder.reset()
        val data = ByteArray(14)
        data[13] = 0x00.toByte()  // bit4 not set
        val packet = buildKsFrame(0xC9, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(0, decoded.newState!!.chargingStatus, "Charging flag should be 0")
    }

    @Test
    fun `password login 0x46 locked`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 1  // need password = locked
        val packet = buildKsFrame(0x46, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1, decoded.newState!!.lockState, "Lock state should be 1 (locked)")
    }

    @Test
    fun `password login 0x46 unlocked`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 0  // logged in
        val packet = buildKsFrame(0x46, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(0, decoded.newState!!.lockState, "Lock state should be 0 (unlocked)")
    }

    @Test
    fun `lift sensor 0x4C on`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 1  // enabled
        val packet = buildKsFrame(0x4C, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.newState!!.handleButton, "Handle button should be true when lift sensor on")
    }

    @Test
    fun `lift sensor 0x4C off`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 0  // disabled
        val packet = buildKsFrame(0x4C, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertFalse(decoded.newState!!.handleButton, "Handle button should be false when lift sensor off")
    }

    @Test
    fun `headlight mode 0x55 normal`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 0  // normal
        val packet = buildKsFrame(0x55, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1, decoded.newState!!.lightMode, "Normal headlight mode should map to lightMode 1 (on)")
    }

    @Test
    fun `headlight mode 0x55 strobe`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 1  // strobe
        val packet = buildKsFrame(0x55, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(2, decoded.newState!!.lightMode, "Strobe headlight mode should map to lightMode 2")
    }

    @Test
    fun `LED mode readback 0x4D sets ledMode`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 5  // LED mode 5
        val packet = buildKsFrame(0x4D, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(5, decoded.newState!!.ledMode, "LED mode should be 5")
    }

    @Test
    fun `turn off timer 0x3F parses timer`() {
        decoder.reset()
        val data = ByteArray(14)
        data[0] = 0  // type=0 means timer value follows
        // timer at bytes 4-5 → data[2-3]
        val minutes = 30
        data[2] = (minutes and 0xFF).toByte()
        data[3] = ((minutes shr 8) and 0xFF).toByte()
        val packet = buildKsFrame(0x3F, data)

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1800, decoded.newState!!.autoOffTime, "Timer 30 min should be 1800 seconds")
    }

    // ==================== New Command Tests ====================

    @Test
    fun `buildCommand SetLed on sends inverted byte`() {
        val commands = decoder.buildCommand(WheelCommand.SetLed(true))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x00.toByte(), frame[2], "LED on should send 0x00 (inverted)")
        assertEquals(0x6C.toByte(), frame[16], "Should use LED_ON_OFF cmd byte 0x6C")
    }

    @Test
    fun `buildCommand SetLed off sends inverted byte`() {
        val commands = decoder.buildCommand(WheelCommand.SetLed(false))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x01.toByte(), frame[2], "LED off should send 0x01 (inverted)")
    }

    @Test
    fun `buildCommand SetLedMode uses 0x4D cmd byte`() {
        val commands = decoder.buildCommand(WheelCommand.SetLedMode(3))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(3.toByte(), frame[2], "LED mode should be at byte 2")
        assertEquals(0x4D.toByte(), frame[16], "Should use LED_PATTERN cmd byte 0x4D")
    }

    @Test
    fun `buildCommand SetMute on`() {
        decoder.reset()
        val commands = decoder.buildCommand(WheelCommand.SetMute(true))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x01.toByte(), frame[3], "Mute=true should set byte[3]=1")
        assertEquals(0x73.toByte(), frame[16], "Should use LIGHT_MODE cmd byte 0x73")
    }

    @Test
    fun `buildCommand SetMute preserves current light mode`() {
        decoder.reset()
        // First feed a 0xB9 frame with light mode 0x14 (auto)
        val b9data = ByteArray(14)
        b9data[8] = 0x14.toByte()
        decoder.decode(buildKsFrame(0xB9, b9data), defaultState, defaultConfig)

        // Now send SetMute — should preserve 0x14 in byte[2]
        val commands = decoder.buildCommand(WheelCommand.SetMute(true))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x14.toByte(), frame[2], "Should preserve light mode 0x14 in byte[2]")
    }

    @Test
    fun `buildCommand SetHandleButton on`() {
        val commands = decoder.buildCommand(WheelCommand.SetHandleButton(true))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x01.toByte(), frame[2], "Lift sensor on should send 1")
        assertEquals(0x7E.toByte(), frame[16], "Should use LIFT_SENSOR cmd byte 0x7E")
    }

    @Test
    fun `buildCommand SetHandleButton off`() {
        val commands = decoder.buildCommand(WheelCommand.SetHandleButton(false))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(0x00.toByte(), frame[2], "Lift sensor off should send 0")
    }

    @Test
    fun `buildCommand SetLightBrightness sends clamped value`() {
        val commands = decoder.buildCommand(WheelCommand.SetLightBrightness(75))
        assertTrue(commands.isNotEmpty())
        val frame = (commands[0] as WheelCommand.SendBytes).data
        assertEquals(75.toByte(), frame[4], "Brightness should be at byte 4")
        assertEquals(0x54.toByte(), frame[16], "Should use INSTRUMENT_BRIGHTNESS cmd byte 0x54")
    }

    @Test
    fun `buildCommand SetLightBrightness clamps to 50-100`() {
        // Below minimum
        val cmds1 = decoder.buildCommand(WheelCommand.SetLightBrightness(30))
        val frame1 = (cmds1[0] as WheelCommand.SendBytes).data
        assertEquals(50.toByte(), frame1[4], "Below minimum should clamp to 50")

        // Above maximum
        val cmds2 = decoder.buildCommand(WheelCommand.SetLightBrightness(150))
        val frame2 = (cmds2[0] as WheelCommand.SendBytes).data
        assertEquals(100.toByte(), frame2[4], "Above maximum should clamp to 100")
    }

    // ==================== SUPPORTED_COMMANDS Tests ====================

    @Test
    fun `new commands are in supported commands`() {
        assertTrue(SettingsCommandId.LED in KingsongDecoder.SUPPORTED_COMMANDS, "LED should be supported")
        assertTrue(SettingsCommandId.MUTE in KingsongDecoder.SUPPORTED_COMMANDS, "MUTE should be supported")
        assertTrue(SettingsCommandId.HANDLE_BUTTON in KingsongDecoder.SUPPORTED_COMMANDS, "HANDLE_BUTTON should be supported")
        assertTrue(SettingsCommandId.LIGHT_BRIGHTNESS in KingsongDecoder.SUPPORTED_COMMANDS, "LIGHT_BRIGHTNESS should be supported")
    }

    // ==================== Name Checksum Tests ====================

    @Test
    fun `name frame 0xBB with valid checksum does not re-request`() {
        decoder.reset()
        // First set version >= 117 by sending a name with version 200
        val nameStr = "KS-S18-0200"
        val packet = ByteArray(20)
        packet[0] = 0xAA.toByte()
        packet[1] = 0x55
        val nameBytes = nameStr.encodeToByteArray()
        var nameSum = 0
        for (i in nameBytes.indices) {
            if (i + 2 < 16) {
                packet[i + 2] = nameBytes[i]
                nameSum += nameBytes[i].toInt() and 0xFF
            }
        }
        packet[16] = 0xBB.toByte()
        packet[17] = 0x14
        // Set valid checksum at bytes 18-19
        packet[18] = ((nameSum shr 8) and 0xFF).toByte()
        packet[19] = (nameSum and 0xFF).toByte()

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.commands.isEmpty(), "Valid checksum should not trigger re-request")
    }

    @Test
    fun `name frame 0xBB with invalid checksum triggers re-request`() {
        decoder.reset()
        // Set version >= 117 by feeding a name first
        val namePacket1 = buildKsNamePacket("KS-S18-0200")
        decoder.decode(namePacket1, defaultState, defaultConfig)

        // Now send another name frame with bad checksum (footer 5A5A won't match)
        val nameStr = "KS-S18-0200"
        val packet = ByteArray(20)
        packet[0] = 0xAA.toByte()
        packet[1] = 0x55
        val nameBytes = nameStr.encodeToByteArray()
        for (i in nameBytes.indices) {
            if (i + 2 < 16) packet[i + 2] = nameBytes[i]
        }
        packet[16] = 0xBB.toByte()
        packet[17] = 0x14
        // Wrong checksum
        packet[18] = 0xFF.toByte()
        packet[19] = 0xFF.toByte()

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.commands.isNotEmpty(), "Invalid checksum should trigger name re-request")
    }
}
