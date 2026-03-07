package org.freewheel.core.domain.dashboard

/**
 * Configuration for the bottom navigation tab bar.
 * Controls which screens appear as tabs and their order.
 *
 * Constraints:
 * - DEVICES tab is always required (connection management)
 * - 2-5 tabs supported
 * - No duplicate tabs
 */
data class NavigationConfig(
    val tabs: List<NavigationTab> = DEFAULT_TABS
) {
    /**
     * Returns true if this config is valid:
     * - Contains DEVICES
     * - Has 2-5 tabs
     * - No duplicates
     */
    fun isValid(): Boolean {
        if (NavigationTab.DEVICES !in tabs) return false
        if (tabs.size < 2 || tabs.size > 5) return false
        if (tabs.toSet().size != tabs.size) return false
        return true
    }

    /**
     * Returns soft warnings about the configuration.
     * These don't prevent saving but alert the user to potential issues.
     */
    fun warnings(): List<String> {
        val result = mutableListOf<String>()
        if (NavigationTab.SETTINGS !in tabs) {
            result += "Settings tab is recommended for access to app preferences"
        }
        return result
    }

    companion object {
        val DEFAULT_TABS = listOf(
            NavigationTab.DEVICES,
            NavigationTab.RIDES,
            NavigationTab.SETTINGS
        )
    }
}
