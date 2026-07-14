package org.freewheel.core.protocol

import org.freewheel.core.domain.profile.WheelCalibration

/** Maps the app-owned wheel calibration boundary into decoder-only configuration. */
object DecoderConfigFactory {
    fun fromCalibration(
        calibration: WheelCalibration,
        wheelPassword: String = "",
    ): DecoderConfig = DecoderConfig(
        useCustomPercents = calibration.customBatteryPercentEnabled,
        cellVoltageTiltback = calibration.emptyCellVoltageHundredths,
        rotationSpeed = calibration.rotationSpeedTenthsKmh,
        rotationVoltage = calibration.rotationVoltageTenthsVolts,
        powerFactor = calibration.powerFactorPercent,
        batteryCapacity = calibration.batteryCapacityWh,
        wheelPassword = wheelPassword,
        gotwayNegative = calibration.currentPolarity.legacyValue,
        useRatio = calibration.gotwayDistanceRatioEnabled,
        gotwayVoltage = calibration.begodeVoltageClass.legacyValue,
        hwPwmEnabled = calibration.hardwarePwmEnabled,
        ks18LScaler = calibration.ks18LDistanceScalerEnabled,
        autoVoltage = calibration.autoVoltageEnabled,
    )
}
