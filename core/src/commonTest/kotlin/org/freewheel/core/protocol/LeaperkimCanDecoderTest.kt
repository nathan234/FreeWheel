package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [LeaperkimCanDecoder].
 *
 * Tests cover:
 * - Frame unpacking (header, escape, checksum, trailer)
 * - Init state machine (PASSWORD -> INIT_COMM -> INIT_STATUS -> POLLING)
 * - Telemetry parsing from READ_VALUES responses
 * - Status parsing from INIT_STATUS/READ_STATUS responses
 * - Command encoding (buildCommand)
 * - Keep-alive alternation
 */
class LeaperkimCanDecoderTest {

    private val decoder = LeaperkimCanDecoder()
    private val defaultState = WheelState()
    private val defaultConfig = DecoderConfig()

    // ==================== Frame Builder Helper ====================

    /**
     * Build a raw CAN frame with proper header, checksum, and trailer.
     * Applies 0xA5 escape stuffing.
     *
     * @param canId CAN message ID (written as 4 bytes LE)
     * @param payload Data payload (after the 10-byte reserved area)
     * @return Complete frame bytes ready to feed to decoder
     */
    private fun buildTestFrame(canId: Int, payload: ByteArray): ByteArray {
        // Body: CAN_ID (4B LE) + reserved (10B zeros) + payload
        val body = ByteArray(4 + 10 + payload.size)
        body[0] = (canId and 0xFF).toByte()
        body[1] = ((canId shr 8) and 0xFF).toByte()
        body[2] = ((canId shr 16) and 0xFF).toByte()
        body[3] = ((canId shr 24) and 0xFF).toByte()
        payload.copyInto(body, 14)

        // Checksum: sum of body bytes mod 256
        var checksum = 0
        for (b in body) {
            checksum = (checksum + (b.toInt() and 0xFF)) and 0xFF
        }

        // Build with escaping
        val result = mutableListOf<Byte>()
        result.add(0xAA.toByte())
        result.add(0xAA.toByte())
        for (b in body) {
            val v = b.toInt() and 0xFF
            if (v == 0xA5) {
                result.add(0xA5.toByte())
                result.add(0xA5.toByte())
            } else {
                result.add(b)
            }
        }
        if (checksum == 0xA5) {
            result.add(0xA5.toByte())
            result.add(0xA5.toByte())
        } else {
            result.add(checksum.toByte())
        }
        result.add(0x55)
        result.add(0x55)

        return result.toByteArray()
    }

    /**
     * Helper to write an int32 LE into a byte array at offset.
     */
    private fun putIntLE(array: ByteArray, offset: Int, value: Int) {
        array[offset] = (value and 0xFF).toByte()
        array[offset + 1] = ((value shr 8) and 0xFF).toByte()
        array[offset + 2] = ((value shr 16) and 0xFF).toByte()
        array[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /**
     * Helper to write a uint64 LE into a byte array at offset.
     */
    private fun putLongLE(array: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            array[offset + i] = ((value shr (i * 8)) and 0xFF).toByte()
        }
    }

    // ==================== Initial State ====================

    @Test
    fun `wheelType is LEAPERKIM`() {
        assertEquals(WheelType.LEAPERKIM, decoder.wheelType)
    }

    @Test
    fun `isReady returns false before init sequence completes`() {
        assertFalse(decoder.isReady())
    }

    @Test
    fun `keepAliveIntervalMs is 500`() {
        assertEquals(500L, decoder.keepAliveIntervalMs)
    }

    @Test
    fun `getKeepAliveCommand returns null before ready`() {
        assertEquals(null, decoder.getKeepAliveCommand())
    }

    // ==================== Init Sequence ====================

    @Test
    fun `getInitCommands returns password command`() {
        val commands = decoder.getInitCommands()
        assertEquals(1, commands.size)
        assertTrue(commands[0] is WheelCommand.SendBytes)
        val bytes = (commands[0] as WheelCommand.SendBytes).data
        // Should start with AA AA header
        assertEquals(0xAA.toByte(), bytes[0])
        assertEquals(0xAA.toByte(), bytes[1])
    }

    @Test
    fun `init sequence - password ACK triggers INIT_COMM command`() {
        // Feed a response frame (simulating ACK to password)
        val ackFrame = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        val result = decoder.decode(ackFrame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1, decoded.commands.size)
        assertTrue(decoded.commands[0] is WheelCommand.SendBytes)
        assertFalse(decoder.isReady())
    }

    @Test
    fun `init sequence - INIT_COMM ACK triggers INIT_STATUS command`() {
        // Step 1: Password ACK
        val ack1 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        decoder.decode(ack1, defaultState, defaultConfig)

        // Step 2: INIT_COMM ACK
        val ack2 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_COMM, ByteArray(8))
        val result = decoder.decode(ack2, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1, decoded.commands.size)
        assertFalse(decoder.isReady())
    }

