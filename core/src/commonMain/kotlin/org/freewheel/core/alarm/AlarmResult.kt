package org.freewheel.core.alarm

import org.freewheel.core.domain.AlarmType

/**
 * Result of an alarm check.
 * Contains all triggered alarms and their details.
 */
data class AlarmResult(
    /**
     * List of triggered alarms with their details.
     */
    val triggeredAlarms: List<TriggeredAlarm> = emptyList(),

    /**
     * Whether a pre-warning should be played (not a full alarm).
     */
    val preWarning: PreWarning? = null
) {
    /**
     * Whether any alarm is currently triggered.
     */
    val hasAlarm: Boolean get() = triggeredAlarms.isNotEmpty()

    /**
     * Bitmask of active alarms (for compatibility with existing code).
     * Bit 0: Speed alarm
     * Bit 1: Current alarm
     * Bit 2: Temperature alarm
     * Bit 3: Battery alarm
     * Bit 4: Wheel alarm
     */
    val alarmBitmask: Int get() {
        var mask = 0
        for (alarm in triggeredAlarms) {
            mask = mask or when (alarm.type) {
                AlarmType.SPEED1, AlarmType.SPEED2, AlarmType.SPEED3, AlarmType.PWM -> 0x01
                AlarmType.CURRENT -> 0x02
                AlarmType.TEMPERATURE -> 0x04
                AlarmType.BATTERY -> 0x08
                AlarmType.WHEEL -> 0x10
            }
        }
        return mask
    }

    /**
     * Get the most severe speed-related alarm (PWM > SPEED3 > SPEED2 > SPEED1).
     */
    val speedAlarm: TriggeredAlarm? get() = triggeredAlarms
        .filter { it.type in listOf(AlarmType.PWM, AlarmType.SPEED3, AlarmType.SPEED2, AlarmType.SPEED1) }
        .maxByOrNull { it.type.value }
}

/**
 * Details of a triggered alarm.
 */
data class TriggeredAlarm(
    /**
     * Type of alarm triggered.
     */
    val type: AlarmType,

    /**
     * The value that triggered the alarm (speed, current, temperature, etc.).
     */
    val triggerValue: Double,

    /**
     * The threshold that was exceeded.
     */
    val threshold: Double,

    /**
     * Suggested tone duration in milliseconds (for audio feedback).
     */
    val toneDuration: Int = 100
)

/**
 * Pre-warning (not a full alarm, just advisory).
 */
data class PreWarning(
    /**
     * Type of warning (PWM or Speed based).
     */
    val type: PreWarningType,

    /**
     * Current value that triggered the warning.
     */
    val value: Double
)

enum class PreWarningType {
    PWM,
    SPEED
}
