package org.freewheel.core.domain.identity

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
