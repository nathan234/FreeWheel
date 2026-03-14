package org.freewheel.core.domain.dashboard

/**
 * Optional sections that can appear on the dashboard below the main metric areas.
 */
enum class DashboardSection(val label: String) {
    WHEEL_SETTINGS("Wheel Settings"),
    WHEEL_INFO("Wheel Info"),
    BMS_SUMMARY("BMS Summary")
}
