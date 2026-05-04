package org.freewheel.core.domain

/**
 * Reads decoder-protocol tuning values from a [KeyValueStore]. These are not
 * user-facing settings (they don't appear in the settings screen) — they're
 * per-wheel-type configuration the legacy AppConfig stored alongside ordinary
 * preferences. Kept distinct from [AppSettingsStore] so [AppSettingId] can
 * stay the source of truth for the settings UI.
 *
 * Per-wheel values are scoped by the same `last_mac` key [AppSettingsStore]
 * uses; the snapshot reflects whichever wheel is currently connected.
 */
class DecoderConfigStore(private val store: KeyValueStore) {

    fun getCustomPercents(): Boolean =
        store.getBool(PreferenceKeys.CUSTOM_PERCENTS, PreferenceDefaults.CUSTOM_PERCENTS)

    fun getCellVoltageTiltback(): Int =
        store.getInt(scoped(PreferenceKeys.CELL_VOLTAGE_TILTBACK), PreferenceDefaults.CELL_VOLTAGE_TILTBACK)

    fun getRotationSpeed(): Int =
        store.getInt(scoped(PreferenceKeys.ROTATION_SPEED), PreferenceDefaults.ROTATION_SPEED)

    fun getRotationVoltage(): Int =
        store.getInt(scoped(PreferenceKeys.ROTATION_VOLTAGE), PreferenceDefaults.ROTATION_VOLTAGE)

    fun getPowerFactor(): Int =
        store.getInt(scoped(PreferenceKeys.POWER_FACTOR), PreferenceDefaults.POWER_FACTOR)

    fun getBatteryCapacity(): Int =
        store.getInt(scoped(PreferenceKeys.BATTERY_CAPACITY), PreferenceDefaults.BATTERY_CAPACITY)

    fun getUseRatio(): Boolean =
        store.getBool(scoped(PreferenceKeys.USE_RATIO), PreferenceDefaults.USE_RATIO)

    fun getHwPwm(): Boolean =
        store.getBool(scoped(PreferenceKeys.HW_PWM), PreferenceDefaults.HW_PWM)

    fun getAutoVoltage(): Boolean =
        store.getBool(scoped(PreferenceKeys.AUTO_VOLTAGE), PreferenceDefaults.AUTO_VOLTAGE)

    fun getKs18LScaler(): Boolean =
        store.getBool(scoped(PreferenceKeys.KS18L_SCALER), PreferenceDefaults.KS18L_SCALER)

    /** Stored as a string by the legacy ListPreference; parsed to int for decoder use. */
    fun getGotwayNegative(): Int =
        (store.getString(scoped(PreferenceKeys.GOTWAY_NEGATIVE), PreferenceDefaults.GOTWAY_NEGATIVE)
            ?: PreferenceDefaults.GOTWAY_NEGATIVE).toIntOrNull() ?: 0

    /** Stored as a string by the legacy ListPreference; parsed to int for decoder use. */
    fun getGotwayVoltage(): Int =
        (store.getString(scoped(PreferenceKeys.GOTWAY_VOLTAGE), PreferenceDefaults.GOTWAY_VOLTAGE)
            ?: PreferenceDefaults.GOTWAY_VOLTAGE).toIntOrNull() ?: 0

    /**
     * Reads the legacy per-wheel pairing password used by Inmotion/Ninebot decoders.
     * Stored at `wheel_password_$mac` (mac AFTER the underscore) — diverges from the
     * standard `${mac}_$key` per-wheel format because legacy AppConfig hardcoded this
     * format. Preserved so existing users keep their stored passwords.
     */
    fun getWheelPassword(): String {
        val mac = currentMac().takeIf { it.isNotBlank() } ?: return ""
        return store.getString("wheel_password_$mac", "") ?: ""
    }

    private fun scoped(key: String): String = "${currentMac()}_$key"

    private fun currentMac(): String = store.getString(PreferenceKeys.LAST_MAC, "") ?: ""
}
