package org.freewheel.core.domain.common

/**
 * Alarm types that can be triggered by the wheel or app.
 */
enum class AlarmType(val value: Int) {
    SPEED1(1),
    SPEED2(2),
    SPEED3(3),
    CURRENT(4),
    TEMPERATURE(5),
    PWM(6),
    BATTERY(7),
    WHEEL(8);

    val displayName: String get() = when (this) {
        SPEED1 -> "Speed 1"
        SPEED2 -> "Speed 2"
        SPEED3 -> "Speed 3"
        CURRENT -> "Current"
        TEMPERATURE -> "Temp"
        PWM -> "PWM"
        BATTERY -> "Battery"
        WHEEL -> "Wheel"
    }

    val alarmMessage: String get() = when (this) {
        SPEED1 -> "Speed alarm 1 triggered"
        SPEED2 -> "Speed alarm 2 triggered"
        SPEED3 -> "Speed alarm 3 triggered"
        CURRENT -> "Current alarm triggered"
        TEMPERATURE -> "Temperature alarm triggered"
        PWM -> "PWM alarm triggered"
        BATTERY -> "Low battery alarm triggered"
        WHEEL -> "Wheel alarm triggered"
    }

    /** Audio frequency in Hz for alarm tone generation. */
    val audioFrequencyHz: Int get() = when (this) {
        SPEED1, SPEED2, SPEED3, PWM -> 1000
        CURRENT -> 800
        TEMPERATURE -> 600
        BATTERY -> 400
        WHEEL -> 1200
    }

    companion object {
        fun fromValue(value: Int): AlarmType? = entries.find { it.value == value }
    }
}
