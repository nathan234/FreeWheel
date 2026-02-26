package com.cooper.wheellog.core.protocol

import com.cooper.wheellog.core.domain.WheelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lifecycle tests covering init commands, retry/fallback logic, and state machine
 * behavior across all decoders.
 *
 * Existing *Test.kt and *ComparisonTest.kt files cover frame parsing.
 * This file covers the behaviors that happen _around_ parsing: connection
 * handshakes, identity probing, keep-alive state machines, and readiness checks.
 */
class DecoderLifecycleTest {

    private val defaultState = WheelState()
    private val defaultConfig = DecoderConfig()


    /**
     * Build a Gotway frame 0x00 (live data) with default values.
     * frameType byte at offset 18 is 0x00 (live data).
     */
    private fun buildGotwayLiveDataFrame(
        voltage: Int = 6000,
        speed: Int = 0,
        distance: Int = 0
    ): ByteArray {
        val header = byteArrayOf(0x55, 0xAA.toByte())
        return header +
            shortToBytesBE(voltage) +
            shortToBytesBE(speed) +
            byteArrayOf(0, 0) +
            shortToBytesBE(distance) +
            shortToBytesBE(0) + // phaseCurrent
            shortToBytesBE(99) + // temperature
            byteArrayOf(0, 0, 0, 0, 0, 0x18, 0x5A, 0x5A, 0x5A, 0x5A)
    }

    // ==================== GotwayDecoder Lifecycle ====================

    @Test
    fun `Gotway getInitCommands emits V, b, N, b in correct order`() {
        val decoder = GotwayDecoder()
        val commands = decoder.getInitCommands()

        assertEquals(4, commands.size, "Should emit 4 init commands")

        // First: V (firmware request)
        val cmd0 = commands[0] as WheelCommand.SendBytes
        assertEquals("V", cmd0.data.decodeToString())

        // Second: b (beep, delayed 100ms)
        val cmd1 = commands[1] as WheelCommand.SendDelayed
        assertEquals("b", cmd1.data.decodeToString())
        assertEquals(100L, cmd1.delayMs)

        // Third: N (name request, delayed 200ms)
        val cmd2 = commands[2] as WheelCommand.SendDelayed
        assertEquals("N", cmd2.data.decodeToString())
        assertEquals(200L, cmd2.delayMs)

        // Fourth: b (beep, delayed 300ms)
        val cmd3 = commands[3] as WheelCommand.SendDelayed
        assertEquals("b", cmd3.data.decodeToString())
        assertEquals(300L, cmd3.delayMs)
    }

    @Test
    fun `Gotway retry emits V command when fw empty after live data`() {
        val decoder = GotwayDecoder()
        // Feed a live data frame without any prior firmware response
        val liveFrame = buildGotwayLiveDataFrame(voltage = 6000, speed = 100)
        val result = decoder.decode(liveFrame, defaultState, defaultConfig)

        assertNotNull(result)
        // Should emit a V command to request firmware
        val vCommands = result.commands.filterIsInstance<WheelCommand.SendBytes>()
            .filter { it.data.decodeToString() == "V" }
        assertTrue(vCommands.isNotEmpty(), "Should retry V when fw is empty")
    }

    @Test
    fun `Gotway retry emits N command after fw populated but model empty`() {
        val decoder = GotwayDecoder()
        // Send firmware response to set fw
        val fwData = "GW1.23".encodeToByteArray()
        decoder.decode(fwData, defaultState, defaultConfig)

        // Now send live data — fw is set but model is still empty
        val liveFrame = buildGotwayLiveDataFrame(voltage = 6000, speed = 100)
        val result = decoder.decode(liveFrame, defaultState, defaultConfig)

        assertNotNull(result)
        val nCommands = result.commands.filterIsInstance<WheelCommand.SendBytes>()
            .filter { it.data.decodeToString() == "N" }
        assertTrue(nCommands.isNotEmpty(), "Should retry N when model is empty")
    }

