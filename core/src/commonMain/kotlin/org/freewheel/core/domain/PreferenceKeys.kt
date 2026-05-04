package org.freewheel.core.domain

/**
 * Single source of truth for preference key strings shared across Android and iOS.
 *
 * Android reads these via SharedPreferences; iOS reads via UserDefaults.
 * Keys that are per-wheel on Android use a MAC-address prefix (e.g. "AA:BB:CC:DD:EE:FF_alarms_enabled").
 * iOS currently stores alarm settings globally (no per-wheel prefix).
 */
object PreferenceKeys {
    // Unit display (global)
    const val USE_MPH = "use_mph"
    const val USE_FAHRENHEIT = "use_fahrenheit"
    const val SPEED_DISPLAY_MODE = "speed_display_mode"

    // Alarms (per-wheel on Android via MAC prefix, global on iOS currently)
    const val ALARMS_ENABLED = "alarms_enabled"
    const val ALARM_1_SPEED = "alarm_1_speed"
    const val ALARM_2_SPEED = "alarm_2_speed"
    const val ALARM_3_SPEED = "alarm_3_speed"
    const val ALARM_CURRENT = "alarm_current"
    const val ALARM_TEMPERATURE = "alarm_temperature"
    const val ALARM_BATTERY = "alarm_battery"
    const val ALARM_ACTION = "alarm_action"
    const val ALTERED_ALARMS = "altered_alarms"
    const val ALARM_FACTOR_1 = "alarm_factor1"
    const val ALARM_FACTOR_2 = "alarm_factor2"
    const val WARNING_PWM = "warning_pwm"
    const val WARNING_SPEED = "warning_speed"
    const val WARNING_SPEED_PERIOD = "warning_speed_period"
    const val ALARM_1_BATTERY = "alarm_1_battery"
    const val ALARM_2_BATTERY = "alarm_2_battery"
    const val ALARM_3_BATTERY = "alarm_3_battery"
    const val ALARM_PHASE_CURRENT = "alarm_phase_current"
    const val ALARM_MOTOR_TEMPERATURE = "alarm_motor_temperature"
    const val ALARM_WHEEL = "alarm_wheel"

    // The MAC of the last-connected wheel; also used as the prefix for per-wheel keys.
    const val LAST_MAC = "last_mac"

    // Connection (global)
    const val USE_RECONNECT = "use_reconnect"
    const val SHOW_UNKNOWN_DEVICES = "show_unknown_devices"

    // Decoder config (custom_percents global; rest per-wheel)
    const val CUSTOM_PERCENTS = "custom_percents"
    const val CELL_VOLTAGE_TILTBACK = "cell_voltage_tiltback"
    const val ROTATION_SPEED = "rotation_speed"
    const val ROTATION_VOLTAGE = "rotation_voltage"
    const val POWER_FACTOR = "power_factor"
    const val BATTERY_CAPACITY = "battery_capacity"
    const val GOTWAY_NEGATIVE = "gotway_negative"
    const val GOTWAY_VOLTAGE = "gotway_voltage"
    const val USE_RATIO = "use_ratio"
    const val HW_PWM = "hw_pwm"
    const val AUTO_VOLTAGE = "auto_voltage"
    const val KS18L_SCALER = "ks18l_scaler"

    // Logging (global)
    const val AUTO_LOG = "auto_log"
    const val LOG_LOCATION_DATA = "log_location_data"
    const val AUTO_CAPTURE = "auto_capture"

    // Wheel profiles
    const val SAVED_WHEEL_ADDRESSES = "saved_wheel_addresses"
    const val SUFFIX_PROFILE_NAME = "_profile_name"
    const val SUFFIX_WHEEL_TYPE = "_wheel_type_name"
    const val SUFFIX_LAST_CONNECTED = "_last_connected"
    const val SUFFIX_TOP_SPEED_OVERRIDE_KMH = "_top_speed_override_kmh"

    // Wheel settings slider persistence — keys MUST be built via wheelSliderKey() so they
    // are scoped to a specific wheel MAC. A bare prefix would let one wheel's last-set
    // value leak into another wheel's UI.
    private const val WHEEL_SLIDER_PREFIX = "wheel_slider_"

    /** Build the per-wheel slider persistence key. [mac] must be non-blank. */
    fun wheelSliderKey(mac: String, commandName: String): String {
        require(mac.isNotBlank()) { "wheelSliderKey requires a non-blank wheel MAC" }
        return "${mac}_${WHEEL_SLIDER_PREFIX}${commandName}"
    }

    // Dashboard layout (per-wheel)
    const val DASHBOARD_LAYOUT = "dashboard_layout"

    // Auto-torch (global)
    const val AUTO_TORCH_ENABLED = "auto_torch_enabled"
    const val AUTO_TORCH_SPEED_THRESHOLD = "auto_torch_speed_threshold"
    const val AUTO_TORCH_USE_SUNSET = "auto_torch_use_sunset"

    // Navigation config (global)
    const val NAVIGATION_CONFIG = "navigation_config"

    // Charger profiles
    const val SAVED_CHARGER_ADDRESSES = "saved_charger_addresses"
    const val SUFFIX_CHARGER_NAME = "_charger_name"
    const val SUFFIX_CHARGER_PASSWORD = "_charger_password"
    const val SUFFIX_CHARGER_LAST_CONNECTED = "_charger_last_connected"
}
