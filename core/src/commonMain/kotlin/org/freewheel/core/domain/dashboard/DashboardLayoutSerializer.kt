package org.freewheel.core.domain.dashboard

/**
 * Hand-rolled text serializer for [DashboardLayout].
 *
 * v2 format: `v2|id:racing|name:Racing|hero:SPEED|tiles:SPEED,BATTERY|stats:VOLTAGE|sections:WHEEL_SETTINGS,WHEEL_INFO`
 * v1 format: `v1|hero:SPEED|tiles:SPEED,BATTERY|stats:VOLTAGE|settings:1|info:1`
 *
 * - Missing key = use defaults (e.g., no `tiles` key → DEFAULT_TILES)
 * - Empty value = explicit empty (e.g., `tiles:` → empty list, `sections:` → no sections)
 * - Unknown metric names are skipped gracefully (forward-compatible)
 * - Invalid or empty input returns null (caller falls back to default)
 */
object DashboardLayoutSerializer {

    private const val CURRENT_VERSION = "v2"

    fun serialize(layout: DashboardLayout): String {
        val parts = mutableListOf(CURRENT_VERSION)
        layout.id?.let { parts += "id:$it" }
        layout.name?.let { parts += "name:$it" }
        parts += "hero:${layout.heroMetric?.name ?: "NONE"}"
        parts += "tiles:${layout.tiles.joinToString(",") { it.name }}"
        parts += "stats:${layout.stats.joinToString(",") { it.name }}"
        parts += "sections:${layout.sections.joinToString(",") { it.name }}"
        return parts.joinToString("|")
    }

    fun deserialize(input: String): DashboardLayout? {
        if (input.isBlank()) return null
        val parts = input.split("|")
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            "v1" -> deserializeV1(parts)
            "v2" -> deserializeV2(parts)
            else -> null
        }
    }

    /** Migrates v1 format (showSettings/showInfo booleans) to v2 (sections). */
    private fun deserializeV1(parts: List<String>): DashboardLayout? {
        var hero: DashboardMetric? = null
        var tiles: List<DashboardMetric>? = null
        var stats: List<DashboardMetric>? = null
        var showSettings = true
        var showInfo = true

        for (part in parts.drop(1)) {
            val colonIdx = part.indexOf(':')
            if (colonIdx < 0) continue
            val key = part.substring(0, colonIdx)
            val value = part.substring(colonIdx + 1)

            when (key) {
                "hero" -> hero = parseMetric(value)
                "tiles" -> tiles = parseMetricList(value)
                "stats" -> stats = parseMetricList(value)
                "settings" -> showSettings = value == "1"
                "info" -> showInfo = value == "1"
            }
        }

        val sections = mutableSetOf<DashboardSection>()
        if (showSettings) sections += DashboardSection.WHEEL_SETTINGS
        if (showInfo) sections += DashboardSection.WHEEL_INFO

        return DashboardLayout.createLenient(
            heroMetric = hero ?: DashboardMetric.SPEED,
            tiles = tiles ?: DashboardLayout.DEFAULT_TILES,
            stats = stats ?: DashboardLayout.DEFAULT_STATS,
            sections = sections
        )
    }

    private fun deserializeV2(parts: List<String>): DashboardLayout? {
        var id: String? = null
        var name: String? = null
        var hero: DashboardMetric? = null
        var heroExplicitlyNone = false
        var tiles: List<DashboardMetric>? = null
        var stats: List<DashboardMetric>? = null
        var sections: Set<DashboardSection>? = null

        for (part in parts.drop(1)) {
            val colonIdx = part.indexOf(':')
            if (colonIdx < 0) continue
            val key = part.substring(0, colonIdx)
            val value = part.substring(colonIdx + 1)

            when (key) {
                "id" -> id = value
                "name" -> name = value
                "hero" -> {
                    if (value == "NONE") {
                        heroExplicitlyNone = true
                    } else {
                        hero = parseMetric(value)
                    }
                }
                "tiles" -> tiles = parseMetricList(value)
                "stats" -> stats = parseMetricList(value)
                "sections" -> sections = parseSectionSet(value)
            }
        }

        return DashboardLayout.createLenient(
            id = id,
            name = name,
            heroMetric = if (heroExplicitlyNone) null else (hero ?: DashboardMetric.SPEED),
            tiles = tiles ?: DashboardLayout.DEFAULT_TILES,
            stats = stats ?: DashboardLayout.DEFAULT_STATS,
            sections = sections ?: DashboardLayout.DEFAULT_SECTIONS
        )
    }

    private fun parseMetric(name: String): DashboardMetric? {
        return try {
            DashboardMetric.valueOf(name)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseMetricList(csv: String): List<DashboardMetric> {
        if (csv.isBlank()) return emptyList()
        return csv.split(",").mapNotNull { parseMetric(it.trim()) }
    }

    private fun parseSectionSet(csv: String): Set<DashboardSection> {
        if (csv.isBlank()) return emptySet()
        return csv.split(",").mapNotNull { name ->
            try {
                DashboardSection.valueOf(name.trim())
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toSet()
    }
}
