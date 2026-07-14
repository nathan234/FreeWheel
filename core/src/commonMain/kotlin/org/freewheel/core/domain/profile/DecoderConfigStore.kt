package org.freewheel.core.domain.profile

import org.freewheel.core.domain.KeyValueStore
import org.freewheel.core.domain.settings.PreferenceDefaults
import org.freewheel.core.domain.settings.PreferenceKeys

/**
 * Reads app-owned wheel calibration from the legacy decoder preference keys.
 * [getCalibration] is the typed ownership boundary; the scalar getters remain
 * compatibility shims while platform callers migrate.
 *
 * Per-wheel values are scoped by [PreferenceKeys.LAST_CONNECTED_MAC] — the same
 * stable anchor [AppSettingsStore] uses, so reads stay consistent across an
 * explicit-disconnect cycle.
 */
class DecoderConfigStore(private val store: KeyValueStore) {

    /**
     * Loads calibration for [address], defaulting to the last connected wheel.
     *
     * `custom_percents` was historically global. A scoped value takes precedence,
     * while the global key remains a read-only migration fallback for existing users.
     */
    fun getCalibration(address: String = currentMac()): WheelCalibration = WheelCalibration(
        customBatteryPercentEnabled = if (
            address.isNotBlank() && store.contains(scoped(address, PreferenceKeys.CUSTOM_PERCENTS))
        ) {
            store.getBool(scoped(address, PreferenceKeys.CUSTOM_PERCENTS), PreferenceDefaults.CUSTOM_PERCENTS)
        } else {
            store.getBool(PreferenceKeys.CUSTOM_PERCENTS, PreferenceDefaults.CUSTOM_PERCENTS)
        },
        emptyCellVoltageHundredths = readInt(address, PreferenceKeys.CELL_VOLTAGE_TILTBACK, PreferenceDefaults.CELL_VOLTAGE_TILTBACK),
        rotationSpeedTenthsKmh = readInt(address, PreferenceKeys.ROTATION_SPEED, PreferenceDefaults.ROTATION_SPEED),
        rotationVoltageTenthsVolts = readInt(address, PreferenceKeys.ROTATION_VOLTAGE, PreferenceDefaults.ROTATION_VOLTAGE),
        powerFactorPercent = readInt(address, PreferenceKeys.POWER_FACTOR, PreferenceDefaults.POWER_FACTOR),
        batteryCapacityWh = readInt(address, PreferenceKeys.BATTERY_CAPACITY, PreferenceDefaults.BATTERY_CAPACITY),
        currentPolarity = WheelCurrentPolarity.fromLegacy(
            readLegacyInt(address, PreferenceKeys.GOTWAY_NEGATIVE, PreferenceDefaults.GOTWAY_NEGATIVE),
        ),
        begodeVoltageClass = BegodeVoltageClass.fromLegacy(
            readLegacyInt(address, PreferenceKeys.GOTWAY_VOLTAGE, PreferenceDefaults.GOTWAY_VOLTAGE),
        ),
        gotwayDistanceRatioEnabled = readBool(address, PreferenceKeys.USE_RATIO, PreferenceDefaults.USE_RATIO),
        hardwarePwmEnabled = readBool(address, PreferenceKeys.HW_PWM, PreferenceDefaults.HW_PWM),
        autoVoltageEnabled = readBool(address, PreferenceKeys.AUTO_VOLTAGE, PreferenceDefaults.AUTO_VOLTAGE),
        ks18LDistanceScalerEnabled = readBool(address, PreferenceKeys.KS18L_SCALER, PreferenceDefaults.KS18L_SCALER),
    )

    /** Explicit no-argument bridge for Swift, which does not export Kotlin default arguments. */
    fun getCurrentCalibration(): WheelCalibration = getCalibration(currentMac())

    /** Persists [calibration] using the existing per-wheel keys. */
    fun saveCalibration(address: String, calibration: WheelCalibration) {
        require(address.isNotBlank()) { "saveCalibration requires a non-blank wheel address" }
        store.putBool(scoped(address, PreferenceKeys.CUSTOM_PERCENTS), calibration.customBatteryPercentEnabled)
        store.putInt(scoped(address, PreferenceKeys.CELL_VOLTAGE_TILTBACK), calibration.emptyCellVoltageHundredths)
        store.putInt(scoped(address, PreferenceKeys.ROTATION_SPEED), calibration.rotationSpeedTenthsKmh)
        store.putInt(scoped(address, PreferenceKeys.ROTATION_VOLTAGE), calibration.rotationVoltageTenthsVolts)
        store.putInt(scoped(address, PreferenceKeys.POWER_FACTOR), calibration.powerFactorPercent)
        store.putInt(scoped(address, PreferenceKeys.BATTERY_CAPACITY), calibration.batteryCapacityWh)
        store.putString(scoped(address, PreferenceKeys.GOTWAY_NEGATIVE), calibration.currentPolarity.legacyValue.toString())
        store.putString(scoped(address, PreferenceKeys.GOTWAY_VOLTAGE), calibration.begodeVoltageClass.legacyValue.toString())
        store.putBool(scoped(address, PreferenceKeys.USE_RATIO), calibration.gotwayDistanceRatioEnabled)
        store.putBool(scoped(address, PreferenceKeys.HW_PWM), calibration.hardwarePwmEnabled)
        store.putBool(scoped(address, PreferenceKeys.AUTO_VOLTAGE), calibration.autoVoltageEnabled)
        store.putBool(scoped(address, PreferenceKeys.KS18L_SCALER), calibration.ks18LDistanceScalerEnabled)
    }

