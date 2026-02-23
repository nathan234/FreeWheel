package com.cooper.wheellog.core.domain

/**
 * Single source of truth for preference default values shared across Android and iOS.
 *
 * Both platforms should reference these constants instead of hardcoding defaults.
 * Keys are in [PreferenceKeys]; this object holds the corresponding default values.
 */
object PreferenceDefaults {
    // Unit display
    const val USE_MPH = false
    const val USE_FAHRENHEIT = false

    // Alarm enablement
    const val ALARMS_ENABLED = false
    const val PWM_BASED_ALARMS = true

    // PWM-based alarm thresholds (%)
    const val ALARM_FACTOR_1 = 80
    const val ALARM_FACTOR_2 = 95

    // Pre-warning settings (0 = disabled)
    const val WARNING_PWM = 0
    const val WARNING_SPEED = 0
    const val WARNING_SPEED_PERIOD = 0

    // Speed-based alarm thresholds (km/h, 0 = disabled)
    const val ALARM_1_SPEED = 29
    const val ALARM_2_SPEED = 0
    const val ALARM_3_SPEED = 0

    // Speed-based alarm battery thresholds (%, 0 = disabled)
    const val ALARM_1_BATTERY = 100
    const val ALARM_2_BATTERY = 0
    const val ALARM_3_BATTERY = 0

    // Other alarm thresholds (0 = disabled)
    const val ALARM_CURRENT = 0
    const val ALARM_PHASE_CURRENT = 0
    const val ALARM_TEMPERATURE = 0
    const val ALARM_MOTOR_TEMPERATURE = 0
    const val ALARM_BATTERY = 0
    const val ALARM_WHEEL = false

    // Connection
    const val USE_RECONNECT = false
    const val SHOW_UNKNOWN_DEVICES = false

    // Logging
    const val AUTO_LOG = false
    const val LOG_LOCATION_DATA = false

    // Alarm action (index into AlarmAction enum)
    const val ALARM_ACTION = 0
}
