package org.freewheel.core.domain

object SettingsLabels {
    // Section headers
    const val SECTION_UNITS = "Units"
    const val SECTION_ALARMS = "Speed & Safety Alarms"
    const val SECTION_PWM_THRESHOLDS = "PWM Alarm Thresholds"
    const val SECTION_PRE_WARNINGS = "Pre-Warnings"
    const val SECTION_SPEED_ALARMS = "Speed Alarms"
    const val SECTION_OTHER_ALARMS = "Other Alarms"
    const val SECTION_LOGGING = "Logging"
    const val SECTION_CONNECTION = "Connection"
    const val SECTION_ABOUT = "About"

    // Unit toggles
    const val USE_MPH = "Use Miles per Hour"
    const val USE_FAHRENHEIT = "Use Fahrenheit"

    // Alarm toggles & controls
    const val ENABLE_ALARMS = "Enable Alarms"
    const val ALARM_ACTION = "Alarm Action"
    const val PWM_BASED_ALARMS = "PWM-Based Alarms"
    const val PWM_DESCRIPTION = "PWM alarms trigger based on motor load instead of speed."
    const val ALARM_FACTOR_1 = "Alarm Factor 1"
    const val ALARM_FACTOR_2 = "Alarm Factor 2"
    const val ALARM_1_SPEED = "Alarm 1 Speed"
    const val ALARM_2_SPEED = "Alarm 2 Speed"
    const val ALARM_3_SPEED = "Alarm 3 Speed"
    const val ALARM_1_BATTERY = "Alarm 1 Battery"
    const val ALARM_2_BATTERY = "Alarm 2 Battery"
    const val ALARM_3_BATTERY = "Alarm 3 Battery"
    const val CURRENT_ALARM = "Current Alarm"
    const val PHASE_CURRENT_ALARM = "Phase Current Alarm"
    const val TEMPERATURE_ALARM = "Temperature Alarm"
    const val MOTOR_TEMP_ALARM = "Motor Temp Alarm"
    const val BATTERY_ALARM = "Battery Alarm"
    const val WHEEL_ALARM = "Wheel Alarm"
    const val WARNING_SPEED = "Warning Speed"
    const val WARNING_PWM = "Warning PWM"
    const val WARNING_PERIOD = "Warning Period"
    const val DISABLE_HINT = "Set to 0 to disable individual alarms."

    // Logging
    const val AUTO_START_LOGGING = "Auto-Start Logging"
    const val INCLUDE_GPS = "Include GPS"
    const val GPS_HINT = "GPS requires location permission. Logs are saved as CSV files."

    // Connection
    const val AUTO_RECONNECT = "Auto Reconnect"
    const val SHOW_UNKNOWN_DEVICES = "Show Unknown Devices"
    const val RECONNECT_HINT = "Automatically reconnect to the last wheel on startup."

    // Pre-warning
    const val PRE_WARNING_HINT = "Advisory tones before full alarms. Set warning period > 0 to enable."

    // Auto-torch
    const val SECTION_AUTO_TORCH = "Auto Headlight"
    const val AUTO_TORCH_ENABLED = "Enable Auto Headlight"
    const val AUTO_TORCH_SPEED_THRESHOLD = "Speed Threshold"
    const val AUTO_TORCH_USE_SUNSET = "Turn On After Sunset"
    const val AUTO_TORCH_HINT = "Automatically turns on the headlight when riding above the speed threshold or after sunset."

    // About / meta
    const val TITLE = "Settings"
    const val VERSION = "Version"
    const val CLOSE_APP = "Close App"
    const val GITHUB_REPOSITORY = "GitHub Repository"
}
