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
    MAX_CHARGE_VOLTAGE, BRAKE_PRESSURE_ALARM, LATERAL_CUTOFF_ANGLE,
    DYNAMIC_ASSIST, ACCELERATION_LIMIT, WHEEL_DISPLAY_UNIT,
    // InMotion P6 settings
    SCREEN_AUTO_OFF;

    /**
     * True if this command is firmware-gated (not supported by all models of its WheelType).
     * Used by [CapabilitySet.supports] to detect wheels with version-dependent features.
     */
    val isExtended: Boolean get() = when (this) {
        // Veteran: mVer >= 3 required
        ALARM_SPEED_1, PEDAL_TILT, TRANSPORT_MODE, HIGH_SPEED_MODE, LOW_VOLTAGE_MODE,
        KEY_TONE, SCREEN_BACKLIGHT, STOP_SPEED, VETERAN_PWM_LIMIT, VOLTAGE_CORRECTION,
        MAX_CHARGE_VOLTAGE, BRAKE_PRESSURE_ALARM, LATERAL_CUTOFF_ANGLE,
        DYNAMIC_ASSIST, ACCELERATION_LIMIT, WHEEL_DISPLAY_UNIT,
        // InMotion V2: model/protoVer gated
        BERM_ANGLE_MODE, BERM_ANGLE, TURNING_SENSITIVITY, ONE_PEDAL_MODE,
        SPEEDING_BRAKING_MODE, SPEEDING_BRAKING_ANGLE, SOUND_WAVE, SOUND_WAVE_SENSITIVITY,
        SAFE_SPEED_LIMIT, BACKWARD_OVERSPEED_ALERT, TAIL_LIGHT_MODE, TURN_SIGNAL_MODE,
        LOGO_LIGHT_BRIGHTNESS, AUTO_HEADLIGHT, LIGHT_EFFECT, LIGHT_EFFECT_MODE,
        TWO_BATTERY_MODE, LOW_BATTERY_SAFE_MODE, SPIN_KILL, CRUISE, LOAD_DETECT,
        STANDBY_TIME, CHARGE_LIMIT, SCREEN_AUTO_OFF,
        // Begode extended
        WEAK_MAGNETISM, EXTENDED_ROLL_ANGLE, POWER_ALARM, PLATE_PROTECTION,
        // InMotion V2 thermal (V11-only)
        FAN_QUIET -> true
        // Base commands — always available for their wheel type
        LIGHT_MODE, LED, LED_MODE, STROBE_MODE, TAIL_LIGHT, DRL, LIGHT_BRIGHTNESS,
        PEDALS_MODE, ROLL_ANGLE_MODE, CUTOUT_ANGLE, HANDLE_BUTTON, BRAKE_ASSIST,
        RIDE_MODE, GO_HOME_MODE, FANCIER_MODE,
        PEDAL_SENSITIVITY, MAX_SPEED, LIMITED_MODE, LIMITED_SPEED,
        ALARM_ENABLED_1, ALARM_ENABLED_2, ALARM_ENABLED_3,
        ALARM_SPEED_2, ALARM_SPEED_3,
        SPEAKER_VOLUME, BEEPER_VOLUME, MUTE, FAN,
        CALIBRATE, POWER_OFF, LOCK, RESET_TRIP -> false
    }

    /** Read current int value from settings, or null if no readback. */
    fun readInt(settings: WheelSettings): Int? = when (this) {
        PEDALS_MODE -> settings.pedalsMode.takeIf { it >= 0 }
        LIGHT_MODE -> settings.lightMode.takeIf { it >= 0 }
        LED_MODE -> settings.ledMode.takeIf { it >= 0 }
        ROLL_ANGLE_MODE -> settings.rollAngle.takeIf { it >= 0 }
        CUTOUT_ANGLE -> settings.cutoutAngle.takeIf { it >= 0 }
        MAX_SPEED -> settings.maxSpeed.takeIf { it >= 0 }
        PEDAL_TILT -> settings.pedalTilt.takeIf { it >= 0 }?.let { it / 10 }
        PEDAL_SENSITIVITY -> settings.pedalSensitivity.takeIf { it >= 0 }
        SPEAKER_VOLUME -> settings.speakerVolume.takeIf { it >= 0 }
        BEEPER_VOLUME -> settings.beeperVolume.takeIf { it >= 0 }
        LIGHT_BRIGHTNESS -> settings.lightBrightness.takeIf { it >= 0 }
        BERM_ANGLE -> settings.bermAngle.takeIf { it >= 0 }
        TURNING_SENSITIVITY -> settings.turningSensitivity.takeIf { it >= 0 }
        SPEEDING_BRAKING_ANGLE -> settings.speedingBrakingAngle.takeIf { it >= 0 }
        SOUND_WAVE_SENSITIVITY -> settings.soundWaveSensitivity.takeIf { it >= 0 }
        TAIL_LIGHT_MODE -> settings.tailLightMode.takeIf { it >= 0 }
        TURN_SIGNAL_MODE -> settings.turnSignalMode.takeIf { it >= 0 }
        LOGO_LIGHT_BRIGHTNESS -> settings.logoLightBrightness.takeIf { it >= 0 }
        LIGHT_EFFECT_MODE -> settings.lightEffectMode.takeIf { it >= 0 }
        STANDBY_TIME -> settings.standbyTime.takeIf { it >= 0 }
        CHARGE_LIMIT -> settings.chargeLimit.takeIf { it >= 0 }
        WEAK_MAGNETISM -> settings.weakMagnetism.takeIf { it >= 0 }
        EXTENDED_ROLL_ANGLE -> settings.extendedRollAngle.takeIf { it >= 0 }
        POWER_ALARM -> settings.powerAlarm.takeIf { it >= 0 }
        KEY_TONE -> settings.keyTone.takeIf { it >= 0 }
        SCREEN_BACKLIGHT -> settings.screenBacklight.takeIf { it >= 0 }
        STOP_SPEED -> settings.stopSpeed.takeIf { it >= 0 }
        VETERAN_PWM_LIMIT -> settings.pwmLimit.takeIf { it >= 0 }
        VOLTAGE_CORRECTION -> settings.voltageCorrection.takeIf { it > -16 } // -15..+15, raw signed
        MAX_CHARGE_VOLTAGE -> settings.maxChargeVoltage.takeIf { it >= 0 }
        BRAKE_PRESSURE_ALARM -> settings.brakePressureAlarm.takeIf { it >= 0 }
        LATERAL_CUTOFF_ANGLE -> settings.lateralCutoffAngle.takeIf { it >= 0 }
        DYNAMIC_ASSIST -> settings.dynamicAssist.takeIf { it >= 0 }
        ACCELERATION_LIMIT -> settings.accelerationLimit.takeIf { it >= 0 }
        WHEEL_DISPLAY_UNIT -> settings.wheelDisplayUnit.takeIf { it >= 0 }
        // No int readback: bool-only, action-only, or no readback field
        LED, STROBE_MODE, TAIL_LIGHT, DRL, HANDLE_BUTTON, BRAKE_ASSIST,
        RIDE_MODE, GO_HOME_MODE, FANCIER_MODE, TRANSPORT_MODE,
        LIMITED_MODE, LIMITED_SPEED,
        ALARM_ENABLED_1, ALARM_ENABLED_2, ALARM_ENABLED_3,
        ALARM_SPEED_1, ALARM_SPEED_2, ALARM_SPEED_3,
        MUTE, FAN, FAN_QUIET,
        CALIBRATE, POWER_OFF, LOCK, RESET_TRIP,
        BERM_ANGLE_MODE, ONE_PEDAL_MODE, SPEEDING_BRAKING_MODE,
        SOUND_WAVE, SAFE_SPEED_LIMIT, BACKWARD_OVERSPEED_ALERT,
        AUTO_HEADLIGHT, LIGHT_EFFECT, TWO_BATTERY_MODE, LOW_BATTERY_SAFE_MODE,
        SPIN_KILL, CRUISE, LOAD_DETECT, PLATE_PROTECTION,
        HIGH_SPEED_MODE, LOW_VOLTAGE_MODE, SCREEN_AUTO_OFF -> null
    }

    /** Read current bool value from settings, or null if no readback. */
    fun readBool(settings: WheelSettings): Boolean? = when (this) {
        LED -> settings.ledMode.takeIf { it >= 0 }?.let { it > 0 }
        RIDE_MODE -> settings.rideMode
        FANCIER_MODE -> settings.fancierMode
        MUTE -> settings.mute
        HANDLE_BUTTON -> settings.handleButton
        DRL -> settings.drl
        TRANSPORT_MODE -> settings.transportMode
        GO_HOME_MODE -> settings.goHomeMode
        FAN_QUIET -> settings.fanQuiet
        BERM_ANGLE_MODE -> settings.bermAngleMode
        ONE_PEDAL_MODE -> settings.onePedalMode
        SPEEDING_BRAKING_MODE -> settings.speedingBrakingMode
        SOUND_WAVE -> settings.soundWave
        SAFE_SPEED_LIMIT -> settings.safeSpeedLimit
        BACKWARD_OVERSPEED_ALERT -> settings.backwardOverspeedAlert
        AUTO_HEADLIGHT -> settings.autoHeadlight
        LIGHT_EFFECT -> settings.lightEffect
        TWO_BATTERY_MODE -> settings.twoBatteryMode
        LOW_BATTERY_SAFE_MODE -> settings.lowBatterySafeMode
        SPIN_KILL -> settings.spinKill
        CRUISE -> settings.cruise
        LOAD_DETECT -> settings.loadDetect
        PLATE_PROTECTION -> settings.plateProtection
        HIGH_SPEED_MODE -> settings.highSpeedMode
        LOW_VOLTAGE_MODE -> settings.lowVoltageMode
        // No bool readback: int-only, action-only, or no readback field
        PEDALS_MODE, LIGHT_MODE, LED_MODE, STROBE_MODE, TAIL_LIGHT,
        ROLL_ANGLE_MODE, CUTOUT_ANGLE, BRAKE_ASSIST, LIGHT_BRIGHTNESS,
        PEDAL_TILT, PEDAL_SENSITIVITY, MAX_SPEED, LIMITED_MODE, LIMITED_SPEED,
        ALARM_ENABLED_1, ALARM_ENABLED_2, ALARM_ENABLED_3,
        ALARM_SPEED_1, ALARM_SPEED_2, ALARM_SPEED_3,
        SPEAKER_VOLUME, BEEPER_VOLUME, FAN,
        CALIBRATE, POWER_OFF, LOCK, RESET_TRIP,
        BERM_ANGLE, TURNING_SENSITIVITY, SPEEDING_BRAKING_ANGLE,
        SOUND_WAVE_SENSITIVITY, TAIL_LIGHT_MODE, TURN_SIGNAL_MODE,
        LOGO_LIGHT_BRIGHTNESS, LIGHT_EFFECT_MODE,
        STANDBY_TIME, CHARGE_LIMIT,
        WEAK_MAGNETISM, EXTENDED_ROLL_ANGLE, POWER_ALARM,
        KEY_TONE, SCREEN_BACKLIGHT, STOP_SPEED, VETERAN_PWM_LIMIT,
        VOLTAGE_CORRECTION, MAX_CHARGE_VOLTAGE, BRAKE_PRESSURE_ALARM,
        LATERAL_CUTOFF_ANGLE, DYNAMIC_ASSIST, ACCELERATION_LIMIT,
        WHEEL_DISPLAY_UNIT, SCREEN_AUTO_OFF -> null
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
