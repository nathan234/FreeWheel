package org.freewheel.core.domain.dashboard

/**
 * Configuration for the bottom navigation tab bar.
 * Controls which screens appear as tabs and their order.
 *
 * Constraints:
 * - All required tabs (DEVICES, SETTINGS) must be present
 * - 2-5 tabs supported
 * - No duplicate tabs (by ID)
 */
data class NavigationConfig(
    val tabs: List<NavigationTab> = DEFAULT_TABS
) {
    /**
     * Returns true if this config is valid:
     * - Contains all required tabs
     * - Has 2-5 tabs
     * - No duplicates (by ID)
     */
    fun isValid(): Boolean {
        val required = NavigationTab.builtIn.filter { it.isRequired }
        if (!tabs.containsAll(required)) return false
        if (tabs.size < 2 || tabs.size > 5) return false
        if (tabs.map { it.id }.toSet().size != tabs.size) return false
        return true
    }

    /**
     * Returns soft warnings about the configuration.
     * These don't prevent saving but alert the user to potential issues.
     */
    fun warnings(): List<String> {
        return emptyList()
    }

    /** All custom tabs in this config. */
    val customTabs: List<NavigationTab.Custom>
        get() = tabs.filterIsInstance<NavigationTab.Custom>()

    companion object {
        val DEFAULT_TABS by lazy {
            listOf(
                NavigationTab.Devices,
                NavigationTab.Map,
                NavigationTab.Rides,
                NavigationTab.Settings
            )
        }

        /** Swift-callable factory (KMP data class defaults aren't exposed to ObjC). */
        fun default(): NavigationConfig = NavigationConfig()
    }
}
