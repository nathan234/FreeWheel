package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ==================== Frame Builders ====================

    /**
     * Build a generic 20-byte Kingsong frame.
     *
     * @param frameType Frame type byte at position 16
     * @param data 14-byte payload (copied into positions 2-15)
     * @param byte17 Value at position 17 (0x14 for normal frames, pNum for BMS)
     */
    private fun buildKsFrame(
        frameType: Int,
        data: ByteArray = ByteArray(14),
        byte17: Int = 0x14
    ): ByteArray {
        val packet = ByteArray(20)
        packet[0] = 0xAA.toByte()
        packet[1] = 0x55
        data.copyInto(packet, 2, 0, minOf(data.size, 14))
        packet[16] = frameType.toByte()
        packet[17] = byte17.toByte()
        packet[18] = 0x5A
        packet[19] = 0x5A
        return packet
    }

    /**
     * Build a live data (0xA9) frame.
     *
     * Payload layout (within the 14-byte data region, offsets relative to frame byte 2):
     * - [0-1] LE: voltage (raw, e.g. 6505 = 65.05V)
     * - [2-3] LE: speed (raw, decoder stores directly in WheelState.speed)
     * - [4-7] LE-pairs: totalDistance (4-byte, read via getInt4R)
     * - [8-9] LE: current (read manually as data[10]+(data[11]<<8) in frame coords)
     * - [10-11] LE: temperature (raw, e.g. 3600 = 36.00C)
     * - [12]: mode value (stored when byte 15 in frame = 0xE0)
     * - [13]: mode indicator (0xE0 for standard)
     */
    private fun buildKsLivePacket(
        voltage: Int,
        speed: Int = 0,
        current: Int = 0,
        temperature: Int = 3600,
        mode: Int = 0
    ): ByteArray {
        val data = ByteArray(14)
        // Voltage LE at data[0-1]
        data[0] = (voltage and 0xFF).toByte()
        data[1] = ((voltage shr 8) and 0xFF).toByte()
        // Speed LE at data[2-3]
        data[2] = (speed and 0xFF).toByte()
        data[3] = ((speed shr 8) and 0xFF).toByte()
        // totalDistance at data[4-7]: leave 0
        // current LE at data[8-9] (frame bytes 10-11, read as data[10]+(data[11]<<8))
        data[8] = (current and 0xFF).toByte()
        data[9] = ((current shr 8) and 0xFF).toByte()
        // temperature LE at data[10-11]
        data[10] = (temperature and 0xFF).toByte()
        data[11] = ((temperature shr 8) and 0xFF).toByte()
        // mode at data[12], mode indicator at data[13]
        data[12] = mode.toByte()
        data[13] = 0xE0.toByte()
        return buildKsFrame(0xA9, data)
    }

    /**
     * Build a name/type (0xBB) frame.
     * Name string bytes go into positions 2-15 (null-terminated).
     */
    private fun buildKsNamePacket(name: String): ByteArray {
        val packet = ByteArray(20)
        packet[0] = 0xAA.toByte()
        packet[1] = 0x55
        val nameBytes = name.encodeToByteArray()
        for (i in nameBytes.indices) {
            if (i + 2 < 16) packet[i + 2] = nameBytes[i]
        }
        packet[16] = 0xBB.toByte()
        packet[17] = 0x14
        packet[18] = 0x5A
        packet[19] = 0x5A
        return packet
    }

    /**
     * Build a BMS info (0xF1, pNum=0x00) frame with voltage, current, capacity.
     *
     * Payload layout (LE at data offsets):
     * - [0-1]: voltage (divided by 100 in decoder)
     * - [2-3]: current (divided by 100 in decoder)
     * - [4-5]: remaining capacity (multiplied by 10 in decoder)
     * - [6-7]: factory capacity (multiplied by 10 in decoder)
     * - [8-9]: full cycles
     */
    private fun buildKsBmsInfoFrame(
        voltage: Int,
        current: Int,
        remCap: Int = 200,
        factoryCap: Int = 300,
        fullCycles: Int = 50
    ): ByteArray {
        val data = ByteArray(14)
        // voltage LE
        data[0] = (voltage and 0xFF).toByte()
        data[1] = ((voltage shr 8) and 0xFF).toByte()
        // current LE
        data[2] = (current and 0xFF).toByte()
        data[3] = ((current shr 8) and 0xFF).toByte()
        // remCap LE
        data[4] = (remCap and 0xFF).toByte()
        data[5] = ((remCap shr 8) and 0xFF).toByte()
        // factoryCap LE
        data[6] = (factoryCap and 0xFF).toByte()
        data[7] = ((factoryCap shr 8) and 0xFF).toByte()
        // fullCycles LE
        data[8] = (fullCycles and 0xFF).toByte()
        data[9] = ((fullCycles shr 8) and 0xFF).toByte()
        return buildKsFrame(0xF1, data, byte17 = 0x00)
    }

    /**
     * Build a BMS cell voltage frame (0xF1, pNum=0x02-0x06).
     * Each frame carries up to 7 cell voltages in LE pairs.
     * Cell voltage unit: raw (divided by 1000.0 in decoder, e.g. 4200 = 4.200V).
     */
    private fun buildKsBmsCellFrame(pNum: Int, cellVoltages: List<Int>): ByteArray {
        val data = ByteArray(14)
        for (i in cellVoltages.indices) {
            if (i < 7) {
                val v = cellVoltages[i]
                data[i * 2] = (v and 0xFF).toByte()
                data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
        }
        return buildKsFrame(0xF1, data, byte17 = pNum)
    }

    /**
     * Build a distance/time (0xB9) frame.
     *
     * Payload layout (LE-pairs at data offsets):
     * - [0-3]: distance (4-byte, read via getInt4R)
     * - [6-7]: topSpeed (2-byte, read via getInt2R)
     * - [10]: fanStatus
     * - [11]: chargingStatus
     * - [12-13]: temperature2 (2-byte, read via getInt2R)
     */
    private fun buildKsDistancePacket(
        distance: Long = 0,
        fanStatus: Int = 0,
        chargingStatus: Int = 0,
        temperature2: Int = 0
    ): ByteArray {
        val data = ByteArray(14)
        // distance LE-pairs at data[0-3]
        // getInt4R reads 4 bytes, reverses pairs, reads BE
        // To get value V: write LE pairs → [lo0, hi0, lo1, hi1]
        // reverseEvery2 → [hi0, lo0, hi1, lo1]
        // readBE → hi0<<24 | lo0<<16 | hi1<<8 | lo1
        // For this to equal V, we need:
        //   hi0 = (V >> 24), lo0 = (V >> 16) & 0xFF
        //   hi1 = (V >> 8) & 0xFF, lo1 = V & 0xFF
        // So the LE-pair layout in data is: [lo0=V>>16, hi0=V>>24, lo1=V&0xFF, hi1=V>>8]
        val v = distance.toInt()
        data[0] = ((v shr 16) and 0xFF).toByte()
        data[1] = ((v shr 24) and 0xFF).toByte()
        data[2] = (v and 0xFF).toByte()
        data[3] = ((v shr 8) and 0xFF).toByte()
        // fanStatus at data[10]
        data[10] = fanStatus.toByte()
        // chargingStatus at data[11]
        data[11] = chargingStatus.toByte()
        // temperature2 LE at data[12-13]
        data[12] = (temperature2 and 0xFF).toByte()
        data[13] = ((temperature2 shr 8) and 0xFF).toByte()
        return buildKsFrame(0xB9, data)
    }

    // ==================== Validation Tests ====================

    @Test
    fun `short data is rejected`() {
        decoder.reset()
        for (size in 0..19) {
            val data = ByteArray(size) { 0x55 }
            val result = decoder.decode(data, defaultState, defaultConfig)
            if (size < 20) {
                assertNull(result, "Should return null for data of size $size")
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
        assertNull(result, "Wrong header should return null")
    }

    @Test
    fun `AA 55 header is accepted`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6000)
        assertEquals(0xAA.toByte(), packet[0])
        assertEquals(0x55.toByte(), packet[1])

        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertNotNull(result, "AA 55 header should be accepted")
    }

    // ==================== Live Data (0xA9) Tests ====================

    @Test
    fun `live data 0xA9 parses voltage`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6505)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertTrue(result.hasNewData, "0xA9 should set hasNewData")
        assertEquals(6505, result.newState.voltage, "Raw voltage should be 6505")
        assertEquals(65.05, result.newState.voltageV, 0.01, "Voltage should be 65.05V")
    }

    @Test
    fun `live data 0xA9 parses speed`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6000, speed = 515)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        // Speed is stored directly as the raw value from getInt2R
        assertEquals(515, result.newState.speed)
        assertEquals(5.15, result.newState.speedKmh, 0.01, "Speed should be 5.15 km/h")
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

        assertNotNull(result)
        assertEquals(215, result.newState.current, "Current should be 215 (2.15A)")
    }

    @Test
    fun `live data 0xA9 parses temperature`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6000, temperature = 3600)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertEquals(3600, result.newState.temperature, "Raw temperature should be 3600")
        assertEquals(36, result.newState.temperatureC, "Temperature should be 36C")
    }

    @Test
    fun `live data 0xA9 calculates power from current and voltage`() {
        decoder.reset()
        // Power = ((current / 100.0) * voltage).roundToInt()
        // current=1000 (10.00A), voltage=6000 (60.00V)
        // power = (1000 / 100.0) * 6000 = 10.0 * 6000 = 60000
        val packet = buildKsLivePacket(voltage = 6000, current = 1000)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        val expectedPower = ((1000 / 100.0) * 6000).roundToInt()
        assertEquals(expectedPower, result.newState.power)
    }

    @Test
    fun `live data 0xA9 calculates battery percentage for 67v wheel`() {
        decoder.reset()
        // Without a model set, uses 67v battery calculation (default)
        // Standard percent: voltage 6000 -> (6000 - 5000) / 16 = 62
        val packet = buildKsLivePacket(voltage = 6000)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertEquals(62, result.newState.batteryLevel, "Battery should be 62% for 67v default")
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

        assertNotNull(result)
        assertEquals(12, result.newState.batteryLevel, "Battery should be 12% for 84v KS-S18")
    }

    @Test
    fun `live data 0xA9 sets wheelType to KINGSONG`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6000)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertEquals(WheelType.KINGSONG, result.newState.wheelType)
    }

    @Test
    fun `live data 0xA9 sets mode string when indicator is 0xE0`() {
        decoder.reset()
        val packet = buildKsLivePacket(voltage = 6000, mode = 2)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertEquals("2", result.newState.modeStr, "Mode string should reflect the mode byte")
    }

    // ==================== Name/Type (0xBB) Tests ====================

    @Test
    fun `name frame 0xBB sets model and name`() {
        decoder.reset()
        val packet = buildKsNamePacket("KS-S18-0205")
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertEquals("KS-S18-0205", result.newState.name, "Name should be KS-S18-0205")
        assertEquals("KS-S18", result.newState.model, "Model should be KS-S18")
    }

    @Test
    fun `name frame 0xBB extracts version from last segment`() {
        decoder.reset()
        val packet = buildKsNamePacket("KS-S18-0205")
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertEquals("2.05", result.newState.version, "Version should be 2.05")
    }

    @Test
    fun `name frame 0xBB handles multi-segment model names`() {
        decoder.reset()
        val packet = buildKsNamePacket("KS-16X-1234")
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertEquals("KS-16X", result.newState.model, "Model should be KS-16X")
        assertEquals("12.34", result.newState.version, "Version should be 12.34")
    }

    @Test
    fun `name frame 0xBB handles name without dashes`() {
        decoder.reset()
        val packet = buildKsNamePacket("ROCKWHEEL")
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertEquals("ROCKWHEEL", result.newState.model, "Single-segment name should be model")
    }

    // ==================== Serial Number (0xB3) Tests ====================

    @Test
    fun `serial frame 0xB3 triggers REQUEST_ALARMS command`() {
        decoder.reset()
        val packet = buildKsFrame(0xB3)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertTrue(result.commands.isNotEmpty(), "Serial frame should trigger alarm request command")
        assertTrue(
            result.commands[0] is WheelCommand.SendBytes,
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
        assertNotNull(result)
        assertTrue(
            result.newState.serialNumber.isNotEmpty(),
            "Serial number should be set"
        )
    }

    // ==================== Distance/Time (0xB9) Tests ====================

    @Test
    fun `distance frame 0xB9 parses fan and charging status`() {
        decoder.reset()
        val packet = buildKsDistancePacket(fanStatus = 1, chargingStatus = 1)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertEquals(1, result.newState.fanStatus, "Fan status should be 1")
        assertEquals(1, result.newState.chargingStatus, "Charging status should be 1")
    }

    @Test
    fun `distance frame 0xB9 parses temperature2`() {
        decoder.reset()
        val packet = buildKsDistancePacket(temperature2 = 4000)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertEquals(4000, result.newState.temperature2, "Temperature2 should be 4000")
        assertEquals(40, result.newState.temperature2C, "Temperature2 should be 40C")
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
        assertNotNull(result)
        assertEquals(64, result.newState.cpuLoad, "CPU load should be 64")
        assertEquals(1200, result.newState.output, "Output should be 1200")
        // calculatedPwm = output / 10000.0 = 1200 / 10000.0 = 0.12
        assertEquals(0.12, result.newState.calculatedPwm, 0.001)
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
        assertNotNull(result)
        assertEquals(32.05, result.newState.speedLimit, 0.01, "Speed limit should be 32.05 km/h")
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
        assertNotNull(result)
        assertTrue(result.hasNewData, "0xA4 should set hasNewData")
        assertTrue(
            result.commands.isNotEmpty(),
            "0xA4 frame should trigger alarm request response"
        )
    }

    @Test
    fun `alert frame 0xB5 does not trigger command response`() {
        decoder.reset()
        val packet = buildKsFrame(0xB5)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        assertTrue(result.hasNewData, "0xB5 should set hasNewData")
        assertTrue(
            result.commands.isEmpty(),
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
        assertNotNull(nameResult)
        state = nameResult.newState

        val livePacket = buildKsLivePacket(voltage = 6000)
        val liveResult = decoder.decode(livePacket, state, defaultConfig)
        assertNotNull(liveResult)

        assertTrue(decoder.isReady(), "Should be ready after name + voltage")
    }

    @Test
    fun `isReady requires positive voltage`() {
        decoder.reset()
        var state = defaultState

        val namePacket = buildKsNamePacket("KS-16X-0100")
        val nameResult = decoder.decode(namePacket, state, defaultConfig)
        assertNotNull(nameResult)
        state = nameResult.newState

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
        val nameResult = decoder.decode(namePacket, state, defaultConfig)!!
        state = nameResult.newState

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
        val nameResult = decoder.decode(namePacket, state, defaultConfig)!!
        state = nameResult.newState
        val livePacket = buildKsLivePacket(voltage = 6505)
        decoder.decode(livePacket, state, defaultConfig)
        assertTrue(decoder.isReady())

        // Reset for new connection
        decoder.reset()
        state = defaultState

        // Second connection with different wheel (KS-14D is a 67v wheel)
        val namePacket2 = buildKsNamePacket("KS-14D-0100")
        val nameResult2 = decoder.decode(namePacket2, state, defaultConfig)!!
        state = nameResult2.newState
        assertEquals("KS-14D", state.model, "Model should reflect new wheel after reset")

        val livePacket2 = buildKsLivePacket(voltage = 6000)
        val liveResult2 = decoder.decode(livePacket2, state, defaultConfig)!!
        assertTrue(decoder.isReady())
        // 67v battery: (6000 - 5000) / 16 = 62
        assertEquals(62, liveResult2.newState.batteryLevel, "Battery should use 67v calc for KS-14D")
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
    fun `getInitCommands returns name serial and alarm requests`() {
        val commands = decoder.getInitCommands()
        assertEquals(3, commands.size, "Should return 3 init commands")
        assertTrue(commands[0] is WheelCommand.SendBytes, "First should be SendBytes (name request)")
        assertTrue(commands[1] is WheelCommand.SendDelayed, "Second should be SendDelayed (serial)")
        assertTrue(commands[2] is WheelCommand.SendDelayed, "Third should be SendDelayed (alarms)")
    }

    // ==================== BMS Data (0xF1) Tests ====================

    @Test
    fun `BMS pNum 0x00 sets voltage and current`() {
        decoder.reset()
        // BMS voltage=8400 -> 84.00V, current=500 -> 5.00A
        val packet = buildKsBmsInfoFrame(voltage = 8400, current = 500)
        val result = decoder.decode(packet, defaultState, defaultConfig)

        assertNotNull(result)
        val bms = result.newState.bms1
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

        assertNotNull(result)
        val bms = result.newState.bms1!!
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
        assertNotNull(result)
        val bms = result.newState.bms1!!
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
        assertNotNull(result02)
        state = result02.newState

        // pNum 0x03: cells 7-13
        val cells03 = listOf(4130, 4120, 4110, 4100, 4090, 4080, 4070)
        val packet03 = buildKsBmsCellFrame(0x03, cells03)
        val result03 = decoder.decode(packet03, state, defaultConfig)
        assertNotNull(result03)
        state = result03.newState

        // pNum 0x04: cells 14-20
        val cells04 = listOf(4060, 4050, 4040, 4030, 4020, 4010, 4000)
        val packet04 = buildKsBmsCellFrame(0x04, cells04)
        val result04 = decoder.decode(packet04, state, defaultConfig)
        assertNotNull(result04)
        state = result04.newState

        // pNum 0x05: cells 21-27
        val cells05 = listOf(3990, 3980, 3970, 3960, 3950, 3940, 3930)
        val packet05 = buildKsBmsCellFrame(0x05, cells05)
        val result05 = decoder.decode(packet05, state, defaultConfig)
        assertNotNull(result05)
        state = result05.newState

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
        state = decoder.decode(packet02, state, defaultConfig)!!.newState

        val cells03 = listOf(4160, 4140, 4110, 4190, 4090, 4050, 4080)
        val packet03 = buildKsBmsCellFrame(0x03, cells03)
        state = decoder.decode(packet03, state, defaultConfig)!!.newState

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
        state = decoder.decode(packet06, state, defaultConfig)!!.newState

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
        state = decoder.decode(bms1Packet, state, defaultConfig)!!.newState
        assertEquals(84.0, state.bms1!!.voltage, 0.01, "BMS1 voltage should be 84.00V")

        // 0xF2 -> bms2
        val data = ByteArray(14)
        val bms2Voltage = 8200
        data[0] = (bms2Voltage and 0xFF).toByte()
        data[1] = ((bms2Voltage shr 8) and 0xFF).toByte()
        val bms2Packet = buildKsFrame(0xF2, data, byte17 = 0x00)
        state = decoder.decode(bms2Packet, state, defaultConfig)!!.newState

        assertEquals(82.0, state.bms2!!.voltage, 0.01, "BMS2 voltage should be 82.00V")
        // BMS1 should still be intact
        assertEquals(84.0, state.bms1!!.voltage, 0.01, "BMS1 should still be 84.00V")
    }

    // ==================== BMS Serial/Firmware Tests ====================

    @Test
    fun `BMS serial frame 0xE1 returns null state but stores serial`() {
        decoder.reset()
        // BMS serial frames (0xE1/0xE2) return null state from processBmsSerial
        // but still set the serial in the SmartBms object
        val data = ByteArray(14)
        "BMS-SN123".encodeToByteArray().copyInto(data, 0, 0, 9)
        val packet = buildKsFrame(0xE1, data)

        // processBmsSerial returns null, so decode returns null (no state update)
        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertNull(result, "BMS serial frame should return null (no state update path)")
    }

    @Test
    fun `BMS firmware frame 0xE5 returns null state`() {
        decoder.reset()
        val packet = buildKsFrame(0xE5)
        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertNull(result, "BMS firmware frame should return null")
    }

    // ==================== Unknown Frame Type Tests ====================

    @Test
    fun `unknown frame type returns null`() {
        decoder.reset()
        val packet = buildKsFrame(0xFF)  // 0xFF is not a known frame type
        val result = decoder.decode(packet, defaultState, defaultConfig)
        assertNull(result, "Unknown frame type should return null")
    }

    // ==================== Multi-Frame Integration Test ====================

    @Test
    fun `full initialization sequence produces correct state`() {
        // Simulate a real connection: name -> serial -> live data
        decoder.reset()
        var state = defaultState

        // 1. Name packet
        val namePacket = buildKsNamePacket("KS-S18-0205")
        val nameResult = decoder.decode(namePacket, state, defaultConfig)!!
        state = nameResult.newState
        assertEquals("KS-S18", state.model)
        assertEquals("2.05", state.version)
        assertFalse(decoder.isReady())

        // 2. Serial packet (triggers alarm request)
        val serialPacket = buildKsFrame(0xB3)
        val serialResult = decoder.decode(serialPacket, state, defaultConfig)!!
        state = serialResult.newState
        assertTrue(serialResult.commands.isNotEmpty(), "Serial should trigger alarm request")
        assertFalse(decoder.isReady(), "Still not ready without voltage")

        // 3. Live data packet
        val livePacket = buildKsLivePacket(voltage = 6505, speed = 515, current = 215, temperature = 1300)
        val liveResult = decoder.decode(livePacket, state, defaultConfig)!!
        state = liveResult.newState

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
}
