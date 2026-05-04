package org.freewheel.core.domain

/**
 * Well-known destination identifiers for [AppSettingSpec.NavLink].
 * Platform layers map these to their own navigation actions.
 */
object AppSettingsDestinations {
    const val CUSTOMIZE_NAVIGATION = "customize_navigation"
    const val BLE_CAPTURE = "ble_capture"
    const val CONNECTION_ERROR_LOG = "connection_error_log"
    const val WHEEL_EVENT_LOG = "wheel_event_log"
    const val DIAGNOSTICS = "diagnostics"
}

/**
 * Well-known value identifiers for [AppSettingSpec.StaticInfo].
 * Platform layers resolve these to runtime values (e.g., app version string).
 */
object AppSettingsValueIds {
    const val APP_VERSION = "app_version"
    const val BUILD_DATE = "build_date"
}

/**
 * Well-known action identifiers for [AppSettingSpec.ActionButton].
 * Platform layers map these to their own action handlers.
 */
object AppSettingsActions {
    const val CLOSE_APP = "close_app"
}
