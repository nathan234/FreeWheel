package org.freewheel.core.domain.identity

/**
 * Protocol family used as a connection-time hint.
 *
 * Distinct from [WheelType], which has two extra inhabitants ([WheelType.Unknown]
 * and [WheelType.GOTWAY_VIRTUAL]) that aren't valid hints — `Unknown` because a
 * hint must point somewhere, and `GOTWAY_VIRTUAL` because it's a sentinel decoder
 * selector chosen as a fallback during ambiguous service discovery, not a real
 * protocol any wheel speaks.
 *
 * Splitting hint type from decoder selector is the whole point of the type:
 * [fromWheelType] returns null for both, so passing them as a hint is a compile
 * error rather than a silent footgun.
 */
enum class ProtocolFamily {
    KINGSONG,
    GOTWAY,
    NINEBOT,
    NINEBOT_Z,
    INMOTION,
    LORIN,
    VETERAN,
    LEAPERKIM;

    fun toWheelType(): WheelType = when (this) {
        KINGSONG -> WheelType.KINGSONG
        GOTWAY -> WheelType.GOTWAY
        NINEBOT -> WheelType.NINEBOT
        NINEBOT_Z -> WheelType.NINEBOT_Z
        INMOTION -> WheelType.INMOTION
        LORIN -> WheelType.LORIN
        VETERAN -> WheelType.VETERAN
        LEAPERKIM -> WheelType.LEAPERKIM
    }

    companion object {
        fun fromWheelType(t: WheelType): ProtocolFamily? = when (t) {
            WheelType.KINGSONG -> KINGSONG
            WheelType.GOTWAY -> GOTWAY
            WheelType.NINEBOT -> NINEBOT
            WheelType.NINEBOT_Z -> NINEBOT_Z
            WheelType.INMOTION -> INMOTION
            WheelType.LORIN -> LORIN
            WheelType.VETERAN -> VETERAN
            WheelType.LEAPERKIM -> LEAPERKIM
            WheelType.GOTWAY_VIRTUAL -> null
            WheelType.Unknown -> null
        }
    }
}
