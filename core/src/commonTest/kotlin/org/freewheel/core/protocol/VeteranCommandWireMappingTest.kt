package org.freewheel.core.protocol

import org.freewheel.core.domain.WheelSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Source-of-truth for the Veteran command set's wire-vs-user value convention.
 *
 * FreeWheel's Veteran [WheelCommand]s have **mixed conventions** for what the
 * `value` field means. This test asserts each command's contract explicitly so
 * a future contributor can read it as executable documentation, and so an
 * accidental flip (e.g. moving an offset from the decoder into the ViewModel
 * or vice-versa) lands red.
 *
 *  - "User-facing" commands: caller passes the user-friendly number (km/h,
 *    degrees, °C). The decoder applies the wire offset internally before
 *    emitting bytes. Slider ControlSpec ranges match user space.
 *
 *  - "Wire passthrough" commands: caller passes the literal wire byte the
 *    wheel expects (typically because the displayed value matches the wire
 *    range, e.g. 80–125 % brake pressure). Slider ControlSpec ranges match
 *    wire space. UI code is responsible for any human-friendly relabeling.
 *
 * Cross-reference: the official Leaperkim app's per-setting
 * `progressToCmdValue(i)` lives in
 * `freewheel-data/euc-reference-apps/leaperkim/jadx_out/sources/com/laoniao/leaperkim/setting/control/`
 * (one `*SettingActivity.java` per slider).
 */
class VeteranCommandWireMappingTest {

    private fun stateWith(mVer: Int) = DecoderState(
        settings = WheelSettings.Veteran(mVer = mVer)
    )

    private fun emit(cmd: WheelCommand): ByteArray {
        val decoder = VeteranDecoder()
        val out = decoder.buildCommand(cmd, stateWith(mVer = 5))
        return (out.first() as WheelCommand.SendBytes).data
    }

    /** Byte at index [pos] in the un-CRC payload of [bytes]. */
    private fun valueAt(bytes: ByteArray, pos: Int): Int = bytes[pos].toInt() and 0xFF

    // ==================== User-facing commands (decoder applies offset) ====================

    @Test
    fun `SetAlarmSpeed encodes user kmh as wire kmh+10`() {
        // SetAlarmSpeedActivity.java sends `userSpeed + 10`. Caller passes user kmh.
        assertEquals(60, valueAt(emit(WheelCommand.SetAlarmSpeed(50, 1)), 12), "wire = user+10")
        assertEquals(40, valueAt(emit(WheelCommand.SetAlarmSpeed(30, 1)), 12))
        assertEquals(20, valueAt(emit(WheelCommand.SetAlarmSpeed(10, 1)), 12), "wire-min = user-min+10")
    }

    @Test
    fun `SetPedalTilt encodes user degrees as wire degrees+80`() {
        // SetAngelActivity.java sends `userAngle + 80`. Caller passes user degrees
        // (slider -8..+8 per WheelSettingsConfig).
        assertEquals(80, valueAt(emit(WheelCommand.SetPedalTilt(0)), 11), "wire = user+80")
        assertEquals(88, valueAt(emit(WheelCommand.SetPedalTilt(8)), 11), "wire-max = +8 user + 80")
        assertEquals(72, valueAt(emit(WheelCommand.SetPedalTilt(-8)), 11), "wire-min = -8 user + 80")
    }

    // ==================== Wire-passthrough commands (caller supplies wire) ====================

    @Test
    fun `SetBrakePressureAlarm passes wire byte through unchanged`() {
        // BrakeSettingActivity.java sends `i + 80` (slider 0..45 → wire 80..125).
        // FreeWheel's ControlSpec slider already uses wire range 80..125, so the
        // i+80 offset belongs at the (future) UI layer, not the decoder.
        assertEquals(80, valueAt(emit(WheelCommand.SetBrakePressureAlarm(80)), 29), "wire-min unchanged")
        assertEquals(125, valueAt(emit(WheelCommand.SetBrakePressureAlarm(125)), 29), "wire-max unchanged")
        assertEquals(110, valueAt(emit(WheelCommand.SetBrakePressureAlarm(110)), 29))
    }

    @Test
    fun `SetVeteranPwmLimit passes wire byte through unchanged`() {
        // StopPowerSettingActivity.java sends `i + 30` (slider 0..70 → wire 30..100).
        // FreeWheel passes wire range 30..100 directly.
        assertEquals(30, valueAt(emit(WheelCommand.SetVeteranPwmLimit(30)), 13), "wire-min unchanged")
        assertEquals(100, valueAt(emit(WheelCommand.SetVeteranPwmLimit(100)), 13), "wire-max unchanged")
    }

    @Test
    fun `SetStopSpeed passes wire byte through unchanged`() {
        // StopSpeedSettingActivity.java sends `i + 10` (slider 0..110 → wire 10..120).
        // FreeWheel passes wire range 10..120 directly.
        assertEquals(10, valueAt(emit(WheelCommand.SetStopSpeed(10)), 12), "wire-min unchanged")
        assertEquals(120, valueAt(emit(WheelCommand.SetStopSpeed(120)), 12), "wire-max unchanged")
    }

    @Test
    fun `SetVoltageCorrection passes raw signed wire byte`() {
        // VolLightSettingActivity.java sends `i - 15` (slider 0..30 → wire -15..15 signed).
        // FreeWheel uses signed wire range -15..+15 directly; signed encoding is
        // critical (the slider stores negative values).
        assertEquals(0, valueAt(emit(WheelCommand.SetVoltageCorrection(0)), 19))
        assertEquals(15, valueAt(emit(WheelCommand.SetVoltageCorrection(15)), 19), "positive cap")
        // -15 as a signed byte is 0xF1 in two's complement (255 - 14 = 241).
        assertEquals(0xF1, valueAt(emit(WheelCommand.SetVoltageCorrection(-15)), 19), "negative cap as signed byte")
    }

    @Test
    fun `SetKeyTone SetScreenBacklight SetMaxChargeVoltage SetDynamicAssist SetAccelerationLimit pass wire bytes through unchanged`() {
        // KeyTone, ScreenBacklight, MaxChargePower, SetUpDownSpwwd, SetUpSpeedCul:
        // all use `progressToCmdValue(i) = (byte) i` in the app. FreeWheel passes
        // through directly.
        assertEquals(50, valueAt(emit(WheelCommand.SetKeyTone(50)), 23))
        assertEquals(75, valueAt(emit(WheelCommand.SetScreenBacklight(75)), 15))
        assertEquals(100, valueAt(emit(WheelCommand.SetMaxChargeVoltage(100)), 24))
        assertEquals(60, valueAt(emit(WheelCommand.SetDynamicAssist(60)), 26))
        assertEquals(40, valueAt(emit(WheelCommand.SetAccelerationLimit(40)), 28))
    }
}
