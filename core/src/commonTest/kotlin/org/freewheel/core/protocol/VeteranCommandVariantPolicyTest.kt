package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelSettings
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Variant-policy parity test for [VeteranDecoder].
 *
 * Verifies that [VeteranDecoder.buildCommand] emits exactly the same payload
 * bytes as the official Leaperkim app (`com.laoniao.leaperkim` v1.4.8), and
 * uses the same single/dual-emit policy:
 *
 *  - "Dual-emit" commands go through `BtManager.sendBytesDataCombine(LkAp, LdAp)`
 *    and we must emit BOTH variants.
 *  - "Single-emit LdAp-only" commands go through `BtManager.sendBytesData()`
 *    with the new prefix only — we must emit JUST the LdAp variant.
 *  - "Single-emit ASCII" commands (legacy `strCmdMode` wheels, mVer < 3)
 *    emit a plain ASCII string with no framing.
 *
 * Fixtures live in [LeaperkimAppCommands].
 */
class VeteranCommandVariantPolicyTest {

    private val config = DecoderConfig()

    private fun stateWith(mVer: Int) = DecoderState(
        settings = WheelSettings.Veteran(mVer = mVer)
    )

    private fun bytesOf(cmd: WheelCommand): ByteArray =
        (cmd as WheelCommand.SendBytes).data

    private fun assertCrcValid(bytes: ByteArray, label: String) {
        val payloadSize = bytes.size - 4
        val computed = veteranCrc32(bytes, 0, payloadSize)
        val provided = ((bytes[payloadSize].toLong() and 0xFF) shl 24) or
                ((bytes[payloadSize + 1].toLong() and 0xFF) shl 16) or
                ((bytes[payloadSize + 2].toLong() and 0xFF) shl 8) or
                (bytes[payloadSize + 3].toLong() and 0xFF)
        assertEquals(computed, provided, "$label: CRC32 mismatch")
    }

    private fun assertMatchesFixture(emitted: WheelCommand, fixture: ByteArray, label: String) {
        val bytes = bytesOf(emitted)
        assertCrcValid(bytes, label)
        val payload = LeaperkimAppCommands.stripCrc(bytes)
        assertContentEquals(
            fixture,
            payload,
            "$label: payload bytes diverge from official Leaperkim app"
        )
    }

    // ==================== Dual-emit policy (legacy + new on mVer >= 3) ====================
    // BtManager.sendBytesDataCombine(LkAp, LdAp) → 2 commands per call.

