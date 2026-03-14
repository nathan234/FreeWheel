package org.freewheel.core.domain.dashboard

/**
 * Built-in dashboard layout presets.
 * Each preset provides a curated combination of hero, tiles, and stats.
 * Instances are cached — safe to call repeatedly without allocation.
 */
data class DashboardPreset(
    val id: String,
    val name: String,
    val layout: DashboardLayout
)

object DashboardPresets {

    private val _default = DashboardPreset(
        id = "default",
        name = "Default",
        layout = DashboardLayout.default()
    )

    private val _racing = DashboardPreset(
        id = "racing",
        name = "Racing",
        layout = DashboardLayout.create(
            id = "racing", name = "Racing",
            heroMetric = DashboardMetric.SPEED,
            tiles = listOf(
                DashboardMetric.PWM,
                DashboardMetric.POWER,
                DashboardMetric.BATTERY,
                DashboardMetric.TEMPERATURE
            ),
            stats = listOf(
                DashboardMetric.CURRENT,
                DashboardMetric.VOLTAGE,
                DashboardMetric.GPS_SPEED
            ),
            sections = emptySet()
        )
    )

    private val _touring = DashboardPreset(
        id = "touring",
        name = "Touring",
        layout = DashboardLayout.create(
            id = "touring", name = "Touring",
            heroMetric = DashboardMetric.SPEED,
            tiles = listOf(
                DashboardMetric.BATTERY,
                DashboardMetric.GPS_SPEED,
                DashboardMetric.TEMPERATURE,
                DashboardMetric.POWER
            ),
            stats = listOf(
                DashboardMetric.TRIP_DISTANCE,
                DashboardMetric.TOTAL_DISTANCE,
                DashboardMetric.VOLTAGE,
                DashboardMetric.CURRENT
            ),
            sections = setOf(DashboardSection.WHEEL_SETTINGS, DashboardSection.WHEEL_INFO, DashboardSection.BMS_SUMMARY)
        )
    )

    private val _compact = DashboardPreset(
        id = "compact",
        name = "Compact",
        layout = DashboardLayout.create(
            id = "compact", name = "Compact",
            heroMetric = DashboardMetric.SPEED,
            tiles = listOf(
                DashboardMetric.BATTERY,
                DashboardMetric.PWM
            ),
            stats = listOf(
                DashboardMetric.VOLTAGE,
                DashboardMetric.TRIP_DISTANCE
            ),
            sections = emptySet()
        )
    )

    private val _diagnostic = DashboardPreset(
        id = "diagnostic",
        name = "Diagnostic",
        layout = DashboardLayout.create(
            id = "diagnostic", name = "Diagnostic",
            heroMetric = DashboardMetric.PWM,
            tiles = listOf(
                DashboardMetric.SPEED,
                DashboardMetric.BATTERY,
                DashboardMetric.POWER,
                DashboardMetric.CURRENT,
                DashboardMetric.TEMPERATURE,
                DashboardMetric.TEMPERATURE_2
            ),
            stats = listOf(
                DashboardMetric.VOLTAGE,
                DashboardMetric.PHASE_CURRENT,
                DashboardMetric.ANGLE,
                DashboardMetric.ROLL,
                DashboardMetric.TORQUE,
                DashboardMetric.CPU_LOAD,
                DashboardMetric.TRIP_DISTANCE,
                DashboardMetric.TOTAL_DISTANCE
            ),
            sections = setOf(DashboardSection.WHEEL_SETTINGS, DashboardSection.WHEEL_INFO, DashboardSection.BMS_SUMMARY)
        )
    )

    private val _tilesOnly = DashboardPreset(
        id = "tiles_only",
        name = "Tiles Only",
        layout = DashboardLayout.create(
            id = "tiles_only", name = "Tiles Only",
            heroMetric = null,
            tiles = listOf(
                DashboardMetric.SPEED,
                DashboardMetric.BATTERY,
                DashboardMetric.POWER,
                DashboardMetric.PWM,
                DashboardMetric.TEMPERATURE,
                DashboardMetric.GPS_SPEED
            ),
            stats = listOf(
                DashboardMetric.VOLTAGE,
                DashboardMetric.CURRENT,
                DashboardMetric.TRIP_DISTANCE,
                DashboardMetric.TOTAL_DISTANCE
            ),
            sections = emptySet()
        )
    )

    private val _all = listOf(_default, _tilesOnly, _racing, _touring, _compact, _diagnostic)

    fun default(): DashboardPreset = _default
    fun tilesOnly(): DashboardPreset = _tilesOnly
    fun racing(): DashboardPreset = _racing
    fun touring(): DashboardPreset = _touring
    fun compact(): DashboardPreset = _compact
    fun diagnostic(): DashboardPreset = _diagnostic

    /** All built-in presets in display order. */
    fun all(): List<DashboardPreset> = _all
}
