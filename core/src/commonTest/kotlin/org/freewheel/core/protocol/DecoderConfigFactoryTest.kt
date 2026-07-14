package org.freewheel.core.protocol

import org.freewheel.core.domain.profile.BegodeVoltageClass
import org.freewheel.core.domain.profile.WheelCalibration
import org.freewheel.core.domain.profile.WheelCurrentPolarity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DecoderConfigFactoryTest {

    @Test
    fun `calibration maps once into protocol config without app preferences`() {
        val calibration = WheelCalibration(
            customBatteryPercentEnabled = true,
            emptyCellVoltageHundredths = 315,
            rotationSpeedTenthsKmh = 650,
            rotationVoltageTenthsVolts = 1260,
            powerFactorPercent = 95,
            batteryCapacityWh = 3600,
            currentPolarity = WheelCurrentPolarity.INVERTED,
            begodeVoltageClass = BegodeVoltageClass.V168,
            gotwayDistanceRatioEnabled = true,
            hardwarePwmEnabled = true,
            autoVoltageEnabled = false,
            ks18LDistanceScalerEnabled = true,
        )

        val config = DecoderConfigFactory.fromCalibration(calibration, wheelPassword = "123456")

        assertFalse(config.useMph)
        assertFalse(config.useFahrenheit)
        assertEquals(true, config.useCustomPercents)
        assertEquals(315, config.cellVoltageTiltback)
        assertEquals(650, config.rotationSpeed)
        assertEquals(1260, config.rotationVoltage)
        assertEquals(95, config.powerFactor)
        assertEquals(3600, config.batteryCapacity)
        assertEquals("123456", config.wheelPassword)
        assertEquals(-1, config.gotwayNegative)
        assertEquals(5, config.gotwayVoltage)
        assertEquals(true, config.useRatio)
        assertEquals(true, config.hwPwmEnabled)
        assertEquals(false, config.autoVoltage)
        assertEquals(true, config.ks18LScaler)
    }
}
