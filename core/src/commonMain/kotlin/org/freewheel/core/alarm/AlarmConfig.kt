package org.freewheel.core.alarm

/**
 * Configuration for alarm thresholds.
 * All speed values are in km/h, temperatures in Celsius, currents in Amps.
 */
data class AlarmConfig(
    // PWM-based alarms (modern mode)
    val pwmBasedAlarms: Boolean = false,
    val alarmFactor1: Int = 80,  // PWM % to start alarm
    val alarmFactor2: Int = 90,  // PWM % for max alarm intensity

    // Pre-warning settings
    val warningPwm: Int = 0,           // PWM % for warning (0 = disabled)
    val warningSpeed: Int = 0,         // Speed for warning (0 = disabled)
    val warningSpeedPeriod: Int = 0,   // Seconds between warnings

    // Old-style speed alarms (speed + battery threshold)
    val alarm1Speed: Int = 0,    // Speed threshold for alarm 1
    val alarm1Battery: Int = 0,  // Battery must be <= this for alarm 1
    val alarm2Speed: Int = 0,
    val alarm2Battery: Int = 0,
    val alarm3Speed: Int = 0,
    val alarm3Battery: Int = 0,

    // Current alarm
    val alarmCurrent: Int = 0,       // Current in Amps (0 = disabled)
    val alarmPhaseCurrent: Int = 0,  // Phase current in Amps (0 = disabled)

    // Temperature alarm
    val alarmTemperature: Int = 0,       // Board temp in Celsius (0 = disabled)
    val alarmMotorTemperature: Int = 0,  // Motor temp in Celsius (0 = disabled)

    // Battery alarm
    val alarmBattery: Int = 0,  // Battery % threshold (0 = disabled)

    // Wheel-reported alarm
    val alarmWheel: Boolean = false  // React to wheel's own alarm flag
)
