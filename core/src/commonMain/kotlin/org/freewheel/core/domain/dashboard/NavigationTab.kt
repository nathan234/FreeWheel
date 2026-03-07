package org.freewheel.core.domain.dashboard

/**
 * Registry of screens that can appear as bottom navigation tabs.
 *
 * @property route Navigation route string used by platform nav frameworks
 * @property label Display label for the tab
 * @property iconName Platform-agnostic icon identifier. Android maps to Icons.Default.*,
 *                    iOS maps to SF Symbols.
 * @property isRequired If true, the tab cannot be removed from navigation (Devices is required)
 */
enum class NavigationTab(
    val route: String,
    val label: String,
    val iconName: String,
    val isRequired: Boolean
) {
    DEVICES("devices", "Devices", "bluetooth", true),
    CHART("chart", "Chart", "show_chart", false),
    BMS("bms", "BMS", "battery_full", false),
    RIDES("rides", "Rides", "route", false),
    WHEEL_SETTINGS("wheel_settings", "Wheel", "tune", false),
    SETTINGS("settings", "Settings", "settings", true);
}
