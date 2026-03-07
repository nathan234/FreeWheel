package org.freewheel.core.domain.dashboard

/**
 * Hand-rolled text serializer for [NavigationConfig].
 *
 * v1 format: `v1|tabs:DEVICES,RIDES,SETTINGS`
 *
 * Currently v1 only — v2 reserved for future extension.
 * Unknown tab names are skipped gracefully (forward-compatible).
 * Invalid or empty input returns null (caller falls back to default).
 */
object NavigationConfigSerializer {

    private const val CURRENT_VERSION = "v1"

    fun serialize(config: NavigationConfig): String {
        return "$CURRENT_VERSION|tabs:${config.tabs.joinToString(",") { it.name }}"
    }

    fun deserialize(input: String): NavigationConfig? {
        if (input.isBlank()) return null
        val parts = input.split("|")
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            "v1" -> deserializeV1(parts)
            else -> null
        }
    }

    private fun deserializeV1(parts: List<String>): NavigationConfig? {
        for (part in parts.drop(1)) {
            val colonIdx = part.indexOf(':')
            if (colonIdx < 0) continue
            val key = part.substring(0, colonIdx)
            val value = part.substring(colonIdx + 1)

            if (key == "tabs") {
                val tabs = parseTabList(value)
                if (tabs.isEmpty()) return null
                val config = NavigationConfig(tabs = tabs)
                return if (config.isValid()) config else null
            }
        }
        return null
    }

    private fun parseTabList(csv: String): List<NavigationTab> {
        if (csv.isBlank()) return emptyList()
        return csv.split(",").mapNotNull { name ->
            try {
                NavigationTab.valueOf(name.trim())
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