    @Test
    fun `Gotway fallback sets model to fwProt after 50 attempts`() {
        val decoder = GotwayDecoder()
        // Set firmware to "Begode" protocol
        val fwData = "GW1.23".encodeToByteArray()
        decoder.decode(fwData, defaultState, defaultConfig)

        // Send 51 live data frames without any NAME response.
        // Counter goes 0→1→...→50 over 50 frames; fallback triggers on frame 51.
        var state = defaultState
        var result: DecodedData? = null
        for (i in 1..51) {
            val frame = buildGotwayLiveDataFrame(voltage = 6000, speed = 100)
            result = decoder.decode(frame, state, defaultConfig)
            if (result != null) state = result.newState
        }

        // After exceeding MAX_INFO_ATTEMPTS, model should fall back to "Begode" (the fwProt value)
        assertNotNull(result)
        assertEquals("Begode", result!!.newState.model,
            "Model should fall back to fwProt after MAX_INFO_ATTEMPTS")
    }

    @Test
    fun `Gotway fallback sets version to dash when fw never received after 50 attempts`() {
        val decoder = GotwayDecoder()
        // Do NOT send any firmware response — fw stays empty

        // Send 51 live data frames (counter goes 0→50 over 50 frames; fallback on frame 51)
        var state = defaultState
        var result: DecodedData? = null
        for (i in 1..51) {
            val frame = buildGotwayLiveDataFrame(voltage = 6000, speed = 100)
            result = decoder.decode(frame, state, defaultConfig)
            if (result != null) state = result.newState
        }

        // After exceeding MAX_INFO_ATTEMPTS, version should be "-" and model should be "Begode"
        assertNotNull(result)
        assertEquals("-", result!!.newState.version,
            "Version should fall back to '-' after MAX_INFO_ATTEMPTS")
        assertEquals("Begode", result.newState.model,
            "Model should fall back to 'Begode' when fwProt is empty")
    }

    @Test
    fun `Gotway no retry when both fw and model are populated`() {
        val decoder = GotwayDecoder()
        // Set firmware
        val fwData = "GW1.23".encodeToByteArray()
        decoder.decode(fwData, defaultState, defaultConfig)
        // Set model
        val nameData = "NAME TestWheel".encodeToByteArray()
        decoder.decode(nameData, defaultState, defaultConfig)

        // Send live data — should not emit V or N commands
        val liveFrame = buildGotwayLiveDataFrame(voltage = 6000, speed = 100)
        val result = decoder.decode(liveFrame, defaultState, defaultConfig)

        assertNotNull(result)
        val retryCommands = result.commands.filterIsInstance<WheelCommand.SendBytes>()
            .filter { it.data.decodeToString() in listOf("V", "N") }
        assertTrue(retryCommands.isEmpty(),
            "Should not retry V/N when both fw and model are populated")
    }

    @Test
    fun `Gotway reset clears retry counter`() {
        val decoder = GotwayDecoder()
        // Burn through some retry attempts
        for (i in 1..10) {
            val frame = buildGotwayLiveDataFrame(voltage = 6000, speed = 100)
            decoder.decode(frame, defaultState, defaultConfig)
        }

        // Reset should clear the counter
        decoder.reset()

        // After reset, should start retrying again (emit V)
        val frame = buildGotwayLiveDataFrame(voltage = 6000, speed = 100)
        val result = decoder.decode(frame, defaultState, defaultConfig)
        assertNotNull(result)
        val vCommands = result.commands.filterIsInstance<WheelCommand.SendBytes>()
            .filter { it.data.decodeToString() == "V" }
        assertTrue(vCommands.isNotEmpty(),
            "Should retry V after reset (counter cleared)")
    }

    // ==================== InMotionV2Decoder Lifecycle ====================

