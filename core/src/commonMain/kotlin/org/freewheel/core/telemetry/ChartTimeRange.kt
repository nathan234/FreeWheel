package org.freewheel.core.telemetry

/**
 * Time ranges available for the telemetry chart.
 * Each range defines a display label and duration in milliseconds.
 */
enum class ChartTimeRange(val label: String, val durationMs: Long) {
    FIVE_MINUTES("5m", 300_000L),
    ONE_HOUR("1h", 3_600_000L),
    TWENTY_FOUR_HOURS("24h", 86_400_000L)
}
