package org.freewheel.compose.navigation

/**
 * Centralized route definitions for Jetpack Navigation.
 *
 * Tab routes live on [NavigationTab.route] (KMP shared).
 * Overlay routes (screens that hide the bottom bar) are defined here.
 */
object Routes {
    // Overlay route patterns (for composable() registration)
    const val EDIT_DASHBOARD = "edit_dashboard"
    const val EDIT_NAVIGATION = "edit_navigation"
    const val CUSTOM_TAB = "custom/{tabId}"
    const val EDIT_CUSTOM_TAB = "edit_custom_tab/{tabId}"
    const val METRIC_DETAIL = "metric/{metricId}"
    const val TRIP_DETAIL = "trip/{fileName}"
    const val BLE_CAPTURE = "ble_capture"

    // Route prefixes (for bottom-bar visibility checks)
    const val CUSTOM_PREFIX = "custom/"
    const val EDIT_CUSTOM_PREFIX = "edit_custom_tab/"
    const val METRIC_PREFIX = "metric/"
    const val TRIP_PREFIX = "trip/"

    // Builders (for navController.navigate())
    fun customTab(tabId: String) = "custom/$tabId"
    fun editCustomTab(tabId: String) = "edit_custom_tab/$tabId"
    fun metricDetail(metricId: String) = "metric/$metricId"
    fun tripDetail(fileName: String) = "trip/$fileName"
}