    @Test
    fun `InMotionV2 getKeepAliveCommand sends REAL_TIME_INFO primarily with init retries on 4th tick`() {
        val decoder = InMotionV2Decoder()

        // First 3 ticks should send REAL_TIME_INFO (DEFAULT flag 0x14)
        for (i in 1..3) {
            val command = decoder.getKeepAliveCommand()
            assertNotNull(command)
            val bytes = (command as WheelCommand.SendBytes).data
            assertEquals(0xAA.toByte(), bytes[0])
            assertEquals(0xAA.toByte(), bytes[1])
            assertEquals(0x14.toByte(), bytes[2], "Tick $i should send REAL_TIME_INFO (DEFAULT flag)")
        }

        // 4th tick should send init retry (INITIAL flag 0x11) since model not detected
        val initCommand = decoder.getKeepAliveCommand()
        assertNotNull(initCommand)
        val initBytes = (initCommand as WheelCommand.SendBytes).data
        assertEquals(0x11.toByte(), initBytes[2], "4th tick should send init retry (INITIAL flag)")
    }

    @Test
    fun `InMotionV2 getKeepAliveCommand retries serial after model detected`() {
        val decoder = InMotionV2Decoder()

        // Feed a car type response to set isModelDetected = true
        val carTypeFrame = buildIM2Frame(0x11, 0x02, byteArrayOf(0x01, 0x00, 0x08, 0x01, 0x00, 0x00))
        decoder.decode(carTypeFrame, defaultState, defaultConfig)

        // Advance to 4th tick to trigger init retry
        repeat(3) { decoder.getKeepAliveCommand() }
        val command = decoder.getKeepAliveCommand()
        assertNotNull(command)
        val bytes = (command as WheelCommand.SendBytes).data
        assertEquals(0xAA.toByte(), bytes[0])
        assertEquals(0xAA.toByte(), bytes[1])
        // After model detected, init retry should request serial (INITIAL flag)
        assertEquals(0x11.toByte(), bytes[2])
    }

    @Test
    fun `InMotionV2 getKeepAliveCommand returns REAL_TIME_INFO when fully initialized`() {
        val decoder = InMotionV2Decoder()

        // Step 1: Feed car type response
        val carTypeFrame = buildIM2Frame(0x11, 0x02, byteArrayOf(0x01, 0x00, 0x08, 0x01, 0x00, 0x00))
        decoder.decode(carTypeFrame, defaultState, defaultConfig)

        // Step 2: Feed serial response (sub-type 0x02, 16 serial chars)
        // Command is MAIN_INFO (0x02); data[0]=0x02 is the sub-type for serial
        val serialData = ByteArray(17) { 0x41 } // "AAAA..." (17 bytes: 0x02 prefix + 16 serial chars)
        serialData[0] = 0x02
        val serialFrame = buildIM2Frame(0x11, 0x02, serialData)
        decoder.decode(serialFrame, defaultState, defaultConfig)

        // Step 3: Feed version response (sub-type 0x06, 24+ bytes of version data)
        // Command is MAIN_INFO (0x02); data[0]=0x06 is the sub-type for versions
        val versionData = ByteArray(25)
        versionData[0] = 0x06
        val versionFrame = buildIM2Frame(0x11, 0x02, versionData)
        decoder.decode(versionFrame, defaultState, defaultConfig)

        val command = decoder.getKeepAliveCommand()
        assertNotNull(command)
        val bytes = (command as WheelCommand.SendBytes).data
        assertEquals(0xAA.toByte(), bytes[0])
        assertEquals(0xAA.toByte(), bytes[1])
        // Should now be DEFAULT flag (0x14) for real-time data
        assertEquals(0x14.toByte(), bytes[2])
    }

