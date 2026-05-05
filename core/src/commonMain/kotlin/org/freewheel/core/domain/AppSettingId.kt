package org.freewheel.core.domain

/**
 * Storage scope for an app-level setting.
 */
enum class SettingScope {
    /** Stored globally (same value regardless of connected wheel). */
    GLOBAL,
    /** Stored per-wheel (MAC-prefixed on Android, global on iOS currently). */
    PER_WHEEL
}

/**
 * Identifies each app-level setting. Maps to a [PreferenceKeys] constant, a [SettingScope],
 * and default values from [PreferenceDefaults].
 *
 * Used by [AppSettingsConfig] to declare the settings screen structure and by both
 * Android and iOS to read/write preference values generically.
 */
enum class AppSettingId(
    val prefKey: String,
    val scope: SettingScope
) {
    // Units
    USE_MPH(PreferenceKeys.USE_MPH, SettingScope.GLOBAL),
    USE_FAHRENHEIT(PreferenceKeys.USE_FAHRENHEIT, SettingScope.GLOBAL),

    // Alarms
    ALARMS_ENABLED(PreferenceKeys.ALARMS_ENABLED, SettingScope.PER_WHEEL),
    ALARM_ACTION(PreferenceKeys.ALARM_ACTION, SettingScope.GLOBAL),
    PWM_BASED_ALARMS(PreferenceKeys.ALTERED_ALARMS, SettingScope.PER_WHEEL),

    // PWM thresholds
    ALARM_FACTOR_1(PreferenceKeys.ALARM_FACTOR_1, SettingScope.PER_WHEEL),
    ALARM_FACTOR_2(PreferenceKeys.ALARM_FACTOR_2, SettingScope.PER_WHEEL),

    // Pre-warnings
    WARNING_SPEED(PreferenceKeys.WARNING_SPEED, SettingScope.PER_WHEEL),
    WARNING_PWM(PreferenceKeys.WARNING_PWM, SettingScope.PER_WHEEL),
    WARNING_SPEED_PERIOD(PreferenceKeys.WARNING_SPEED_PERIOD, SettingScope.PER_WHEEL),

    // Speed alarms
    ALARM_1_SPEED(PreferenceKeys.ALARM_1_SPEED, SettingScope.PER_WHEEL),
    ALARM_1_BATTERY(PreferenceKeys.ALARM_1_BATTERY, SettingScope.PER_WHEEL),
    ALARM_2_SPEED(PreferenceKeys.ALARM_2_SPEED, SettingScope.PER_WHEEL),
    ALARM_2_BATTERY(PreferenceKeys.ALARM_2_BATTERY, SettingScope.PER_WHEEL),
    ALARM_3_SPEED(PreferenceKeys.ALARM_3_SPEED, SettingScope.PER_WHEEL),
    ALARM_3_BATTERY(PreferenceKeys.ALARM_3_BATTERY, SettingScope.PER_WHEEL),

    // Other alarms
    ALARM_CURRENT(PreferenceKeys.ALARM_CURRENT, SettingScope.PER_WHEEL),
    ALARM_PHASE_CURRENT(PreferenceKeys.ALARM_PHASE_CURRENT, SettingScope.PER_WHEEL),
    ALARM_TEMPERATURE(PreferenceKeys.ALARM_TEMPERATURE, SettingScope.PER_WHEEL),
    ALARM_MOTOR_TEMPERATURE(PreferenceKeys.ALARM_MOTOR_TEMPERATURE, SettingScope.PER_WHEEL),
    ALARM_BATTERY(PreferenceKeys.ALARM_BATTERY, SettingScope.PER_WHEEL),
    ALARM_WHEEL(PreferenceKeys.ALARM_WHEEL, SettingScope.PER_WHEEL),

    // Connection
    AUTO_RECONNECT(PreferenceKeys.USE_RECONNECT, SettingScope.GLOBAL),
    SHOW_UNKNOWN_DEVICES(PreferenceKeys.SHOW_UNKNOWN_DEVICES, SettingScope.GLOBAL),

    // Logging
    AUTO_LOG(PreferenceKeys.AUTO_LOG, SettingScope.GLOBAL),
    LOG_LOCATION_DATA(PreferenceKeys.LOG_LOCATION_DATA, SettingScope.GLOBAL),
    AUTO_CAPTURE(PreferenceKeys.AUTO_CAPTURE, SettingScope.GLOBAL),

    // Auto torch
    AUTO_TORCH_ENABLED(PreferenceKeys.AUTO_TORCH_ENABLED, SettingScope.GLOBAL),
    AUTO_TORCH_SPEED_THRESHOLD(PreferenceKeys.AUTO_TORCH_SPEED_THRESHOLD, SettingScope.GLOBAL),
    AUTO_TORCH_USE_SUNSET(PreferenceKeys.AUTO_TORCH_USE_SUNSET, SettingScope.GLOBAL);

    /**
     * Discriminator between boolean-valued and int-valued settings. Exhaustive: a new id
     * must be classified here, which is what lets iOS auto-build its visibility-state map
     * from [entries] instead of hand-maintaining a subset.
     */
    val isBool: Boolean
        get() = when (this) {
            USE_MPH, USE_FAHRENHEIT, ALARMS_ENABLED, PWM_BASED_ALARMS, ALARM_WHEEL,
            AUTO_RECONNECT, SHOW_UNKNOWN_DEVICES, AUTO_LOG, LOG_LOCATION_DATA, AUTO_CAPTURE,
            AUTO_TORCH_ENABLED, AUTO_TORCH_USE_SUNSET -> true
            ALARM_ACTION, ALARM_FACTOR_1, ALARM_FACTOR_2, WARNING_SPEED, WARNING_PWM,
            WARNING_SPEED_PERIOD, ALARM_1_SPEED, ALARM_1_BATTERY, ALARM_2_SPEED, ALARM_2_BATTERY,
            ALARM_3_SPEED, ALARM_3_BATTERY, ALARM_CURRENT, ALARM_PHASE_CURRENT, ALARM_TEMPERATURE,
            ALARM_MOTOR_TEMPERATURE, ALARM_BATTERY, AUTO_TORCH_SPEED_THRESHOLD -> false
        }

    /** Default boolean value for this setting, or false if not a boolean setting. */
    val defaultBool: Boolean
        get() = when (this) {
            USE_MPH -> PreferenceDefaults.USE_MPH
            USE_FAHRENHEIT -> PreferenceDefaults.USE_FAHRENHEIT
            ALARMS_ENABLED -> PreferenceDefaults.ALARMS_ENABLED
            PWM_BASED_ALARMS -> PreferenceDefaults.PWM_BASED_ALARMS
            ALARM_WHEEL -> PreferenceDefaults.ALARM_WHEEL
            AUTO_RECONNECT -> PreferenceDefaults.USE_RECONNECT
            SHOW_UNKNOWN_DEVICES -> PreferenceDefaults.SHOW_UNKNOWN_DEVICES
            AUTO_LOG -> PreferenceDefaults.AUTO_LOG
            LOG_LOCATION_DATA -> PreferenceDefaults.LOG_LOCATION_DATA
            AUTO_CAPTURE -> PreferenceDefaults.AUTO_CAPTURE
            AUTO_TORCH_ENABLED -> PreferenceDefaults.AUTO_TORCH_ENABLED
            AUTO_TORCH_USE_SUNSET -> PreferenceDefaults.AUTO_TORCH_USE_SUNSET
            else -> false
        }

    /** Default int value for this setting, or 0 if not an int setting. */
    val defaultInt: Int
        get() = when (this) {
            ALARM_ACTION -> PreferenceDefaults.ALARM_ACTION
            ALARM_FACTOR_1 -> PreferenceDefaults.ALARM_FACTOR_1
            ALARM_FACTOR_2 -> PreferenceDefaults.ALARM_FACTOR_2
            WARNING_SPEED -> PreferenceDefaults.WARNING_SPEED
            WARNING_PWM -> PreferenceDefaults.WARNING_PWM
            WARNING_SPEED_PERIOD -> PreferenceDefaults.WARNING_SPEED_PERIOD
            ALARM_1_SPEED -> PreferenceDefaults.ALARM_1_SPEED
            ALARM_1_BATTERY -> PreferenceDefaults.ALARM_1_BATTERY
            ALARM_2_SPEED -> PreferenceDefaults.ALARM_2_SPEED
            ALARM_2_BATTERY -> PreferenceDefaults.ALARM_2_BATTERY
            ALARM_3_SPEED -> PreferenceDefaults.ALARM_3_SPEED
            ALARM_3_BATTERY -> PreferenceDefaults.ALARM_3_BATTERY
            ALARM_CURRENT -> PreferenceDefaults.ALARM_CURRENT
            ALARM_PHASE_CURRENT -> PreferenceDefaults.ALARM_PHASE_CURRENT
            ALARM_TEMPERATURE -> PreferenceDefaults.ALARM_TEMPERATURE
            ALARM_MOTOR_TEMPERATURE -> PreferenceDefaults.ALARM_MOTOR_TEMPERATURE
            ALARM_BATTERY -> PreferenceDefaults.ALARM_BATTERY
            AUTO_TORCH_SPEED_THRESHOLD -> PreferenceDefaults.AUTO_TORCH_SPEED_THRESHOLD
            else -> 0
        }
}
