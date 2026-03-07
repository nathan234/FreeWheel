package org.freewheel.core.domain.dashboard

/**
 * Display types for dashboard widgets.
 * Each metric declares which widget types it supports.
 */
enum class WidgetType {
    /** Large circular arc gauge (SpeedGauge style). */
    HERO_GAUGE,

    /** Small gauge tile with sparkline (GaugeTile style). */
    GAUGE_TILE,

    /** Compact label:value text row (StatRow style). */
    STAT_ROW
}