    /**
     * Restores defaults for one wheel. The scoped custom-percent default is retained
     * so resetting does not revive the historical global value for this wheel.
     */
    fun resetCalibration(address: String) {
        require(address.isNotBlank()) { "resetCalibration requires a non-blank wheel address" }
        calibrationKeys.forEach { store.remove(scoped(address, it)) }
        store.putBool(scoped(address, PreferenceKeys.CUSTOM_PERCENTS), PreferenceDefaults.CUSTOM_PERCENTS)
    }

    /** Whether a platform preference change can alter the active decoder config. */
    fun affectsDecoderConfig(key: String?): Boolean {
        if (key == null) return true // SharedPreferences.clear()
        if (key == PreferenceKeys.LAST_CONNECTED_MAC || key == PreferenceKeys.CUSTOM_PERCENTS) return true
        if (key.startsWith("wheel_password_")) return true
        return calibrationKeys.any { key.endsWith("_$it") }
    }

    fun getCustomPercents(): Boolean = getCalibration().customBatteryPercentEnabled

    fun getCellVoltageTiltback(): Int =
        getCalibration().emptyCellVoltageHundredths

    fun getRotationSpeed(): Int =
        getCalibration().rotationSpeedTenthsKmh

    fun getRotationVoltage(): Int =
        getCalibration().rotationVoltageTenthsVolts

    fun getPowerFactor(): Int =
        getCalibration().powerFactorPercent

    fun getBatteryCapacity(): Int =
        getCalibration().batteryCapacityWh

    fun getUseRatio(): Boolean =
        getCalibration().gotwayDistanceRatioEnabled

    fun getHwPwm(): Boolean =
        getCalibration().hardwarePwmEnabled

    fun getAutoVoltage(): Boolean =
        getCalibration().autoVoltageEnabled

    fun getKs18LScaler(): Boolean =
        getCalibration().ks18LDistanceScalerEnabled

    /** Stored as a string by the legacy ListPreference; parsed to int for decoder use. */
    fun getGotwayNegative(): Int =
        getCalibration().currentPolarity.legacyValue

    /** Stored as a string by the legacy ListPreference; parsed to int for decoder use. */
    fun getGotwayVoltage(): Int =
        getCalibration().begodeVoltageClass.legacyValue

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

    private fun readInt(address: String, key: String, default: Int): Int =
        if (address.isBlank()) default else store.getInt(scoped(address, key), default)

    private fun readBool(address: String, key: String, default: Boolean): Boolean =
        if (address.isBlank()) default else store.getBool(scoped(address, key), default)

    private fun readLegacyInt(address: String, key: String, default: String): Int =
        if (address.isBlank()) {
            default.toInt()
        } else {
            (store.getString(scoped(address, key), default) ?: default).toIntOrNull()
                ?: default.toInt()
        }

    private fun scoped(address: String, key: String): String = "${address}_$key"

    private fun currentMac(): String =
        store.getString(PreferenceKeys.LAST_CONNECTED_MAC, "") ?: ""

    private companion object {
        val calibrationKeys = listOf(
            PreferenceKeys.CUSTOM_PERCENTS,
            PreferenceKeys.CELL_VOLTAGE_TILTBACK,
            PreferenceKeys.ROTATION_SPEED,
            PreferenceKeys.ROTATION_VOLTAGE,
            PreferenceKeys.POWER_FACTOR,
            PreferenceKeys.BATTERY_CAPACITY,
            PreferenceKeys.GOTWAY_NEGATIVE,
            PreferenceKeys.GOTWAY_VOLTAGE,
            PreferenceKeys.USE_RATIO,
            PreferenceKeys.HW_PWM,
            PreferenceKeys.AUTO_VOLTAGE,
            PreferenceKeys.KS18L_SCALER,
        )
    }
}