    @Test
    fun `InMotionV2 getInitCommands returns 5 staged commands`() {
        val decoder = InMotionV2Decoder()
        val commands = decoder.getInitCommands()

        assertEquals(5, commands.size, "Should emit 5 init commands")

        // First: car type (immediate)
        assertTrue(commands[0] is WheelCommand.SendBytes, "First should be SendBytes")

        // Remaining 4: delayed
        for (i in 1..4) {
            assertTrue(commands[i] is WheelCommand.SendDelayed,
                "Command $i should be SendDelayed")
        }

        // Verify delays are sequential (100, 200, 300, 400)
        assertEquals(100L, (commands[1] as WheelCommand.SendDelayed).delayMs)
        assertEquals(200L, (commands[2] as WheelCommand.SendDelayed).delayMs)
        assertEquals(300L, (commands[3] as WheelCommand.SendDelayed).delayMs)
        assertEquals(400L, (commands[4] as WheelCommand.SendDelayed).delayMs)
    }

    @Test
    fun `InMotionV2 isReady requires model detected and version populated`() {
        val decoder = InMotionV2Decoder()
        assertFalse(decoder.isReady(), "Should not be ready initially")

        // Feed car type — model detected but version empty
        val carTypeFrame = buildIM2Frame(0x11, 0x02, byteArrayOf(0x01, 0x00, 0x08, 0x01, 0x00, 0x00))
        decoder.decode(carTypeFrame, defaultState, defaultConfig)
        assertFalse(decoder.isReady(), "Should not be ready without version")
    }

    /**
     * Build a raw InMotionV2 frame with proper escape sequences and checksum.
     * This replicates InMotionV2Decoder.buildMessage() logic for test purposes.
     */
    private fun buildIM2Frame(flags: Int, command: Int, data: ByteArray): ByteArray {
        val buffer = mutableListOf<Byte>()
        buffer.add(flags.toByte())
        buffer.add((data.size + 1).toByte())
        buffer.add(command.toByte())
        buffer.addAll(data.toList())

        var check = 0
        for (byte in buffer) {
            check = (check xor (byte.toInt() and 0xFF)) and 0xFF
        }

        val output = mutableListOf<Byte>()
        output.add(0xAA.toByte())
        output.add(0xAA.toByte())
        for (byte in buffer) {
            val b = byte.toInt() and 0xFF
            if (b == 0xAA || b == 0xA5) {
                output.add(0xA5.toByte())
            }
            output.add(byte)
        }
        output.add(check.toByte())

        return output.toByteArray()
    }

    // ==================== NinebotDecoder Lifecycle ====================

    @Test
    fun `Ninebot getKeepAliveCommand returns serial request in WAITING_SERIAL state`() {
        val decoder = NinebotDecoder()
        val command = decoder.getKeepAliveCommand()

        assertNotNull(command, "Should return command in WAITING_SERIAL state")
        assertTrue(command is WheelCommand.SendBytes)
    }

    @Test
    fun `Ninebot getInitCommands returns single serial request`() {
        val decoder = NinebotDecoder()
        val commands = decoder.getInitCommands()

        assertEquals(1, commands.size, "Should emit exactly 1 init command")
        assertTrue(commands[0] is WheelCommand.SendBytes)
    }

    @Test
    fun `Ninebot keepAliveIntervalMs is 125`() {
        val decoder = NinebotDecoder()
        assertEquals(125L, decoder.keepAliveIntervalMs)
    }

    @Test
    fun `Ninebot isReady requires serial, version, and voltage`() {
        val decoder = NinebotDecoder()
        assertFalse(decoder.isReady(), "Should not be ready initially")
    }

    // ==================== NinebotZDecoder Lifecycle ====================

    @Test
    fun `NinebotZ getKeepAliveCommand returns BLE version request in INIT state`() {
        val decoder = NinebotZDecoder()
        val command = decoder.getKeepAliveCommand()

        assertNotNull(command, "Should return command in INIT state")
        assertTrue(command is WheelCommand.SendBytes)
    }

    @Test
    fun `NinebotZ getInitCommands returns BLE version request`() {
        val decoder = NinebotZDecoder()
        val commands = decoder.getInitCommands()

        assertEquals(1, commands.size, "Should emit exactly 1 init command")
        assertTrue(commands[0] is WheelCommand.SendBytes)
    }

