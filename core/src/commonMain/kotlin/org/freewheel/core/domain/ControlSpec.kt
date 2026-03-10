package org.freewheel.core.domain

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
    CALIBRATE, POWER_OFF, LOCK, RESET_TRIP,
    // InMotion V2 extended
    BERM_ANGLE_MODE, BERM_ANGLE,
    TURNING_SENSITIVITY, ONE_PEDAL_MODE,
    SPEEDING_BRAKING_MODE, SPEEDING_BRAKING_ANGLE,
    SOUND_WAVE, SOUND_WAVE_SENSITIVITY,
    SAFE_SPEED_LIMIT, BACKWARD_OVERSPEED_ALERT,
    TAIL_LIGHT_MODE, TURN_SIGNAL_MODE,
    LOGO_LIGHT_BRIGHTNESS, AUTO_HEADLIGHT,
    LIGHT_EFFECT, LIGHT_EFFECT_MODE,
    TWO_BATTERY_MODE, LOW_BATTERY_SAFE_MODE,
    SPIN_KILL, CRUISE, LOAD_DETECT,
    STANDBY_TIME, CHARGE_LIMIT,
    // Begode extended settings
    WEAK_MAGNETISM, EXTENDED_ROLL_ANGLE, POWER_ALARM, PLATE_PROTECTION,
    // Veteran extended settings
    HIGH_SPEED_MODE, LOW_VOLTAGE_MODE, KEY_TONE,
    SCREEN_BACKLIGHT, STOP_SPEED, VETERAN_PWM_LIMIT, VOLTAGE_CORRECTION,
    MAX_CHARGE_VOLTAGE, LATERAL_CUTOFF_ANGLE;

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
        BERM_ANGLE -> state.bermAngle.takeIf { it >= 0 }
        TURNING_SENSITIVITY -> state.turningSensitivity.takeIf { it >= 0 }
        SPEEDING_BRAKING_ANGLE -> state.speedingBrakingAngle.takeIf { it >= 0 }
        SOUND_WAVE_SENSITIVITY -> state.soundWaveSensitivity.takeIf { it >= 0 }
        TAIL_LIGHT_MODE -> state.tailLightMode.takeIf { it >= 0 }
        TURN_SIGNAL_MODE -> state.turnSignalMode.takeIf { it >= 0 }
        LOGO_LIGHT_BRIGHTNESS -> state.logoLightBrightness.takeIf { it >= 0 }
        LIGHT_EFFECT_MODE -> state.lightEffectMode.takeIf { it >= 0 }
        STANDBY_TIME -> state.standbyTime.takeIf { it >= 0 }
        CHARGE_LIMIT -> state.chargeLimit.takeIf { it >= 0 }
        WEAK_MAGNETISM -> state.weakMagnetism.takeIf { it >= 0 }
        EXTENDED_ROLL_ANGLE -> state.extendedRollAngle.takeIf { it >= 0 }
        POWER_ALARM -> state.powerAlarm.takeIf { it >= 0 }
        KEY_TONE -> state.keyTone.takeIf { it >= 0 }
        SCREEN_BACKLIGHT -> state.screenBacklight.takeIf { it >= 0 }
        STOP_SPEED -> state.stopSpeed.takeIf { it >= 0 }
        VETERAN_PWM_LIMIT -> state.pwmLimit.takeIf { it >= 0 }
        VOLTAGE_CORRECTION -> state.voltageCorrection.takeIf { it > -16 } // -15..+15, raw signed
        MAX_CHARGE_VOLTAGE -> state.maxChargeVoltage.takeIf { it >= 0 }
        LATERAL_CUTOFF_ANGLE -> state.lateralCutoffAngle.takeIf { it >= 0 }
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
        BERM_ANGLE_MODE -> state.bermAngleMode
        ONE_PEDAL_MODE -> state.onePedalMode
        SPEEDING_BRAKING_MODE -> state.speedingBrakingMode
        SOUND_WAVE -> state.soundWave
        SAFE_SPEED_LIMIT -> state.safeSpeedLimit
        BACKWARD_OVERSPEED_ALERT -> state.backwardOverspeedAlert
        AUTO_HEADLIGHT -> state.autoHeadlight
        LIGHT_EFFECT -> state.lightEffect
        TWO_BATTERY_MODE -> state.twoBatteryMode
        LOW_BATTERY_SAFE_MODE -> state.lowBatterySafeMode
        SPIN_KILL -> state.spinKill
        CRUISE -> state.cruise
        LOAD_DETECT -> state.loadDetect
        PLATE_PROTECTION -> state.plateProtection
        HIGH_SPEED_MODE -> state.highSpeedMode
        LOW_VOLTAGE_MODE -> state.lowVoltageMode
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
