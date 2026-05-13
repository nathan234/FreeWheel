package org.freewheel.core.protocol

/**
 * Canonical command payloads extracted byte-for-byte from the official
 * Leaperkim Android app (`com.laoniao.leaperkim` v1.4.8, jadx-decompiled).
 *
 * These are the un-CRC'd payloads — the app appends CRC32 (4 bytes BE) via
 * [com.laoniao.leaperkim.utils.Util.crc32Encode] inside
 * [com.laoniao.leaperkim.utils.BtManager.sendBytesData] /
 * [com.laoniao.leaperkim.utils.BtManager.sendBytesDataCombine].
 *
 * To compare against [VeteranDecoder.buildCommand] output, strip the trailing
 * 4 CRC bytes via [stripCrc].
 *
 * Each entry cites the source location so future engineers can re-verify when
 * the decompiled app is refreshed:
 *   - utils/BtManager.java
 *   - setting/control/<Activity>.java (per-setting slider commands)
 *   - setting/<Activity>.java (top-level activities)
 *   - home/HomepageFragment.java
 */
internal object LeaperkimAppCommands {

    // ==================== Variant Prefixes ====================
    // "LkAp" = legacy 4-byte header used by older firmware
    // "LdAp" = new 4-byte header used by newer firmware
    // (76, 107, 65, 112) = (0x4C, 0x6B, 0x41, 0x70)
    // (76, 100, 65, 112) = (0x4C, 0x64, 0x41, 0x70)

    // ==================== Beep / OLDCMDb ====================
    // BtManager.java:73, 82 — sendBytesDataCombine(CMD_OLDCMDB, CMD_OLDCMDB_NEW)

