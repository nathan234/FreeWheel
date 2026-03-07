package org.freewheel.core.domain.dashboard

import org.freewheel.core.telemetry.MetricType
import kotlin.test.Test
import kotlin.test.assertEquals

class ColorZoneTest {

    // --- Normal metrics (low = green) ---

    @Test
    fun `normal metric low progress is GREEN`() {
        assertEquals(ColorZone.GREEN, DashboardMetric.SPEED.colorZone(0.3))
    }

    @Test
    fun `normal metric medium progress is ORANGE`() {
        assertEquals(ColorZone.ORANGE, DashboardMetric.SPEED.colorZone(0.6))
    }

    @Test
    fun `normal metric high progress is RED`() {
        assertEquals(ColorZone.RED, DashboardMetric.SPEED.colorZone(0.9))
    }

    @Test
    fun `normal metric at greenBelow boundary is ORANGE`() {
        // greenBelow = 0.5, progress = 0.5 is NOT < 0.5, so ORANGE
        assertEquals(ColorZone.ORANGE, DashboardMetric.SPEED.colorZone(0.5))
    }

    @Test
    fun `normal metric at redAbove boundary is RED`() {
        // redAbove = 0.75, progress = 0.75 is NOT < 0.75, so RED
        assertEquals(ColorZone.RED, DashboardMetric.SPEED.colorZone(0.75))
    }

    // --- Inverted metric (BATTERY: high = green) ---

    @Test
    fun `BATTERY high progress is GREEN`() {
        assertEquals(ColorZone.GREEN, DashboardMetric.BATTERY.colorZone(0.8))
    }

    @Test
    fun `BATTERY medium progress is ORANGE`() {
        assertEquals(ColorZone.ORANGE, DashboardMetric.BATTERY.colorZone(0.4))
    }

    @Test
    fun `BATTERY low progress is RED`() {
        assertEquals(ColorZone.RED, DashboardMetric.BATTERY.colorZone(0.1))
    }

    @Test
    fun `BATTERY is the only invertedColor metric`() {
        val inverted = DashboardMetric.entries.filter { it.invertedColor }
        assertEquals(listOf(DashboardMetric.BATTERY), inverted)
    }

    // --- MetricType delegation ---

    @Test
    fun `MetricType colorZone matches DashboardMetric colorZone for all shared metrics`() {
        val pairs = listOf(
            MetricType.SPEED to DashboardMetric.SPEED,
            MetricType.BATTERY to DashboardMetric.BATTERY,
            MetricType.POWER to DashboardMetric.POWER,
            MetricType.PWM to DashboardMetric.PWM,
            MetricType.TEMPERATURE to DashboardMetric.TEMPERATURE,
            MetricType.GPS_SPEED to DashboardMetric.GPS_SPEED
        )
        val testValues = listOf(0.0, 0.1, 0.25, 0.4, 0.5, 0.6, 0.75, 0.9, 1.0)
        for ((metricType, dashboardMetric) in pairs) {
            for (progress in testValues) {
                assertEquals(
                    dashboardMetric.colorZone(progress),
                    metricType.colorZone(progress),
                    "${metricType.name} at $progress should match DashboardMetric"
                )
            }
        }
    }

    // --- Temperature thresholds ---

    @Test
    fun `temperature metric uses custom thresholds`() {
        // greenBelow = 0.5, redAbove = 0.6875
        assertEquals(ColorZone.GREEN, DashboardMetric.TEMPERATURE.colorZone(0.4))
        assertEquals(ColorZone.ORANGE, DashboardMetric.TEMPERATURE.colorZone(0.6))
        assertEquals(ColorZone.RED, DashboardMetric.TEMPERATURE.colorZone(0.7))
    }
}