    @Test
    fun `Beep at mVer 5 emits LkAp + LdAp pair matching app`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.Beep, stateWith(mVer = 5))
        assertEquals(2, cmds.size, "BtManager.sendBytesDataCombine(OLDCMDB, OLDCMDB_NEW)")
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.BEEP_LKAP, "Beep LkAp")
        assertMatchesFixture(cmds[1], LeaperkimAppCommands.BEEP_LDAP, "Beep LdAp")
    }

    @Test
    fun `Beep at mVer 0 emits single ASCII 'b'`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.Beep, stateWith(mVer = 0))
        assertEquals(1, cmds.size)
        assertEquals("b", bytesOf(cmds[0]).decodeToString())
    }

    @Test
    fun `SetLight on at mVer 5 emits LkAp + LdAp pair matching app`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.SetLight(enabled = true), stateWith(mVer = 5))
        assertEquals(2, cmds.size, "BtManager.sendBytesDataCombine(SET_LIGHT_ON, SET_LIGHT_ON_NEW)")
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.LIGHT_ON_LKAP, "SetLight ON LkAp")
        assertMatchesFixture(cmds[1], LeaperkimAppCommands.LIGHT_ON_LDAP, "SetLight ON LdAp")
    }

    @Test
    fun `SetLight off at mVer 5 emits LkAp + LdAp pair matching app`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.SetLight(enabled = false), stateWith(mVer = 5))
        assertEquals(2, cmds.size)
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.LIGHT_OFF_LKAP, "SetLight OFF LkAp")
        assertMatchesFixture(cmds[1], LeaperkimAppCommands.LIGHT_OFF_LDAP, "SetLight OFF LdAp")
    }

    @Test
    fun `SetLight at mVer 0 emits ASCII 'SetLightON' or 'SetLightOFF'`() {
        val decoder = VeteranDecoder()
        val on = decoder.buildCommand(WheelCommand.SetLight(true), stateWith(mVer = 0))
        val off = decoder.buildCommand(WheelCommand.SetLight(false), stateWith(mVer = 0))
        assertEquals(1, on.size)
        assertEquals(1, off.size)
        assertEquals("SetLightON", bytesOf(on[0]).decodeToString())
        assertEquals("SetLightOFF", bytesOf(off[0]).decodeToString())
    }

    @Test
    fun `SetPedalsMode at mVer 5 emits LkAp + LdAp pair matching app`() {
        val decoder = VeteranDecoder()
        // mode 2 (soft) → value 1 (SETs)
        val soft = decoder.buildCommand(WheelCommand.SetPedalsMode(mode = 2), stateWith(mVer = 5))
        assertEquals(2, soft.size)
        assertMatchesFixture(soft[0], LeaperkimAppCommands.SETS_LKAP, "SetPedalsMode soft LkAp")
        assertMatchesFixture(soft[1], LeaperkimAppCommands.SETS_LDAP, "SetPedalsMode soft LdAp")

        val medium = decoder.buildCommand(WheelCommand.SetPedalsMode(mode = 1), stateWith(mVer = 5))
        assertMatchesFixture(medium[0], LeaperkimAppCommands.SETM_LKAP, "SetPedalsMode medium LkAp")
        assertMatchesFixture(medium[1], LeaperkimAppCommands.SETM_LDAP, "SetPedalsMode medium LdAp")

        val hard = decoder.buildCommand(WheelCommand.SetPedalsMode(mode = 0), stateWith(mVer = 5))
        assertMatchesFixture(hard[0], LeaperkimAppCommands.SETH_LKAP, "SetPedalsMode hard LkAp")
        assertMatchesFixture(hard[1], LeaperkimAppCommands.SETH_LDAP, "SetPedalsMode hard LdAp")
    }

    @Test
    fun `SetPedalsMode at mVer 0 emits ASCII SETh SETm SETs`() {
        val decoder = VeteranDecoder()
        assertEquals("SETh", bytesOf(decoder.buildCommand(WheelCommand.SetPedalsMode(0), stateWith(0))[0]).decodeToString())
        assertEquals("SETm", bytesOf(decoder.buildCommand(WheelCommand.SetPedalsMode(1), stateWith(0))[0]).decodeToString())
        assertEquals("SETs", bytesOf(decoder.buildCommand(WheelCommand.SetPedalsMode(2), stateWith(0))[0]).decodeToString())
    }

    @Test
    fun `ResetTrip at mVer 5 emits LkAp + LdAp binary pair matching app`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.ResetTrip, stateWith(mVer = 5))
        assertEquals(2, cmds.size, "BtManager.sendBytesDataCombine(CLEAR_METER, CLEAR_METER_NEW)")
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.CLEAR_METER_LKAP, "ResetTrip LkAp")
        assertMatchesFixture(cmds[1], LeaperkimAppCommands.CLEAR_METER_LDAP, "ResetTrip LdAp")
    }

    @Test
    fun `ResetTrip at mVer 0 emits ASCII CLEARMETER`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.ResetTrip, stateWith(mVer = 0))
        assertEquals(1, cmds.size)
        assertEquals("CLEARMETER", bytesOf(cmds[0]).decodeToString())
    }

    @Test
    fun `PowerOff at mVer 5 emits LkAp + LdAp pair matching app`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.PowerOff, stateWith(mVer = 5))
        assertEquals(2, cmds.size, "BtManager.sendBytesDataCombine(SET_CLOSE_IN_10, SET_CLOSE_IN_10_NEW)")
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.POWER_OFF_LKAP, "PowerOff LkAp")
        assertMatchesFixture(cmds[1], LeaperkimAppCommands.POWER_OFF_LDAP, "PowerOff LdAp")
    }

    @Test
    fun `SetAlarmSpeed at mVer 5 emits LkAp + LdAp pair matching app`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.SetAlarmSpeed(speed = 50, num = 1), stateWith(mVer = 5))
        assertEquals(2, cmds.size)
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.ALARM_SPEED_50_LKAP, "SetAlarmSpeed LkAp")
        assertMatchesFixture(cmds[1], LeaperkimAppCommands.ALARM_SPEED_50_LDAP, "SetAlarmSpeed LdAp")
    }

    @Test
    fun `SetPedalTilt at mVer 5 emits LkAp + LdAp pair matching app`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.SetPedalTilt(angle = 0), stateWith(mVer = 5))
        assertEquals(2, cmds.size)
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.PEDAL_TILT_0_LKAP, "SetPedalTilt LkAp")
        assertMatchesFixture(cmds[1], LeaperkimAppCommands.PEDAL_TILT_0_LDAP, "SetPedalTilt LdAp")
    }

    @Test
    fun `SetLateralCutoffAngle at mVer 5 emits LkAp + LdAp pair matching app`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.SetLateralCutoffAngle(angle = 70), stateWith(mVer = 5))
        assertEquals(2, cmds.size)
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.LATERAL_CUTOFF_70_LKAP, "SetLateralCutoffAngle LkAp")
        assertMatchesFixture(cmds[1], LeaperkimAppCommands.LATERAL_CUTOFF_70_LDAP, "SetLateralCutoffAngle LdAp")
    }

    // ==================== Single-emit LdAp-only policy ====================
    // BtManager.sendBytesData(LdAp_bytes) — no paired LkAp. Sending one would
    // collide with other commands sharing the same cmd byte (e.g. transport
    // mode cmd 0x16 vs lateral cutoff cmd 0x16 — discriminated only by byte6).

    @Test
    fun `SetTransportMode emits ONLY LdAp byte6=2, no LkAp`() {
        val decoder = VeteranDecoder()
        val on = decoder.buildCommand(WheelCommand.SetTransportMode(enabled = true), stateWith(mVer = 5))
        assertEquals(1, on.size, "ControlActivity.java sends single LdAp; pairing with LkAp would collide with SetLateralCutoffAngle")
        assertMatchesFixture(on[0], LeaperkimAppCommands.TRANSPORT_ON_LDAP, "SetTransportMode ON")

        val off = decoder.buildCommand(WheelCommand.SetTransportMode(enabled = false), stateWith(mVer = 5))
        assertEquals(1, off.size)
        assertMatchesFixture(off[0], LeaperkimAppCommands.TRANSPORT_OFF_LDAP, "SetTransportMode OFF")
    }

    @Test
    fun `SetHighSpeedMode emits ONLY LdAp byte6=2, no LkAp`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.SetHighSpeedMode(enabled = true), stateWith(mVer = 5))
        assertEquals(1, cmds.size)
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.HIGH_SPEED_ON_LDAP, "SetHighSpeedMode")
    }

    @Test
    fun `SetLowVoltageMode emits ONLY LdAp byte6=2, no LkAp`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.SetLowVoltageMode(enabled = true), stateWith(mVer = 5))
        assertEquals(1, cmds.size)
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.LOW_VOLTAGE_ON_LDAP, "SetLowVoltageMode")
    }

    @Test
    fun `SetWheelDisplayUnit emits ONLY LdAp byte6=2, no LkAp`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.SetWheelDisplayUnit(miles = true), stateWith(mVer = 5))
        assertEquals(1, cmds.size)
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.WHEEL_UNIT_MILES_LDAP, "SetWheelDisplayUnit")
    }

    @Test
    fun `Calibrate emits ONLY LdAp byte6=2, no LkAp`() {
        val decoder = VeteranDecoder()
        val cmds = decoder.buildCommand(WheelCommand.Calibrate, stateWith(mVer = 5))
        assertEquals(1, cmds.size)
        assertMatchesFixture(cmds[0], LeaperkimAppCommands.CALIBRATE_LDAP, "Calibrate")
    }

    // ==================== Time-sync shape parity (Codex H1 refinement) ====================
    // Util.getTimeBytes() returns {0x4C 0x64 0x41 0x70 0x12 0x00 0x05 <7 dynamic bytes>} + CRC.
    // The timestamp bytes are dynamic (current clock), so we assert shape, not
    // a static byte fixture.

    @Test
    fun `time sync command matches getTimeBytes shape at mVer 5`() {
        val freshDecoder = VeteranDecoder()
        // Decoding a valid frame at mVer >= 3 should trigger time-sync emission.
        val frame = buildVeteranFrameFixture(mVer = 5)
        val result = freshDecoder.decode(frame, DecoderState(), config)
        assertTrue(result is DecodeResult.Success, "frame must decode")

        val syncCmds = (result as DecodeResult.Success).data.commands.filter { cmd ->
            val data = when (cmd) {
                is WheelCommand.SendBytes -> cmd.data
                is WheelCommand.SendDelayed -> cmd.data
                else -> return@filter false
            }
            data.size >= 7 &&
                data[0] == 0x4C.toByte() && data[1] == 0x64.toByte() &&
                data[2] == 0x41.toByte() && data[3] == 0x70.toByte() &&
                data[4] == 0x12.toByte() && data[5] == 0x00.toByte() && data[6] == 0x05.toByte()
        }
        assertEquals(2, syncCmds.size, "Util.syncTime sends time bytes twice (immediate + 2s delay)")

        // Both must be 18 bytes (14 payload + 4 CRC) and have valid CRC.
        for ((i, cmd) in syncCmds.withIndex()) {
            val data = when (cmd) {
                is WheelCommand.SendBytes -> cmd.data
                is WheelCommand.SendDelayed -> cmd.data
                else -> continue
            }
            assertEquals(18, data.size, "sync[$i] payload must be 14 bytes + 4 CRC")
            assertCrcValid(data, "time sync[$i]")

            // Dynamic timestamp bytes 7..13: year-2000, month, day, hour, min, sec, tz.
            val year = data[7].toInt() and 0xFF
            val month = data[8].toInt() and 0xFF
            val day = data[9].toInt() and 0xFF
            val hour = data[10].toInt() and 0xFF
            val minute = data[11].toInt() and 0xFF
            val second = data[12].toInt() and 0xFF
            val tz = data[13].toInt() // signed
            assertTrue(year in 25..99, "year-2000 byte should be 25..99, was $year")
            assertTrue(month in 1..12, "month should be 1..12, was $month")
            assertTrue(day in 1..31, "day should be 1..31, was $day")
            assertTrue(hour in 0..23, "hour should be 0..23, was $hour")
            assertTrue(minute in 0..59, "minute should be 0..59, was $minute")
            assertTrue(second in 0..59, "second should be 0..59, was $second")
            assertTrue(tz in -12..14, "tz hours should be -12..14, was $tz")
        }

        // Second emission must be SendDelayed @ 2000ms.
        val delayed = syncCmds.first { it is WheelCommand.SendDelayed } as WheelCommand.SendDelayed
        assertEquals(2000L, delayed.delayMs, "Util.syncTime resends after 2 seconds")
    }

    @Test
    fun `time sync ALSO emitted at mVer 0 (no firmware-version gate)`() {
        // BtManager.bluetoothHeatBeatOnce calls syncTime() unconditionally on
        // every received heartbeat — the app does not gate by firmware version.
        val freshDecoder = VeteranDecoder()
        val frame = buildVeteranFrameFixture(mVer = 1)
        val result = freshDecoder.decode(frame, DecoderState(), config)
        assertTrue(result is DecodeResult.Success)
        val syncCmds = (result as DecodeResult.Success).data.commands.filter { cmd ->
            val data = when (cmd) {
                is WheelCommand.SendBytes -> cmd.data
                is WheelCommand.SendDelayed -> cmd.data
                else -> return@filter false
            }
            data.size >= 5 && data[4] == 0x12.toByte()
        }
        assertEquals(2, syncCmds.size, "Time sync must be emitted at mVer 1 (matches app behavior)")
    }

    // ==================== Helpers ====================

    /**
     * Minimal 36-byte Veteran telemetry frame with valid unpacker rules and
     * the requested mVer. Length byte = 32, so no CRC32 trailer required.
     */
    private fun buildVeteranFrameFixture(mVer: Int): ByteArray {
        val frame = ByteArray(36)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A
        frame[2] = 0x5C
        frame[3] = 32 // length
        // bytes 22, 23, 30 must satisfy unpacker constraints (default 0 is fine).
        // Encode version = mVer * 1000 at bytes 28-29 (BE).
        val ver = mVer * 1000
        frame[28] = ((ver shr 8) and 0xFF).toByte()
        frame[29] = (ver and 0xFF).toByte()
        return frame
    }
}
