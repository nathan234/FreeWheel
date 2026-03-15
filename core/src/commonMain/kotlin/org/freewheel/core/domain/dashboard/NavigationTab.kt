package org.freewheel.core.domain.dashboard

/**
 * Registry of screens that can appear as bottom navigation tabs.
 *
 * Built-in tabs are data objects with fixed properties.
 * [Custom] tabs are user-created with a chosen label and icon.
 *
 * @property id Unique identifier (used for serialization and lookup)
 * @property route Navigation route string used by platform nav frameworks
 * @property label Display label for the tab
 * @property iconName Platform-agnostic icon identifier. Android maps to Icons.Default.*,
 *                    iOS maps to SF Symbols.
 * @property isRequired If true, the tab cannot be removed from navigation
 */
sealed class NavigationTab {
    abstract val id: String
    abstract val route: String
    abstract val label: String
    abstract val iconName: String
    abstract val isRequired: Boolean

    data object Devices : NavigationTab() {
        override val id = "DEVICES"
        override val route = "devices"
        override val label = "Devices"
        override val iconName = "bluetooth"
        override val isRequired = true
    }

    data object Chart : NavigationTab() {
        override val id = "CHART"
        override val route = "chart"
        override val label = "Chart"
        override val iconName = "show_chart"
        override val isRequired = false
    }

    data object Bms : NavigationTab() {
        override val id = "BMS"
        override val route = "bms"
        override val label = "BMS"
        override val iconName = "battery_full"
        override val isRequired = false
    }

    data object Rides : NavigationTab() {
        override val id = "RIDES"
        override val route = "rides"
        override val label = "Rides"
        override val iconName = "route"
        override val isRequired = false
    }

    data object WheelSettings : NavigationTab() {
        override val id = "WHEEL_SETTINGS"
        override val route = "wheel_settings"
        override val label = "Wheel"
        override val iconName = "tune"
        override val isRequired = false
    }

    data object Charger : NavigationTab() {
        override val id = "CHARGER"
        override val route = "charger"
        override val label = "Charger"
        override val iconName = "bolt"
        override val isRequired = false
    }

    data object Settings : NavigationTab() {
        override val id = "SETTINGS"
        override val route = "settings"
        override val label = "Settings"
        override val iconName = "settings"
        override val isRequired = true
    }

    data class Custom(
        override val id: String,
        override val label: String,
        override val iconName: String
    ) : NavigationTab() {
        override val route = "custom/$id"
        override val isRequired = false
    }

    companion object {
        /** All built-in tabs (replaces enum .entries). Lazy to avoid initialization order issues. */
        val builtIn: List<NavigationTab> by lazy {
            listOf(Devices, Chart, Bms, Rides, WheelSettings, Charger, Settings)
        }

        /** Look up a built-in tab by its ID, or null. */
        fun builtInById(id: String): NavigationTab? = builtIn.firstOrNull { it.id == id }

        /** Available icon names for custom tabs. */
        val customIcons: List<String> = listOf(
            "dashboard", "speed", "show_chart", "battery_full", "tune",
            "star", "favorite", "bolt", "visibility", "thermostat"
        )
    }
}
