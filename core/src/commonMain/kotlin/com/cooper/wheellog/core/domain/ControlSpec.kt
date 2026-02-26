package com.cooper.wheellog.core.domain

/**
 * Identifies which command to execute on the wheel.
 * Used by both Android and iOS to dispatch settings changes.
 */
enum class SettingsCommandId {
    // Lighting
    LIGHT_MODE, LED, LED_MODE, STROBE_MODE, TAIL_LIGHT, DRL, LIGHT_BRIGHTNESS,
    // Ride
    PEDALS_MODE, ROLL_ANGLE_MODE, CUTOUT_ANGLE, HANDLE_BUTTON, BRAKE_ASSIST,
    RIDE_MODE, GO_HOME_MODE, FANCIER_MODE, TRANSPORT_MODE,
    PEDAL_TILT, PEDAL_SENSITIVITY,
    // Speed
    MAX_SPEED, LIMITED_MODE, LIMITED_SPEED,
    // Alarms
    ALARM_ENABLED_1, ALARM_ENABLED_2, ALARM_ENABLED_3,
    ALARM_SPEED_1, ALARM_SPEED_2, ALARM_SPEED_3,
    // Audio
    SPEAKER_VOLUME, BEEPER_VOLUME, MUTE,
    // Thermal
    FAN, FAN_QUIET,
    // Dangerous
    CALIBRATE, POWER_OFF, LOCK, RESET_TRIP;

    /** Read current int value from WheelState, or null if no readback. */
    fun readInt(state: WheelState): Int? = when (this) {
        PEDALS_MODE -> state.pedalsMode.takeIf { it >= 0 }
        LIGHT_MODE -> state.lightMode.takeIf { it >= 0 }
        LED_MODE -> state.ledMode.takeIf { it >= 0 }
        ROLL_ANGLE_MODE -> state.rollAngle.takeIf { it >= 0 }
        CUTOUT_ANGLE -> state.cutoutAngle.takeIf { it >= 0 }
        MAX_SPEED -> state.maxSpeed.takeIf { it >= 0 }
        PEDAL_TILT -> state.pedalTilt.takeIf { it >= 0 }?.let { it / 10 }
        PEDAL_SENSITIVITY -> state.pedalSensitivity.takeIf { it >= 0 }
        SPEAKER_VOLUME -> state.speakerVolume.takeIf { it >= 0 }
        BEEPER_VOLUME -> state.beeperVolume.takeIf { it >= 0 }
        LIGHT_BRIGHTNESS -> state.lightBrightness.takeIf { it >= 0 }
        else -> null
    }

    /** Read current bool value from WheelState, or null if no readback. */
    fun readBool(state: WheelState): Boolean? = when (this) {
        LED -> state.ledMode.takeIf { it >= 0 }?.let { it > 0 }
        RIDE_MODE -> state.rideMode
        FANCIER_MODE -> state.fancierMode
        MUTE -> state.mute
        HANDLE_BUTTON -> state.handleButton
        DRL -> state.drl
        TRANSPORT_MODE -> state.transportMode
        GO_HOME_MODE -> state.goHomeMode
        FAN_QUIET -> state.fanQuiet
        else -> null
    }
}

/** Describes a single UI control in wheel settings. */
sealed class ControlSpec {
    abstract val commandId: SettingsCommandId

    data class Toggle(
        val label: String,
        override val commandId: SettingsCommandId
    ) : ControlSpec()

    data class Segmented(
        val label: String,
        val options: List<String>,
        override val commandId: SettingsCommandId
    ) : ControlSpec()

    data class Picker(
        val label: String,
        val options: List<String>,
        override val commandId: SettingsCommandId
    ) : ControlSpec()

    data class Slider(
        val label: String,
        val min: Int,
        val max: Int,
        val unit: String,
        val defaultValue: Int,
        override val commandId: SettingsCommandId,
        val visibleWhen: SettingsCommandId? = null,
        val step: Int = 1
    ) : ControlSpec()

    data class DangerousButton(
        val label: String,
        val confirmTitle: String,
        val confirmMessage: String,
        override val commandId: SettingsCommandId
    ) : ControlSpec()

    data class DangerousToggle(
        val label: String,
        val confirmTitle: String,
        val confirmMessage: String,
        override val commandId: SettingsCommandId
    ) : ControlSpec()
}

data class SettingsSection(val title: String, val controls: List<ControlSpec>)