    /** Beep / "OLDCMDb" legacy variant: cmd 0x0E, byte5=0, value=1 @9. */
    val BEEP_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x0E, 0x00,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x01
    )

    /** Beep new variant: cmd 0x0E, byte5=0, byte6=0, value=1 @9. */
    val BEEP_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x0E, 0x00, 0x00,
        0x80.toByte(), 0x80.toByte(),
        0x01
    )

    // ==================== SetLight ====================
    // BtManager.java:74-75, 83-84 — sendBytesDataCombine(CMD_SET_LIGHT_*, CMD_SET_LIGHT_*_NEW)

    /** SetLight ON legacy: cmd 0x0D, byte5=1, value=1 @8. */
    val LIGHT_ON_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x0D, 0x01,
        0x80.toByte(), 0x80.toByte(),
        0x01
    )

    /** SetLight ON new: cmd 0x0D, byte5=1, byte6=0, value=1 @8. */
    val LIGHT_ON_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x0D, 0x01, 0x00,
        0x80.toByte(),
        0x01
    )

    /** SetLight OFF legacy: cmd 0x0D, byte5=1, value=0 @8. */
    val LIGHT_OFF_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x0D, 0x01,
        0x80.toByte(), 0x80.toByte(),
        0x00
    )

    /** SetLight OFF new: cmd 0x0D, byte5=1, byte6=0, value=0 @8. */
    val LIGHT_OFF_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x0D, 0x01, 0x00,
        0x80.toByte(),
        0x00
    )

    // ==================== ResetTrip / CLEARMETER ====================
    // BtManager.java:76, 85 — sendBytesDataCombine(CMD_CLEAR_METER, CMD_CLEAR_METER_NEW)

    /** ResetTrip legacy: cmd 0x0D, byte5=0, value=1 @6. (7-byte payload, no padding.) */
    val CLEAR_METER_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x0D, 0x00,
        0x01
    )

    /** ResetTrip new: cmd 0x0D, byte5=0, byte6=0x02, value=1 @8. */
    val CLEAR_METER_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x0D, 0x00, 0x02,
        0x80.toByte(),
        0x01
    )

    // ==================== SetPedalsMode (discrete Soft/Medium/Hard) ====================
    // BtManager.java:77-79, 86-88 — sendBytesDataCombine(CMD_SETx, CMD_SETx_NEW)
    // Values: SETs=1 (soft), SETm=2 (medium), SETh=3 (hard)

    /** SETs legacy: cmd 0x0C, byte5=1, value=1 @7. */
    val SETS_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x0C, 0x01,
        0x80.toByte(),
        0x01
    )

    /** SETs new: cmd 0x0C, byte5=1, byte6=0, value=1 @7. */
    val SETS_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x0C, 0x01, 0x00,
        0x01
    )

    /** SETm legacy: cmd 0x0C, byte5=1, value=2 @7. */
    val SETM_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x0C, 0x01,
        0x80.toByte(),
        0x02
    )

    /** SETm new: cmd 0x0C, byte5=1, byte6=0, value=2 @7. */
    val SETM_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x0C, 0x01, 0x00,
        0x02
    )

    /** SETh legacy: cmd 0x0C, byte5=1, value=3 @7. */
    val SETH_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x0C, 0x01,
        0x80.toByte(),
        0x03
    )

    /** SETh new: cmd 0x0C, byte5=1, byte6=0, value=3 @7. */
    val SETH_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x0C, 0x01, 0x00,
        0x03
    )

    // ==================== PowerOff / CMD_SET_CLOSE_IN_10 ====================
    // BtManager.java:81, 90 — sendBytesDataCombine(CMD_SET_CLOSE_IN_10, CMD_SET_CLOSE_IN_10_NEW)
    // 18-byte payload with value=1 @16 and a trailing 0x80 @17.

    val POWER_OFF_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x16, 0x01,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(),
        0x01,
        0x80.toByte()
    )

    val POWER_OFF_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x16, 0x01, 0x00,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(),
        0x01,
        0x80.toByte()
    )

    // ==================== SetAlarmSpeed ====================
    // setting/SetAlarmSpeedActivity.java:67 — sendBytesDataCombine(LkAp, LdAp)
    // Wire value = userSpeed + 10. Examples encode userSpeed = 50 → wire 60 (0x3C).

    /** SetAlarmSpeed(50) legacy: cmd 0x11, byte5=1, value=60 @12. */
    val ALARM_SPEED_50_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x11, 0x01,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(),
        60.toByte()
    )

    /** SetAlarmSpeed(50) new: cmd 0x11, byte5=1, byte6=0, value=60 @12. */
    val ALARM_SPEED_50_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x11, 0x01, 0x00,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(),
        60.toByte()
    )

    // ==================== SetPedalTilt ====================
    // setting/SetAngelActivity.java:69 — sendBytesDataCombine(LkAp, LdAp)
    // Wire value = userAngle + 80. userAngle=0 → wire 80 (0x50).

    /** SetPedalTilt(0) legacy: cmd 0x10, byte5=1, value=80 @11. */
    val PEDAL_TILT_0_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x10, 0x01,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(),
        80.toByte()
    )

    /** SetPedalTilt(0) new: cmd 0x10, byte5=1, byte6=0, value=80 @11. */
    val PEDAL_TILT_0_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x10, 0x01, 0x00,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        80.toByte()
    )

    // ==================== SetLateralCutoffAngle / FallProtection ====================
    // setting/SetFallProtectionAngleActivity.java:64 — sendBytesDataCombine(LkAp, LdAp)
    // userAngle=70 → wire 70 (0x46), no offset. Value @17. Distinguishes from
    // SetTransportMode by byte6 (0 here vs 2 there).

    val LATERAL_CUTOFF_70_LKAP = byteArrayOf(
        0x4C, 0x6B, 0x41, 0x70,
        0x16, 0x01,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        70.toByte()
    )

    val LATERAL_CUTOFF_70_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x16, 0x01, 0x00,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(),
        70.toByte()
    )

    // ==================== Toggle Settings (LdAp-only, byte6=0x02) ====================
    // setting/ControlActivity.java — these toggles are sent ONLY in LdAp form,
    // never LkAp. The app uses sendBytesData() (single send), not
    // sendBytesDataCombine() (paired send).

    /** SetTransportMode(ON): ControlActivity.java:439, cmd 0x16, byte6=2, value=1 @17. */
    val TRANSPORT_ON_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x16, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(),
        0x01
    )

    /** SetTransportMode(OFF): value=0. */
    val TRANSPORT_OFF_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x16, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(),
        0x00
    )

    /** SetWheelDisplayUnit(miles=true): ControlActivity.java:443, cmd 0x17, byte6=2, value @18. */
    val WHEEL_UNIT_MILES_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x17, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x01
    )

    /** SetLowVoltageMode(ON): ControlActivity.java:447, cmd 0x19, byte6=2, value @20. */
    val LOW_VOLTAGE_ON_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x19, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(),
        0x01
    )

    /** SetHighSpeedMode(ON): ControlActivity.java:451, cmd 0x1A, byte6=2, value @21. */
    val HIGH_SPEED_ON_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x1A, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(),
        0x01
    )

    // ==================== Calibrate / Gyroscope ====================
    // setting/control/GyroscopeSettingActivity.java:122 — sendBytesData() (single LdAp)
    // Sends value=1 to toggle calibration start/end. cmd 0x15, byte6=2, value @16.

    val CALIBRATE_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x15, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(),
        0x01
    )

    // ==================== Per-slider LdAp-only control settings ====================
    // Each setting/control/<Name>SettingActivity.java sends a single LdAp packet
    // via BtManager.sendBytesData(). progressToCmdValue() controls user-to-wire
    // encoding (raw passthrough for most; +80 offset for brake pressure).

    /** SetPedalHardness(50): PedalSoftnessSettingActivity.java:37 — cmd 0x0F, byte6=2, value @10. */
    val PEDAL_HARDNESS_50_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x0F, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        50.toByte()
    )

    /** SetScreenBacklight(50): ScreenBacklightSettingActivity.java:30 — cmd 0x14, byte6=2, value @15. */
    val SCREEN_BACKLIGHT_50_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x14, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        50.toByte()
    )

    /** SetKeyTone(50): KeyToneSettingActivity.java:30 — cmd 0x1C, byte6=2, value @23. */
    val KEY_TONE_50_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x1C, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        50.toByte()
    )

    /** SetMaxChargeVoltage(100): MaxChargePowerSettingActivity.java:31 — cmd 0x1D, byte6=2, value @24. */
    val MAX_CHARGE_VOLTAGE_100_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x1D, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(),
        100.toByte()
    )

    /**
     * SetBrakePressureAlarm(110): BrakeSettingActivity.java:30 — cmd 0x22, byte6=2,
     * value @29. App encodes user input as `i + 80`; raw wire 110 corresponds to
     * user-slider 30.
     */
    val BRAKE_PRESSURE_110_LDAP = byteArrayOf(
        0x4C, 0x64, 0x41, 0x70,
        0x22, 0x01, 0x02,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
        0x80.toByte(), 0x80.toByte(),
        110.toByte()
    )

    // ==================== Helpers ====================

    /** Drop the trailing 4-byte CRC32 to obtain the un-CRC'd payload. */
    fun stripCrc(bytes: ByteArray): ByteArray = bytes.copyOf(bytes.size - 4)
}
