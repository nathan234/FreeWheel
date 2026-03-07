package org.freewheel.core.domain.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnitCategoryTest {

    @Test
    fun `speed metrics have SPEED category`() {
        assertEquals(UnitCategory.SPEED, DashboardMetric.SPEED.unitCategory)
        assertEquals(UnitCategory.SPEED, DashboardMetric.GPS_SPEED.unitCategory)
        assertEquals(UnitCategory.SPEED, DashboardMetric.SPEED_LIMIT.unitCategory)
    }

    @Test
    fun `distance metrics have DISTANCE category`() {
        assertEquals(UnitCategory.DISTANCE, DashboardMetric.TRIP_DISTANCE.unitCategory)
        assertEquals(UnitCategory.DISTANCE, DashboardMetric.TOTAL_DISTANCE.unitCategory)
    }

    @Test
    fun `temperature metrics have TEMPERATURE category`() {
        assertEquals(UnitCategory.TEMPERATURE, DashboardMetric.TEMPERATURE.unitCategory)
        assertEquals(UnitCategory.TEMPERATURE, DashboardMetric.TEMPERATURE_2.unitCategory)
        assertEquals(UnitCategory.TEMPERATURE, DashboardMetric.CPU_TEMP.unitCategory)
        assertEquals(UnitCategory.TEMPERATURE, DashboardMetric.IMU_TEMP.unitCategory)
    }

    @Test
    fun `percentage metrics have PERCENTAGE category`() {
        assertEquals(UnitCategory.PERCENTAGE, DashboardMetric.BATTERY.unitCategory)
        assertEquals(UnitCategory.PERCENTAGE, DashboardMetric.PWM.unitCategory)
        assertEquals(UnitCategory.PERCENTAGE, DashboardMetric.CPU_LOAD.unitCategory)
    }

    @Test
    fun `power metrics have POWER category`() {
        assertEquals(UnitCategory.POWER, DashboardMetric.POWER.unitCategory)
        assertEquals(UnitCategory.POWER, DashboardMetric.MOTOR_POWER.unitCategory)
    }

    @Test
    fun `current metrics have CURRENT category`() {
        assertEquals(UnitCategory.CURRENT, DashboardMetric.CURRENT.unitCategory)
        assertEquals(UnitCategory.CURRENT, DashboardMetric.PHASE_CURRENT.unitCategory)
        assertEquals(UnitCategory.CURRENT, DashboardMetric.CURRENT_LIMIT.unitCategory)
    }

    @Test
    fun `voltage metric has VOLTAGE category`() {
        assertEquals(UnitCategory.VOLTAGE, DashboardMetric.VOLTAGE.unitCategory)
    }

    @Test
    fun `angle metrics have ANGLE category`() {
        assertEquals(UnitCategory.ANGLE, DashboardMetric.ANGLE.unitCategory)
        assertEquals(UnitCategory.ANGLE, DashboardMetric.ROLL.unitCategory)
    }

    @Test
    fun `dimensionless metrics have NONE category`() {
        assertEquals(UnitCategory.NONE, DashboardMetric.FAN_STATUS.unitCategory)
        assertEquals(UnitCategory.NONE, DashboardMetric.TORQUE.unitCategory)
    }

    @Test
    fun `derived isSpeedMetric matches unitCategory`() {
        for (metric in DashboardMetric.entries) {
            assertEquals(
                metric.unitCategory == UnitCategory.SPEED,
                metric.isSpeedMetric,
                "${metric.name}: isSpeedMetric should match unitCategory == SPEED"
            )
        }
    }

    @Test
    fun `derived isDistanceMetric matches unitCategory`() {
        for (metric in DashboardMetric.entries) {
            assertEquals(
                metric.unitCategory == UnitCategory.DISTANCE,
                metric.isDistanceMetric,
                "${metric.name}: isDistanceMetric should match unitCategory == DISTANCE"
            )
        }
    }

    @Test
    fun `derived isTemperatureMetric matches unitCategory`() {
        for (metric in DashboardMetric.entries) {
            assertEquals(
                metric.unitCategory == UnitCategory.TEMPERATURE,
                metric.isTemperatureMetric,
                "${metric.name}: isTemperatureMetric should match unitCategory == TEMPERATURE"
            )
        }
    }

    @Test
    fun `all 22 metrics have a unitCategory assigned`() {
        for (metric in DashboardMetric.entries) {
            // Just verify it doesn't throw — every metric must have a category
            metric.unitCategory
        }
        assertEquals(22, DashboardMetric.entries.size)
    }
}
