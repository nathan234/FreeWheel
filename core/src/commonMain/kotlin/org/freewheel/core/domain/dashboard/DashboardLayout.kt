package org.freewheel.core.domain.dashboard

import org.freewheel.core.domain.WheelType

/**
 * Configuration for the dashboard layout.
 * Defines which metric drives the hero gauge, which metrics appear as tiles,
 * which appear as stat rows, and which optional sections are visible.
 *
 * Use [create] for strict validation or [createLenient] for deserialization.
 */
data class DashboardLayout private constructor(
    val id: String?,
    val name: String?,
    /** Hero gauge metric, or null for tiles-only layout. */
    val heroMetric: DashboardMetric?,
    val tiles: List<DashboardMetric>,
    val stats: List<DashboardMetric>,
    val sections: Set<DashboardSection>
) {
    /** Backward compat: true if WHEEL_SETTINGS section is enabled. */
    val showWheelSettings: Boolean get() = DashboardSection.WHEEL_SETTINGS in sections

    /** Backward compat: true if WHEEL_INFO section is enabled. */
    val showWheelInfo: Boolean get() = DashboardSection.WHEEL_INFO in sections

    /** True if BMS_SUMMARY section is enabled. */
    val showBmsSummary: Boolean get() = DashboardSection.BMS_SUMMARY in sections

    /**
     * Returns a copy with metrics filtered for the given wheel type.
     * Removes metrics that are not available for the wheel, keeping the hero
     * metric unchanged (falls back to SPEED if the hero isn't available).
     */
    fun filteredFor(wheelType: WheelType): DashboardLayout {
        if (wheelType == WheelType.Unknown) return this
        val filteredHero = when {
            heroMetric == null -> null
            heroMetric.isAvailableFor(wheelType) -> heroMetric
            else -> DashboardMetric.SPEED
        }
        return copy(
            heroMetric = filteredHero,
            tiles = tiles.filter { it.isAvailableFor(wheelType) },
            stats = stats.filter { it.isAvailableFor(wheelType) }
        )
    }

    companion object {
        val DEFAULT_TILES = listOf(
            DashboardMetric.SPEED,
            DashboardMetric.BATTERY,
            DashboardMetric.POWER,
            DashboardMetric.PWM,
            DashboardMetric.TEMPERATURE,
            DashboardMetric.GPS_SPEED
        )

        val DEFAULT_STATS = listOf(
            DashboardMetric.VOLTAGE,
            DashboardMetric.CURRENT,
            DashboardMetric.TRIP_DISTANCE,
            DashboardMetric.TOTAL_DISTANCE
        )

        val DEFAULT_SECTIONS = setOf(DashboardSection.WHEEL_SETTINGS, DashboardSection.WHEEL_INFO, DashboardSection.BMS_SUMMARY)

        /**
         * Strict factory — throws on invalid metric placement.
         * Use for user-initiated layout creation (edit screens, presets).
         * Pass null for [heroMetric] to create a tiles-only layout.
         */
        fun create(
            id: String? = null,
            name: String? = null,
            heroMetric: DashboardMetric? = DashboardMetric.SPEED,
            tiles: List<DashboardMetric> = DEFAULT_TILES,
            stats: List<DashboardMetric> = DEFAULT_STATS,
            sections: Set<DashboardSection> = DEFAULT_SECTIONS
        ): DashboardLayout {
            if (heroMetric != null) {
                require(WidgetType.HERO_GAUGE in heroMetric.supportedDisplayTypes) {
                    "${heroMetric.name} does not support HERO_GAUGE"
                }
            }
            tiles.forEach {
                require(WidgetType.GAUGE_TILE in it.supportedDisplayTypes) {
                    "${it.name} does not support GAUGE_TILE"
                }
            }
            stats.forEach {
                require(WidgetType.STAT_ROW in it.supportedDisplayTypes) {
                    "${it.name} does not support STAT_ROW"
                }
            }
            return DashboardLayout(id, name, heroMetric, tiles.toList(), stats.toList(), sections.toSet())
        }

        /**
         * Lenient factory for deserialization — filters invalid metrics instead of throwing.
         * Invalid hero falls back to SPEED. Explicit null hero preserved (tiles-only layout).
         */
        fun createLenient(
            id: String? = null,
            name: String? = null,
            heroMetric: DashboardMetric? = DashboardMetric.SPEED,
            tiles: List<DashboardMetric> = DEFAULT_TILES,
            stats: List<DashboardMetric> = DEFAULT_STATS,
            sections: Set<DashboardSection> = DEFAULT_SECTIONS
        ): DashboardLayout {
            val validHero = when {
                heroMetric == null -> null
                WidgetType.HERO_GAUGE in heroMetric.supportedDisplayTypes -> heroMetric
                else -> DashboardMetric.SPEED
            }
            return DashboardLayout(
                id, name, validHero,
                tiles.filter { WidgetType.GAUGE_TILE in it.supportedDisplayTypes },
                stats.filter { WidgetType.STAT_ROW in it.supportedDisplayTypes },
                sections.toSet()
            )
        }

        /** Default layout matching current hardcoded behavior. */
        fun default(): DashboardLayout = _default

        // Cached default instance
        private val _default = DashboardLayout(
            id = null,
            name = null,
            heroMetric = DashboardMetric.SPEED,
            tiles = DEFAULT_TILES,
            stats = DEFAULT_STATS,
            sections = DEFAULT_SECTIONS
        )
    }
}
