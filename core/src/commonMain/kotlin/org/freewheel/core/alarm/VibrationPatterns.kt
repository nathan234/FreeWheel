package org.freewheel.core.alarm

import org.freewheel.core.domain.AlarmType

/**
 * Vibration patterns for different alarm types.
 * Each pattern is an array of milliseconds: [delay, vibrate, pause, vibrate, ...]
 *
 * These are platform-agnostic definitions. Each platform implements
 * the actual vibration using their native APIs.
 */
object VibrationPatterns {

    /**
     * Get the vibration pattern for a given alarm type.
     *
     * @param alarmType The type of alarm
     * @return Array of milliseconds: [initial delay, vibrate duration, pause, vibrate, ...]
     */
    fun forAlarmType(alarmType: AlarmType): LongArray {
        return when (alarmType) {
            AlarmType.SPEED1,
            AlarmType.SPEED2,
            AlarmType.SPEED3,
            AlarmType.PWM -> SPEED_PATTERN

            AlarmType.CURRENT -> CURRENT_PATTERN

            AlarmType.TEMPERATURE -> TEMPERATURE_PATTERN

            AlarmType.BATTERY -> BATTERY_PATTERN

            AlarmType.WHEEL -> WHEEL_PATTERN
        }
    }

    /**
     * Speed/PWM alarm: short bursts
     * Pattern: vibrate 100ms, pause 100ms
     */
    val SPEED_PATTERN = longArrayOf(0, 100, 100)

    /**
     * Current alarm: rapid pulses
     * Pattern: vibrate 50ms, pause 50ms, repeat
     */
    val CURRENT_PATTERN = longArrayOf(0, 50, 50, 50, 50)

    /**
     * Temperature alarm: long pulses
     * Pattern: vibrate 500ms, pause 500ms
     */
    val TEMPERATURE_PATTERN = longArrayOf(0, 500, 500)

    /**
     * Battery alarm: distinctive pattern
     * Pattern: short vibrate, long pause
     */
    val BATTERY_PATTERN = longArrayOf(0, 100, 500)

    /**
     * Wheel-reported alarm: quick double pulse
     * Pattern: vibrate 50ms, pause 50ms
     */
    val WHEEL_PATTERN = longArrayOf(0, 50, 50)
}