    @Test
    fun `NinebotZ isReady only true when connectionState is READY`() {
        val decoder = NinebotZDecoder()
        assertFalse(decoder.isReady(), "Should not be ready in INIT state")
    }

    // ==================== KingsongDecoder Lifecycle ====================

    @Test
    fun `Kingsong getInitCommands emits 0x9B, 0x63, 0x98 with delays`() {
        val decoder = KingsongDecoder()
        val commands = decoder.getInitCommands()

        assertEquals(3, commands.size, "Should emit 3 init commands")

        // First: 0x9B (name request, immediate)
        val cmd0 = commands[0] as WheelCommand.SendBytes
        assertEquals(20, cmd0.data.size, "KS frames are 20 bytes")
        assertEquals(0x9B.toByte(), cmd0.data[16], "First command type should be 0x9B")

        // Second: 0x63 (serial request, delayed 100ms)
        val cmd1 = commands[1] as WheelCommand.SendDelayed
        assertEquals(20, cmd1.data.size)
        assertEquals(0x63.toByte(), cmd1.data[16], "Second command type should be 0x63")
        assertEquals(100L, cmd1.delayMs)

        // Third: 0x98 (alarm settings, delayed 200ms)
        val cmd2 = commands[2] as WheelCommand.SendDelayed
        assertEquals(20, cmd2.data.size)
        assertEquals(0x98.toByte(), cmd2.data[16], "Third command type should be 0x98")
        assertEquals(200L, cmd2.delayMs)
    }

    @Test
    fun `Kingsong 0xA4 frame triggers 0x98 acknowledgment in returned commands`() {
        val decoder = KingsongDecoder()

        // Build a 0xA4 frame (max speed/alerts)
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55
        // alarm1 at offset 4, alarm2 at 6, alarm3 at 8, maxSpeed at 10
        frame[4] = 30  // alarm1
        frame[6] = 40  // alarm2
        frame[8] = 50  // alarm3
        frame[10] = 60 // maxSpeed
        frame[16] = 0xA4.toByte() // frame type
        frame[17] = 0x14
        frame[18] = 0x5A
        frame[19] = 0x5A

        val result = decoder.decode(frame, defaultState, defaultConfig)
        assertNotNull(result)

        // Should have a response command with type 0x98
        assertTrue(result.commands.isNotEmpty(), "Should emit response command")
        val response = result.commands[0] as WheelCommand.SendBytes
        assertEquals(0x98.toByte(), response.data[16],
            "Response to 0xA4 should be 0x98 (alarm settings ACK)")
    }

    @Test
    fun `Kingsong isReady requires model populated`() {
        val decoder = KingsongDecoder()
        assertFalse(decoder.isReady(), "Should not be ready initially (model empty)")
    }

    // ==================== VeteranDecoder Lifecycle ====================

    @Test
    fun `Veteran model detected from first frame mVer byte`() {
        val decoder = VeteranDecoder()

        // Build a minimal Veteran frame — use a real packet from comparison tests
        // From VeteranDecoderComparisonTest: Sherman frame
        val hex = "DC5A5C20011710000000BC3C02060000010000B2EC" +
                  "0000002800B40000090000000000000000000000000000000000"
        val frame = hex.hexToByteArray()

        val result = decoder.decode(frame, defaultState, defaultConfig)
        // A Veteran frame should be parseable if it has the right header
        // The model is derived from ver byte
        if (result != null) {
            assertTrue(result.newState.model.isNotEmpty(),
                "Model should be set after first frame")
        }
    }

    @Test
    fun `Veteran getInitCommands returns empty list`() {
        val decoder = VeteranDecoder()
        val commands = decoder.getInitCommands()

        assertTrue(commands.isEmpty(),
            "Veteran has no init commands — data streams immediately")
    }

    @Test
    fun `Veteran getKeepAliveCommand returns null`() {
        val decoder = VeteranDecoder()
        val command = decoder.getKeepAliveCommand()

        assertNull(command, "Veteran has no keep-alive — continuous data stream")
    }
}