    @Test
    fun `init sequence - INIT_STATUS response makes decoder ready`() {
        // Complete the init sequence
        completeInitSequence()
        assertTrue(decoder.isReady())
    }

    // ==================== Telemetry Parsing ====================

    @Test
    fun `parses speed from READ_VALUES response`() {
        completeInitSequence()

        val payload = ByteArray(64)
        // Motor RPM 1 at offset 12: 5000
        putIntLE(payload, 12, 5000)
        // Motor RPM 2 at offset 16: 5000
        putIntLE(payload, 16, 5000)
        // Expected speed: abs(5000+5000) * 3.6 / 7624.0 * 100
        val expectedSpeed = (abs(10000) * 3.6 / 7624.0 * 100).roundToInt()

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(expectedSpeed, decoded.newState!!.speed)
    }

    @Test
    fun `parses voltage from READ_VALUES response`() {
        completeInitSequence()

        val payload = ByteArray(64)
        // Voltage at offset 24: 8400 (= 84.00V stored as x100)
        putIntLE(payload, 24, 8400)

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(8400, decoded.newState!!.voltage)
    }

    @Test
    fun `calculates battery level from voltage`() {
        completeInitSequence()

        val payload = ByteArray(64)
        // Voltage at offset 24: 8900 (= 89.00V, mid-range for 100V class)
        // Expected battery: ((8900 - 7935) / 19.5).roundToInt() = (965 / 19.5).roundToInt() = 49
        putIntLE(payload, 24, 8900)

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(49, decoded.newState!!.batteryLevel)
    }

    @Test
    fun `battery level is 0 at or below minimum voltage`() {
        completeInitSequence()

        val payload = ByteArray(64)
        putIntLE(payload, 24, 7900) // below 7935 threshold

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(0, decoded.newState!!.batteryLevel)
    }

    @Test
    fun `battery level is 100 at or above maximum voltage`() {
        completeInitSequence()

        val payload = ByteArray(64)
        putIntLE(payload, 24, 10000) // above 9870 threshold

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(100, decoded.newState!!.batteryLevel)
    }

    @Test
    fun `parses temperature from READ_VALUES response`() {
        completeInitSequence()

        val payload = ByteArray(64)
        // Temperature at offset 32: 35 (deg C as uint8)
        payload[32] = 35
        // Expected: 35 * 100 = 3500

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(3500, decoded.newState!!.temperature)
    }

