package org.freewheel.core.domain.dashboard

import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.telemetry.MetricType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DashboardMetricTest {

    // --- Value extraction ---

    @Test
    fun `SPEED extracts speedKmh from WheelState`() {
        val state = WheelState(speed = 2500) // 25.00 km/h
        assertEquals(25.0, DashboardMetric.SPEED.extractValue(state)!!, 0.01)
    }

    @Test
    fun `GPS_SPEED returns null`() {
        val state = WheelState(speed = 5000)
        assertNull(DashboardMetric.GPS_SPEED.extractValue(state))
    }

    @Test
    fun `all non-GPS metrics return non-null`() {
        val state = WheelState(
            speed = 2000, voltage = 8000, current = 1000, phaseCurrent = 2000,
            power = 100000, temperature = 3000, temperature2 = 3500,
            batteryLevel = 50, totalDistance = 100000, wheelDistance = 5000,
            calculatedPwm = 0.5, angle = 3.0, roll = 1.5, torque = 10.0,
            motorPower = 500.0, cpuTemp = 45, imuTemp = 40, cpuLoad = 60,
            speedLimit = 40.0, currentLimit = 70.0, fanStatus = 1
        )
        for (metric in DashboardMetric.entries) {
            if (metric == DashboardMetric.GPS_SPEED) continue
            assertNotNull(metric.extractValue(state), "${metric.name} should return non-null")
        }
    }

    @Test
    fun `BATTERY extracts batteryLevel`() {
        val state = WheelState(batteryLevel = 75)
        assertEquals(75.0, DashboardMetric.BATTERY.extractValue(state)!!)
    }

    @Test
    fun `VOLTAGE extracts voltageV`() {
        val state = WheelState(voltage = 8400)
        assertEquals(84.0, DashboardMetric.VOLTAGE.extractValue(state)!!, 0.01)
    }

    @Test
    fun `CURRENT extracts currentA`() {
        val state = WheelState(current = 1500)
        assertEquals(15.0, DashboardMetric.CURRENT.extractValue(state)!!, 0.01)
    }

    @Test
    fun `PHASE_CURRENT extracts phaseCurrentA`() {
        val state = WheelState(phaseCurrent = 2000)
        assertEquals(20.0, DashboardMetric.PHASE_CURRENT.extractValue(state)!!, 0.01)
    }

    @Test
    fun `POWER extracts powerW`() {
        val state = WheelState(power = 150000)
        assertEquals(1500.0, DashboardMetric.POWER.extractValue(state)!!, 0.01)
    }

    @Test
    fun `PWM extracts pwmPercent`() {
        val state = WheelState(calculatedPwm = 0.65)
        assertEquals(65.0, DashboardMetric.PWM.extractValue(state)!!, 0.01)
    }

    @Test
    fun `TEMPERATURE extracts temperatureC`() {
        val state = WheelState(temperature = 3500)
        assertEquals(35.0, DashboardMetric.TEMPERATURE.extractValue(state)!!, 0.01)
    }

    @Test
    fun `TEMPERATURE_2 extracts temperature2C`() {
        val state = WheelState(temperature2 = 4200)
        assertEquals(42.0, DashboardMetric.TEMPERATURE_2.extractValue(state)!!, 0.01)
    }

    @Test
    fun `TRIP_DISTANCE extracts wheelDistanceKm`() {
        val state = WheelState(wheelDistance = 5000)
        assertEquals(5.0, DashboardMetric.TRIP_DISTANCE.extractValue(state)!!, 0.01)
    }

    @Test
    fun `TOTAL_DISTANCE extracts totalDistanceKm`() {
        val state = WheelState(totalDistance = 100000)
        assertEquals(100.0, DashboardMetric.TOTAL_DISTANCE.extractValue(state)!!, 0.01)
    }

    @Test
    fun `ANGLE extracts angle`() {
        val state = WheelState(angle = 5.5)
        assertEquals(5.5, DashboardMetric.ANGLE.extractValue(state)!!, 0.01)
    }

    @Test
    fun `ROLL extracts roll`() {
        val state = WheelState(roll = 3.2)
        assertEquals(3.2, DashboardMetric.ROLL.extractValue(state)!!, 0.01)
    }

    @Test
    fun `TORQUE extracts torque`() {
        val state = WheelState(torque = 12.5)
        assertEquals(12.5, DashboardMetric.TORQUE.extractValue(state)!!, 0.01)
    }

    @Test
    fun `MOTOR_POWER extracts motorPower`() {
        val state = WheelState(motorPower = 800.0)
        assertEquals(800.0, DashboardMetric.MOTOR_POWER.extractValue(state)!!, 0.01)
    }

    @Test
    fun `CPU_TEMP extracts cpuTemp`() {
        val state = WheelState(cpuTemp = 55)
        assertEquals(55.0, DashboardMetric.CPU_TEMP.extractValue(state)!!, 0.01)
    }

    @Test
    fun `IMU_TEMP extracts imuTemp`() {
        val state = WheelState(imuTemp = 42)
        assertEquals(42.0, DashboardMetric.IMU_TEMP.extractValue(state)!!, 0.01)
    }

    @Test
    fun `CPU_LOAD extracts cpuLoad`() {
        val state = WheelState(cpuLoad = 80)
        assertEquals(80.0, DashboardMetric.CPU_LOAD.extractValue(state)!!, 0.01)
    }

    @Test
    fun `SPEED_LIMIT extracts speedLimit`() {
        val state = WheelState(speedLimit = 45.0)
        assertEquals(45.0, DashboardMetric.SPEED_LIMIT.extractValue(state)!!, 0.01)
    }

    @Test
    fun `CURRENT_LIMIT extracts currentLimit`() {
        val state = WheelState(currentLimit = 30.0)
        assertEquals(30.0, DashboardMetric.CURRENT_LIMIT.extractValue(state)!!, 0.01)
    }

    @Test
    fun `FAN_STATUS extracts fanStatus`() {
        val state = WheelState(fanStatus = 1)
        assertEquals(1.0, DashboardMetric.FAN_STATUS.extractValue(state)!!, 0.01)
    }

    // --- Wheel type availability ---

    @Test
    fun `universal metrics are available for all wheel types`() {
        val universalMetrics = listOf(
            DashboardMetric.SPEED, DashboardMetric.GPS_SPEED, DashboardMetric.BATTERY,
            DashboardMetric.VOLTAGE, DashboardMetric.CURRENT, DashboardMetric.POWER,
            DashboardMetric.PWM, DashboardMetric.TEMPERATURE,
            DashboardMetric.TRIP_DISTANCE, DashboardMetric.TOTAL_DISTANCE
        )
        for (metric in universalMetrics) {
            for (type in WheelType.entries) {
                assertTrue(metric.isAvailableFor(type), "${metric.name} should be available for ${type.name}")
            }
        }
    }

    @Test
    fun `PHASE_CURRENT is only available for Gotway and Veteran`() {
        assertTrue(DashboardMetric.PHASE_CURRENT.isAvailableFor(WheelType.GOTWAY))
        assertTrue(DashboardMetric.PHASE_CURRENT.isAvailableFor(WheelType.GOTWAY_VIRTUAL))
        assertTrue(DashboardMetric.PHASE_CURRENT.isAvailableFor(WheelType.VETERAN))
        assertFalse(DashboardMetric.PHASE_CURRENT.isAvailableFor(WheelType.KINGSONG))
        assertFalse(DashboardMetric.PHASE_CURRENT.isAvailableFor(WheelType.INMOTION_V2))
    }

    @Test
    fun `TORQUE is only available for InMotion V2`() {
        assertTrue(DashboardMetric.TORQUE.isAvailableFor(WheelType.INMOTION_V2))
        assertFalse(DashboardMetric.TORQUE.isAvailableFor(WheelType.KINGSONG))
        assertFalse(DashboardMetric.TORQUE.isAvailableFor(WheelType.GOTWAY))
    }

    @Test
    fun `CPU_LOAD is only available for KingSong`() {
        assertTrue(DashboardMetric.CPU_LOAD.isAvailableFor(WheelType.KINGSONG))
        assertFalse(DashboardMetric.CPU_LOAD.isAvailableFor(WheelType.GOTWAY))
        assertFalse(DashboardMetric.CPU_LOAD.isAvailableFor(WheelType.INMOTION_V2))
    }

    @Test
    fun `FAN_STATUS is only available for KingSong`() {
        assertTrue(DashboardMetric.FAN_STATUS.isAvailableFor(WheelType.KINGSONG))
        assertFalse(DashboardMetric.FAN_STATUS.isAvailableFor(WheelType.VETERAN))
    }

    @Test
    fun `ANGLE is available for Veteran and InMotion`() {
        assertTrue(DashboardMetric.ANGLE.isAvailableFor(WheelType.VETERAN))
        assertTrue(DashboardMetric.ANGLE.isAvailableFor(WheelType.INMOTION))
        assertTrue(DashboardMetric.ANGLE.isAvailableFor(WheelType.INMOTION_V2))
        assertFalse(DashboardMetric.ANGLE.isAvailableFor(WheelType.KINGSONG))
    }

    // --- sparklineKey (formerly toMetricType) ---

    @Test
    fun `sparklineKey maps 6 existing metrics correctly`() {
        assertEquals(MetricType.SPEED, DashboardMetric.SPEED.sparklineKey)
        assertEquals(MetricType.GPS_SPEED, DashboardMetric.GPS_SPEED.sparklineKey)
        assertEquals(MetricType.BATTERY, DashboardMetric.BATTERY.sparklineKey)
        assertEquals(MetricType.POWER, DashboardMetric.POWER.sparklineKey)
        assertEquals(MetricType.PWM, DashboardMetric.PWM.sparklineKey)
        assertEquals(MetricType.TEMPERATURE, DashboardMetric.TEMPERATURE.sparklineKey)
    }

    @Test
    fun `sparklineKey returns null for metrics without sparkline`() {
        assertNull(DashboardMetric.VOLTAGE.sparklineKey)
        assertNull(DashboardMetric.CURRENT.sparklineKey)
        assertNull(DashboardMetric.TORQUE.sparklineKey)
        assertNull(DashboardMetric.TRIP_DISTANCE.sparklineKey)
    }

    @Test
    fun `deprecated toMetricType delegates to sparklineKey`() {
        @Suppress("DEPRECATION")
        assertEquals(DashboardMetric.SPEED.sparklineKey, DashboardMetric.SPEED.toMetricType())
    }

    // --- Classification flags ---

    @Test
    fun `isSpeedMetric is true for speed metrics`() {
        assertTrue(DashboardMetric.SPEED.isSpeedMetric)
        assertTrue(DashboardMetric.GPS_SPEED.isSpeedMetric)
        assertTrue(DashboardMetric.SPEED_LIMIT.isSpeedMetric)
        assertFalse(DashboardMetric.BATTERY.isSpeedMetric)
        assertFalse(DashboardMetric.POWER.isSpeedMetric)
    }

    @Test
    fun `isDistanceMetric is true for distance metrics`() {
        assertTrue(DashboardMetric.TRIP_DISTANCE.isDistanceMetric)
        assertTrue(DashboardMetric.TOTAL_DISTANCE.isDistanceMetric)
        assertFalse(DashboardMetric.SPEED.isDistanceMetric)
    }

    @Test
    fun `isTemperatureMetric is true for temperature metrics`() {
        assertTrue(DashboardMetric.TEMPERATURE.isTemperatureMetric)
        assertTrue(DashboardMetric.TEMPERATURE_2.isTemperatureMetric)
        assertTrue(DashboardMetric.CPU_TEMP.isTemperatureMetric)
        assertTrue(DashboardMetric.IMU_TEMP.isTemperatureMetric)
        assertFalse(DashboardMetric.SPEED.isTemperatureMetric)
    }

    // --- Widget type support ---

    @Test
    fun `SPEED supports all widget types`() {
        assertTrue(WidgetType.HERO_GAUGE in DashboardMetric.SPEED.supportedDisplayTypes)
        assertTrue(WidgetType.GAUGE_TILE in DashboardMetric.SPEED.supportedDisplayTypes)
        assertTrue(WidgetType.STAT_ROW in DashboardMetric.SPEED.supportedDisplayTypes)
    }

    @Test
    fun `VOLTAGE does not support HERO_GAUGE`() {
        assertFalse(WidgetType.HERO_GAUGE in DashboardMetric.VOLTAGE.supportedDisplayTypes)
        assertTrue(WidgetType.GAUGE_TILE in DashboardMetric.VOLTAGE.supportedDisplayTypes)
        assertTrue(WidgetType.STAT_ROW in DashboardMetric.VOLTAGE.supportedDisplayTypes)
    }

    @Test
    fun `TRIP_DISTANCE only supports STAT_ROW`() {
        assertFalse(WidgetType.HERO_GAUGE in DashboardMetric.TRIP_DISTANCE.supportedDisplayTypes)
        assertFalse(WidgetType.GAUGE_TILE in DashboardMetric.TRIP_DISTANCE.supportedDisplayTypes)
        assertTrue(WidgetType.STAT_ROW in DashboardMetric.TRIP_DISTANCE.supportedDisplayTypes)
    }

    // --- All 24 metrics present ---

    @Test
    fun `there are 24 metrics in the registry`() {
        assertEquals(24, DashboardMetric.entries.size)
    }

    @Test
    fun `all metrics have non-empty labels`() {
        for (metric in DashboardMetric.entries) {
            assertTrue(metric.label.isNotBlank(), "${metric.name} should have a non-blank label")
        }
    }

    @Test
    fun `all metrics support at least one display type`() {
        for (metric in DashboardMetric.entries) {
            assertTrue(
                metric.supportedDisplayTypes.isNotEmpty(),
                "${metric.name} should support at least one display type"
            )
        }
    }

    @Test
    fun `all metrics have a colorHex value`() {
        for (metric in DashboardMetric.entries) {
            assertTrue(metric.colorHex != 0L, "${metric.name} should have a non-zero colorHex")
        }
    }
}
