package org.freewheel.core.telemetry

import org.freewheel.core.domain.dashboard.DashboardMetric
import org.freewheel.core.utils.StringUtil

/** Typealias to the canonical [org.freewheel.core.domain.dashboard.ColorZone]. */
typealias ColorZone = org.freewheel.core.domain.dashboard.ColorZone

/**
 * Defines the metrics displayed as gauge tiles on the dashboard.
 * Each metric carries its display metadata and color threshold logic.
 */
enum class MetricType(
    val label: String,
    val unit: String,
    val maxValue: Double,    // gauge arc maximum; 0.0 = dynamic (track max seen)
    val greenBelow: Double,  // progress fraction where green ends
    val redAbove: Double,    // progress fraction where red starts
    val decimals: Int,       // decimal places for display formatting
    val colorHex: Long       // ARGB hex for chart/gauge coloring
) {
    SPEED("Speed", "km/h", 50.0, 0.5, 0.75, 1, 0xFF2196F3),
    BATTERY("Battery", "%", 100.0, 0.5, 0.75, 0, 0xFF4CAF50),      // inverted: green ABOVE 50%
    POWER("Power", "W", 0.0, 0.5, 0.75, 0, 0xFF4CAF50),             // dynamic max
    PWM("PWM", "%", 100.0, 0.5, 0.75, 1, 0xFFE91E63),
    TEMPERATURE("Temp", "\u00B0C", 80.0, 0.5, 0.6875, 0, 0xFFF44336), // 40/80=0.5, 55/80~=0.69
    GPS_SPEED("GPS Speed", "km/h", 50.0, 0.5, 0.75, 1, 0xFF00BCD4);

    companion object {
        const val CURRENT_COLOR_HEX: Long = 0xFFFF9800  // Orange
        const val VOLTAGE_COLOR_HEX: Long = 0xFF9C27B0  // Purple
    }

    /** Format a value using this metric's decimal precision. */
    fun formatValue(value: Double): String = StringUtil.formatDecimal(value, decimals)

    /** Extract this metric's value from a telemetry sample. */
    fun extractValue(sample: TelemetrySample): Double = when (this) {
        SPEED -> sample.speedKmh
        BATTERY -> sample.batteryPercent
        POWER -> sample.powerW
        PWM -> sample.pwmPercent
        TEMPERATURE -> sample.temperatureC
        GPS_SPEED -> sample.gpsSpeedKmh
    }

    /**
     * Returns a color indicator for the given progress fraction (0..1).
     * Delegates to [DashboardMetric.colorZone] for canonical logic.
     */
    fun colorZone(progress: Double): ColorZone {
        val dashboardMetric = when (this) {
            SPEED -> DashboardMetric.SPEED
            BATTERY -> DashboardMetric.BATTERY
            POWER -> DashboardMetric.POWER
            PWM -> DashboardMetric.PWM
            TEMPERATURE -> DashboardMetric.TEMPERATURE
            GPS_SPEED -> DashboardMetric.GPS_SPEED
        }
        return dashboardMetric.colorZone(progress)
    }
}
