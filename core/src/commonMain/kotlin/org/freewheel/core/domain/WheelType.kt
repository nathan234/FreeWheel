package org.freewheel.core.domain

/**
 * Enumeration of supported electric unicycle (EUC) wheel types.
 * Each type corresponds to a specific manufacturer's BLE protocol.
 */
enum class WheelType {
    /** Unknown or undetected wheel type */
    Unknown,

    /** KingSong wheels (e.g., KS-16X, KS-18XL, KS-S18, KS-S22) */
    KINGSONG,

    /** Gotway/Begode wheels (e.g., MSP, RS, Monster, Nikola) */
    GOTWAY,

    /** Ninebot wheels (legacy protocol) */
    NINEBOT,

    /** Ninebot Z-series wheels (e.g., Z10) */
    NINEBOT_Z,

    /** InMotion wheels V1 protocol (e.g., V8, V10, V11) */
    INMOTION,

    /** InMotion wheels V2 protocol (e.g., V12, V13, V14) */
    INMOTION_V2,

    /** Leaperkim/Nosfet wheels legacy protocol (e.g., Sherman, Lynx, Apex) */
    VETERAN,

    /** Leaperkim CAN-over-BLE protocol (newer firmware/models) */
    LEAPERKIM,

    /** Virtual Gotway adapter for testing/simulation */
    GOTWAY_VIRTUAL;

    /** Human-readable manufacturer name. */
    val displayName: String get() = when (this) {
        KINGSONG -> "KingSong"
        GOTWAY, GOTWAY_VIRTUAL -> "Begode"
        NINEBOT, NINEBOT_Z -> "Ninebot"
        INMOTION, INMOTION_V2 -> "InMotion"
        VETERAN -> ""
        LEAPERKIM -> "Leaperkim"
        Unknown -> ""
    }

    companion object {
        /**
         * Returns WheelType from string name, case-insensitive.
         * Returns [Unknown] if no match is found.
         */
        fun fromString(name: String): WheelType {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: Unknown
        }
    }
}

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