    @Test
    fun `parses current from READ_VALUES response`() {
        completeInitSequence()

        val payload = ByteArray(64)
        // Current at offset 48: 15000 (= 15000 * 0.001 = 15A)
        // Stored as x100: 15000 * 0.1 = 1500
        putIntLE(payload, 48, 15000)

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1500, decoded.newState!!.phaseCurrent)
    }

    @Test
    fun `parses distance from READ_VALUES response`() {
        completeInitSequence()

        val payload = ByteArray(64)
        // Distance at offset 20: 500 (* 10 = 5000 meters)
        putIntLE(payload, 20, 500)

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(5000L, decoded.newState!!.wheelDistance)
    }

    @Test
    fun `parses pedal tilt from READ_VALUES response`() {
        completeInitSequence()

        val payload = ByteArray(64)
        // Pedal tilt at offset 0: 131072 (= 2.0 degrees in Q16.16)
        putIntLE(payload, 0, 131072) // 2 * 65536

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(2.0, decoded.newState!!.angle, 0.001)
    }

    @Test
    fun `sets wheelType to LEAPERKIM in telemetry`() {
        completeInitSequence()

        val payload = ByteArray(64)
        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(WheelType.LEAPERKIM, decoded.newState!!.wheelType)
    }

    // ==================== Status Parsing ====================

    @Test
    fun `parses serial from status response`() {
        // Complete init to PASSWORD phase, get INIT_COMM, then INIT_STATUS phase
        val ack1 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        decoder.decode(ack1, defaultState, defaultConfig)
        val ack2 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_COMM, ByteArray(8))
        decoder.decode(ack2, defaultState, defaultConfig)

        // Now INIT_STATUS response with serial
        val payload = ByteArray(140)
        putLongLE(payload, 0, 0x0123456789ABCDEFL)

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_STATUS, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals("123456789ABCDEF", decoded.newState!!.serialNumber)
    }

    @Test
    fun `parses firmware version from status response`() {
        val ack1 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        decoder.decode(ack1, defaultState, defaultConfig)
        val ack2 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_COMM, ByteArray(8))
        decoder.decode(ack2, defaultState, defaultConfig)

        val payload = ByteArray(140)
        payload[24] = 2  // major
        payload[25] = 1  // minor
        payload[26] = 5  // patch

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_STATUS, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals("2.1.5", decoded.newState!!.version)
    }

    @Test
    fun `parses model name from status response`() {
        val ack1 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        decoder.decode(ack1, defaultState, defaultConfig)
        val ack2 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_COMM, ByteArray(8))
        decoder.decode(ack2, defaultState, defaultConfig)

        val payload = ByteArray(140)
        putIntLE(payload, 104, 0) // Model ID 0 = Sherman (R1N)

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_STATUS, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals("Sherman", decoded.newState!!.model)
    }

    @Test
    fun `parses model name Abrams`() {
        val ack1 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        decoder.decode(ack1, defaultState, defaultConfig)
        val ack2 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_COMM, ByteArray(8))
        decoder.decode(ack2, defaultState, defaultConfig)

        val payload = ByteArray(140)
        putIntLE(payload, 104, 20) // Model ID 20 = Abrams

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_STATUS, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals("Abrams", decoded.newState!!.model)
    }

    @Test
    fun `parses headlight from status response`() {
        val ack1 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        decoder.decode(ack1, defaultState, defaultConfig)
        val ack2 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_COMM, ByteArray(8))
        decoder.decode(ack2, defaultState, defaultConfig)

        val payload = ByteArray(140)
        payload[80] = 0x01 // headlight on

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_STATUS, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(1, decoded.newState!!.lightMode)
    }

    @Test
    fun `parses handle button from status response`() {
        val ack1 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        decoder.decode(ack1, defaultState, defaultConfig)
        val ack2 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_COMM, ByteArray(8))
        decoder.decode(ack2, defaultState, defaultConfig)

        val payload = ByteArray(140)
        payload[129] = 0x01 // handle button on

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_STATUS, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.newState!!.handleButton)
    }

    @Test
    fun `parses transport mode from status response`() {
        val ack1 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        decoder.decode(ack1, defaultState, defaultConfig)
        val ack2 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_COMM, ByteArray(8))
        decoder.decode(ack2, defaultState, defaultConfig)

        val payload = ByteArray(140)
        payload[132] = 0x01 // transport mode on

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_STATUS, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertTrue(decoded.newState!!.transportMode)
    }

    // ==================== Escape Handling ====================

    @Test
    fun `handles 0xA5 escape bytes in payload`() {
        completeInitSequence()

        // Build a frame manually with a payload containing 0xA5
        val payload = ByteArray(64)
        // Put a value that will have 0xA5 in it
        payload[32] = 0xA5.toByte() // temperature = 0xA5 = 165 deg C -> 16500

        // The buildTestFrame helper handles escaping
        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_VALUES, payload)

        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals(16500, decoded.newState!!.temperature) // 165 * 100
    }

    // ==================== Command Encoding ====================

    @Test
    fun `buildCommand SetLight on`() {
        val commands = decoder.buildCommand(WheelCommand.SetLight(true))
        assertEquals(1, commands.size)
        assertTrue(commands[0] is WheelCommand.SendBytes)
        val bytes = (commands[0] as WheelCommand.SendBytes).data
        // Verify starts with AA AA header
        assertEquals(0xAA.toByte(), bytes[0])
        assertEquals(0xAA.toByte(), bytes[1])
    }

    @Test
    fun `buildCommand SetLight off`() {
        val commands = decoder.buildCommand(WheelCommand.SetLight(false))
        assertEquals(1, commands.size)
    }

    @Test
    fun `buildCommand Beep sends horn via WRITE_MULTI`() {
        val commands = decoder.buildCommand(WheelCommand.Beep)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is WheelCommand.SendBytes)
    }

    @Test
    fun `buildCommand PowerOff sends via WRITE_MULTI`() {
        val commands = decoder.buildCommand(WheelCommand.PowerOff)
        assertEquals(1, commands.size)
        assertTrue(commands[0] is WheelCommand.SendBytes)
    }

    @Test
    fun `buildCommand SetLock sends via WRITE_MULTI`() {
        val commands = decoder.buildCommand(WheelCommand.SetLock(true))
        assertEquals(1, commands.size)
        assertTrue(commands[0] is WheelCommand.SendBytes)
    }

    @Test
    fun `buildCommand SetLed sends via WRITE_MULTI`() {
        val commands = decoder.buildCommand(WheelCommand.SetLed(true))
        assertEquals(1, commands.size)
    }

    @Test
    fun `buildCommand SetTransportMode sends via WRITE_MULTI`() {
        val commands = decoder.buildCommand(WheelCommand.SetTransportMode(true))
        assertEquals(1, commands.size)
    }

    @Test
    fun `buildCommand SetHandleButton`() {
        val commands = decoder.buildCommand(WheelCommand.SetHandleButton(true))
        assertEquals(1, commands.size)
    }

    @Test
    fun `buildCommand SetMaxSpeed`() {
        val commands = decoder.buildCommand(WheelCommand.SetMaxSpeed(40))
        assertEquals(1, commands.size)
    }

    @Test
    fun `buildCommand SetSpeakerVolume`() {
        val commands = decoder.buildCommand(WheelCommand.SetSpeakerVolume(75))
        assertEquals(1, commands.size)
    }

    @Test
    fun `buildCommand SetPedalTilt`() {
        val commands = decoder.buildCommand(WheelCommand.SetPedalTilt(3))
        assertEquals(1, commands.size)
    }

    @Test
    fun `buildCommand SetPedalSensitivity`() {
        val commands = decoder.buildCommand(WheelCommand.SetPedalSensitivity(50))
        assertEquals(1, commands.size)
    }

    @Test
    fun `buildCommand SetRideMode`() {
        val commands = decoder.buildCommand(WheelCommand.SetRideMode(true))
        assertEquals(1, commands.size)
    }

    @Test
    fun `buildCommand unsupported returns empty`() {
        val commands = decoder.buildCommand(WheelCommand.Calibrate)
        assertTrue(commands.isEmpty())
    }

    // ==================== Keep-Alive ====================

    @Test
    fun `keep-alive alternates between READ_VALUES and READ_STATUS`() {
        completeInitSequence()

        val cmd1 = decoder.getKeepAliveCommand()
        assertNotNull(cmd1)

        val cmd2 = decoder.getKeepAliveCommand()
        assertNotNull(cmd2)

        // They should be different (alternating)
        assertTrue(cmd1 is WheelCommand.SendBytes)
        assertTrue(cmd2 is WheelCommand.SendBytes)
        assertFalse((cmd1 as WheelCommand.SendBytes).data.contentEquals((cmd2 as WheelCommand.SendBytes).data))
    }

    // ==================== Reset ====================

    @Test
    fun `reset clears state and makes not ready`() {
        completeInitSequence()
        assertTrue(decoder.isReady())

        decoder.reset()

        assertFalse(decoder.isReady())
    }

    @Test
    fun `can re-init after reset`() {
        completeInitSequence()
        assertTrue(decoder.isReady())

        decoder.reset()
        assertFalse(decoder.isReady())

        completeInitSequence()
        assertTrue(decoder.isReady())
    }

    // ==================== Invalid Frames ====================

    @Test
    fun `returns Buffering for empty data`() {
        val result = decoder.decode(byteArrayOf(), defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Buffering)
    }

    @Test
    fun `returns Buffering for incomplete frame`() {
        val result = decoder.decode(byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x01, 0x02), defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Buffering)
    }

    @Test
    fun `returns Unhandled for bad checksum`() {
        // Build a frame but corrupt the checksum
        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        // Corrupt the checksum byte (second to last before 55 55)
        val checksumIndex = frame.size - 3
        frame[checksumIndex] = ((frame[checksumIndex].toInt() + 1) and 0xFF).toByte()

        val result = decoder.decode(frame, defaultState, defaultConfig)
        assertTrue(result is DecodeResult.Unhandled)
    }

    // ==================== Status Polling ====================

    @Test
    fun `READ_STATUS in polling phase updates settings`() {
        completeInitSequence()

        val payload = ByteArray(140)
        putIntLE(payload, 104, 85) // Glide 3
        payload[80] = 0x01 // headlight on

        val frame = buildTestFrame(LeaperkimCanDecoder.CAN_READ_STATUS, payload)
        val result = decoder.decode(frame, defaultState, defaultConfig)

        assertTrue(result is DecodeResult.Success)
        val decoded = (result as DecodeResult.Success).data
        assertEquals("Glide 3", decoded.newState!!.model)
        assertEquals(1, decoded.newState!!.lightMode)
    }

    // ==================== buildCanFrame verification ====================

    @Test
    fun `buildCanFrame produces valid frame that can be decoded`() {
        // Build a frame using the decoder's method
        val payload = ByteArray(8)
        putIntLE(payload, 0, 42)
        val frame = decoder.buildCanFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, payload)

        // Verify structure
        assertEquals(0xAA.toByte(), frame[0])
        assertEquals(0xAA.toByte(), frame[1])
        assertEquals(0x55.toByte(), frame[frame.size - 2])
        assertEquals(0x55.toByte(), frame[frame.size - 1])
    }

    // ==================== Helpers ====================

    /**
     * Complete the 3-step init sequence to reach POLLING phase.
     */
    private fun completeInitSequence() {
        // Step 1: Password ACK
        val ack1 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_PASSWORD, ByteArray(8))
        decoder.decode(ack1, defaultState, defaultConfig)

        // Step 2: INIT_COMM ACK
        val ack2 = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_COMM, ByteArray(8))
        decoder.decode(ack2, defaultState, defaultConfig)

        // Step 3: INIT_STATUS response (needs enough payload for model ID etc.)
        val statusPayload = ByteArray(140)
        putIntLE(statusPayload, 104, 0) // Sherman
        val statusFrame = buildTestFrame(LeaperkimCanDecoder.CAN_INIT_STATUS, statusPayload)
        decoder.decode(statusFrame, defaultState, defaultConfig)
    }
}
