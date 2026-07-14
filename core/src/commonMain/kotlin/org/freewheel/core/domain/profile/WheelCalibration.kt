package org.freewheel.core.domain.profile

import org.freewheel.core.domain.settings.PreferenceDefaults

/**
 * App-owned telemetry calibration for one physical wheel.
 *
 * This is deliberately separate from wheel controls (whose source of truth is the
 * connected wheel), app presentation preferences, and pairing credentials. Values
 * retain the legacy storage units so existing installations can migrate losslessly.
 */
data class WheelCalibration(
    val customBatteryPercentEnabled: Boolean = PreferenceDefaults.CUSTOM_PERCENTS,
    /** Empty-cell voltage in hundredths of a volt. Reserved for custom SOC curves. */
    val emptyCellVoltageHundredths: Int = PreferenceDefaults.CELL_VOLTAGE_TILTBACK,
    /** Reference no-load speed in tenths of km/h. */
    val rotationSpeedTenthsKmh: Int = PreferenceDefaults.ROTATION_SPEED,
    /** Voltage associated with [rotationSpeedTenthsKmh], in tenths of a volt. */
    val rotationVoltageTenthsVolts: Int = PreferenceDefaults.ROTATION_VOLTAGE,
    val powerFactorPercent: Int = PreferenceDefaults.POWER_FACTOR,
    val batteryCapacityWh: Int = PreferenceDefaults.BATTERY_CAPACITY,
    val currentPolarity: WheelCurrentPolarity = WheelCurrentPolarity.ABSOLUTE,
    val begodeVoltageClass: BegodeVoltageClass = BegodeVoltageClass.AUTO,
    val gotwayDistanceRatioEnabled: Boolean = PreferenceDefaults.USE_RATIO,
    val hardwarePwmEnabled: Boolean = PreferenceDefaults.HW_PWM,
    val autoVoltageEnabled: Boolean = PreferenceDefaults.AUTO_VOLTAGE,
    val ks18LDistanceScalerEnabled: Boolean = PreferenceDefaults.KS18L_SCALER,
)

/** Interpretation of the signed current/speed fields used by Gotway-family protocols. */
enum class WheelCurrentPolarity(val legacyValue: Int) {
    INVERTED(-1),
    ABSOLUTE(0),
    ORIGINAL(1);

    companion object {
        fun fromLegacy(value: Int): WheelCurrentPolarity =
            entries.firstOrNull { it.legacyValue == value } ?: ABSOLUTE
    }
}

/** Explicit Begode/Gotway pack-voltage override. [AUTO] delegates to model detection. */
enum class BegodeVoltageClass(val legacyValue: Int) {
    AUTO(-1),
    V42(7),
    V67_2(0),
    V84(1),
    V100_8(2),
    V126(3),
    V134_4(4),
    V168(5),
    V151_2(6),
    V210(8);

    val fullVoltageV: Double?
        get() = when (this) {
            V42 -> 42.0
            AUTO -> null
            V67_2 -> 67.2
            V84 -> 84.0
            V100_8 -> 100.8
            V126 -> 126.0
            V134_4 -> 134.4
            V168 -> 168.0
            V151_2 -> 151.2
            V210 -> 210.0
        }

    companion object {
        fun fromLegacy(value: Int): BegodeVoltageClass =
            entries.firstOrNull { it.legacyValue == value } ?: AUTO

        fun fromFullVoltage(voltageV: Double): BegodeVoltageClass? =
            entries.firstOrNull { candidate ->
                candidate.fullVoltageV?.let { kotlin.math.abs(it - voltageV) < 0.05 } == true
            }
    }
}

/** Calibration fields whose effective value may come from a different owner. */
enum class WheelCalibrationField {
    CUSTOM_BATTERY_PERCENT,
    EMPTY_CELL_VOLTAGE,
    ROTATION_SPEED,
    ROTATION_VOLTAGE,
    POWER_FACTOR,
    BATTERY_CAPACITY,
    CURRENT_POLARITY,
    BEGODE_VOLTAGE_CLASS,
    GOTWAY_DISTANCE_RATIO,
    HARDWARE_PWM,
    AUTO_VOLTAGE,
    KS18L_DISTANCE_SCALER,
}

/** Why an effective calibration value was selected. */
enum class WheelCalibrationSource {
    USER_OVERRIDE,
    MODEL_CATALOG,
    LEGACY_GLOBAL,
    DEFAULT,
}

/**
 * Effective calibration plus field-level ownership information for diagnostics and UI.
 * The map is intentionally queried through [sourceFor] so Swift does not need to bridge
 * enum-keyed Kotlin maps directly.
 */
data class ResolvedWheelCalibration(
    val calibration: WheelCalibration,
    val sources: Map<WheelCalibrationField, WheelCalibrationSource>,
    val matchedModelName: String? = null,
) {
    fun sourceFor(field: WheelCalibrationField): WheelCalibrationSource =
        sources[field] ?: WheelCalibrationSource.DEFAULT
}
