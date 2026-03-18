package org.freewheel.core.domain

/**
 * Type-safe, per-wheel-type settings state.
 *
 * Replaces the flat [WheelSettingsState] with a sealed hierarchy where each
 * variant holds only the settings fields relevant to that wheel type.
 * Extension properties below provide uniform field access for [SettingsCommandId.readInt]
 * and [SettingsCommandId.readBool].
 *
 * Default value of -1 means "not yet read from wheel" (same convention as [WheelState]).
 */
sealed class WheelSettings {
    data object None : WheelSettings()

    data class Begode(
        val pedalsMode: Int = -1,
        val speedAlarms: Int = -1,
        val rollAngle: Int = -1,
        val tiltBackSpeed: Int = 0,
        val lightMode: Int = -1,
        val ledMode: Int = -1,
        val cutoutAngle: Int = -1,
        val beeperVolume: Int = -1,
        val inMiles: Boolean = false,
        val weakMagnetism: Int = -1,
        val extendedRollAngle: Int = -1,
        val powerAlarm: Int = -1,
        val plateProtection: Boolean = false
    ) : WheelSettings()

    data class Kingsong(
        val pedalsMode: Int = -1,
        val lightMode: Int = -1,
        val ledMode: Int = -1,
        val mute: Boolean = false,
        val handleButton: Boolean = false,
        val ksAlarm1Speed: Int = -1,
        val ksAlarm2Speed: Int = -1,
        val ksAlarm3Speed: Int = -1,
        val ksTiltbackSpeed: Int = -1,
        val autoOffTime: Int = 0,
        val lockState: Int = -1
    ) : WheelSettings()

    data class Veteran(
        val pedalsMode: Int = -1,
        val lightMode: Int = -1,
        val tiltBackSpeed: Int = 0,
        val alertSpeed: Int = 0,
        val autoOffTime: Int = 0,
        val lockState: Int = -1,
        val highSpeedMode: Boolean = false,
        val lowVoltageMode: Boolean = false,
        val voltageCorrection: Int = -1,
        val transportMode: Boolean = false,
        val keyTone: Int = -1,
        val pedalSensitivity: Int = -1,
        val stopSpeed: Int = -1,
        val pwmLimit: Int = -1,
        val screenBacklight: Int = -1,
        val maxChargeVoltage: Int = -1,
        val brakePressureAlarm: Int = -1,
        val lateralCutoffAngle: Int = -1,
        val dynamicAssist: Int = -1,
        val accelerationLimit: Int = -1,
        val chargeVoltageBase: Int = 145,
        val wheelDisplayUnit: Int = -1,
        val batteryTempMode: Int = 0
    ) : WheelSettings()

    data class LeaperkimCan(
        val pedalTilt: Int = -1,
        val lightMode: Int = -1,
        val pedalSensitivity: Int = -1,
        val rideMode: Boolean = false,
        val handleButton: Boolean = false,
        val ledMode: Int = -1,
        val transportMode: Boolean = false
    ) : WheelSettings()

    data class InMotionV2(
        val pedalsMode: Int = -1,
        val maxSpeed: Int = -1,
        val pedalTilt: Int = -1,
        val pedalSensitivity: Int = -1,
        val rideMode: Boolean = false,
        val fancierMode: Boolean = false,
        val speakerVolume: Int = -1,
        val mute: Boolean = false,
        val handleButton: Boolean = false,
        val drl: Boolean = false,
        val lightBrightness: Int = -1,
        val transportMode: Boolean = false,
        val goHomeMode: Boolean = false,
        val fanQuiet: Boolean = false,
        val lightMode: Int = -1,
        val ledMode: Int = -1,
        val cutoutAngle: Int = -1,
        val bermAngleMode: Boolean = false,
        val bermAngle: Int = -1,
        val turningSensitivity: Int = -1,
        val onePedalMode: Boolean = false,
        val speedingBrakingMode: Boolean = false,
        val speedingBrakingAngle: Int = -1,
        val soundWave: Boolean = false,
        val soundWaveSensitivity: Int = -1,
        val safeSpeedLimit: Boolean = false,
        val backwardOverspeedAlert: Boolean = false,
        val tailLightMode: Int = -1,
        val turnSignalMode: Int = -1,
        val logoLightBrightness: Int = -1,
        val autoHeadlight: Boolean = false,
        val lightEffect: Boolean = false,
        val lightEffectMode: Int = -1,
        val twoBatteryMode: Boolean = false,
        val lowBatterySafeMode: Boolean = false,
        val spinKill: Boolean = false,
        val cruise: Boolean = false,
        val loadDetect: Boolean = false,
        val standbyTime: Int = -1,
        val chargeLimit: Int = -1
    ) : WheelSettings()

    data class InMotionV1(
        val lightMode: Int = -1
    ) : WheelSettings()

    data object Ninebot : WheelSettings()

    data class NinebotZ(
        val lightMode: Int = -1,
        val drl: Boolean = false,
        val ledMode: Int = -1,
        val handleButton: Boolean = false,
        val pedalsMode: Int = -1,
        val pedalSensitivity: Int = -1,
        val speakerVolume: Int = -1
    ) : WheelSettings()
}

// ==================== Extension Properties ====================
//
// Provide flat field access across all variants, used by
// SettingsCommandId.readInt/readBool and WheelState.compose().
// Returns -1 (Int) or false (Boolean) for variants that don't have the field.

// --- Int fields ---

val WheelSettings.pedalsMode: Int get() = when (this) {
    is WheelSettings.Begode -> pedalsMode
    is WheelSettings.Kingsong -> pedalsMode
    is WheelSettings.Veteran -> pedalsMode
    is WheelSettings.InMotionV2 -> pedalsMode
    is WheelSettings.NinebotZ -> pedalsMode
    is WheelSettings.LeaperkimCan, is WheelSettings.InMotionV1,
    is WheelSettings.Ninebot, is WheelSettings.None -> -1
}

val WheelSettings.lightMode: Int get() = when (this) {
    is WheelSettings.Begode -> lightMode
    is WheelSettings.Kingsong -> lightMode
    is WheelSettings.Veteran -> lightMode
    is WheelSettings.LeaperkimCan -> lightMode
    is WheelSettings.InMotionV2 -> lightMode
    is WheelSettings.InMotionV1 -> lightMode
    is WheelSettings.NinebotZ -> lightMode
    is WheelSettings.Ninebot, is WheelSettings.None -> -1
}

val WheelSettings.ledMode: Int get() = when (this) {
    is WheelSettings.Begode -> ledMode
    is WheelSettings.Kingsong -> ledMode
    is WheelSettings.LeaperkimCan -> ledMode
    is WheelSettings.InMotionV2 -> ledMode
    is WheelSettings.NinebotZ -> ledMode
    is WheelSettings.Veteran, is WheelSettings.InMotionV1,
    is WheelSettings.Ninebot, is WheelSettings.None -> -1
}

val WheelSettings.cutoutAngle: Int get() = when (this) {
    is WheelSettings.Begode -> cutoutAngle
    is WheelSettings.InMotionV2 -> cutoutAngle
    is WheelSettings.Kingsong, is WheelSettings.Veteran, is WheelSettings.LeaperkimCan,
    is WheelSettings.InMotionV1, is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> -1
}

val WheelSettings.maxSpeed: Int get() = (this as? WheelSettings.InMotionV2)?.maxSpeed ?: -1

val WheelSettings.pedalTilt: Int get() = when (this) {
    is WheelSettings.LeaperkimCan -> pedalTilt
    is WheelSettings.InMotionV2 -> pedalTilt
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.Veteran,
    is WheelSettings.InMotionV1, is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> -1
}

val WheelSettings.pedalSensitivity: Int get() = when (this) {
    is WheelSettings.Veteran -> pedalSensitivity
    is WheelSettings.LeaperkimCan -> pedalSensitivity
    is WheelSettings.InMotionV2 -> pedalSensitivity
    is WheelSettings.NinebotZ -> pedalSensitivity
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.InMotionV1,
    is WheelSettings.Ninebot, is WheelSettings.None -> -1
}

val WheelSettings.speakerVolume: Int get() = when (this) {
    is WheelSettings.InMotionV2 -> speakerVolume
    is WheelSettings.NinebotZ -> speakerVolume
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.Veteran,
    is WheelSettings.LeaperkimCan, is WheelSettings.InMotionV1,
    is WheelSettings.Ninebot, is WheelSettings.None -> -1
}

val WheelSettings.beeperVolume: Int get() = (this as? WheelSettings.Begode)?.beeperVolume ?: -1
val WheelSettings.lightBrightness: Int get() = (this as? WheelSettings.InMotionV2)?.lightBrightness ?: -1
val WheelSettings.bermAngle: Int get() = (this as? WheelSettings.InMotionV2)?.bermAngle ?: -1
val WheelSettings.turningSensitivity: Int get() = (this as? WheelSettings.InMotionV2)?.turningSensitivity ?: -1
val WheelSettings.speedingBrakingAngle: Int get() = (this as? WheelSettings.InMotionV2)?.speedingBrakingAngle ?: -1
val WheelSettings.soundWaveSensitivity: Int get() = (this as? WheelSettings.InMotionV2)?.soundWaveSensitivity ?: -1
val WheelSettings.tailLightMode: Int get() = (this as? WheelSettings.InMotionV2)?.tailLightMode ?: -1
val WheelSettings.turnSignalMode: Int get() = (this as? WheelSettings.InMotionV2)?.turnSignalMode ?: -1
val WheelSettings.logoLightBrightness: Int get() = (this as? WheelSettings.InMotionV2)?.logoLightBrightness ?: -1
val WheelSettings.lightEffectMode: Int get() = (this as? WheelSettings.InMotionV2)?.lightEffectMode ?: -1
val WheelSettings.standbyTime: Int get() = (this as? WheelSettings.InMotionV2)?.standbyTime ?: -1
val WheelSettings.chargeLimit: Int get() = (this as? WheelSettings.InMotionV2)?.chargeLimit ?: -1
val WheelSettings.weakMagnetism: Int get() = (this as? WheelSettings.Begode)?.weakMagnetism ?: -1
val WheelSettings.extendedRollAngle: Int get() = (this as? WheelSettings.Begode)?.extendedRollAngle ?: -1
val WheelSettings.powerAlarm: Int get() = (this as? WheelSettings.Begode)?.powerAlarm ?: -1
val WheelSettings.rollAngle: Int get() = (this as? WheelSettings.Begode)?.rollAngle ?: -1
val WheelSettings.speedAlarms: Int get() = (this as? WheelSettings.Begode)?.speedAlarms ?: -1
val WheelSettings.keyTone: Int get() = (this as? WheelSettings.Veteran)?.keyTone ?: -1
val WheelSettings.screenBacklight: Int get() = (this as? WheelSettings.Veteran)?.screenBacklight ?: -1
val WheelSettings.stopSpeed: Int get() = (this as? WheelSettings.Veteran)?.stopSpeed ?: -1
val WheelSettings.pwmLimit: Int get() = (this as? WheelSettings.Veteran)?.pwmLimit ?: -1
val WheelSettings.voltageCorrection: Int get() = (this as? WheelSettings.Veteran)?.voltageCorrection ?: -1
val WheelSettings.maxChargeVoltage: Int get() = (this as? WheelSettings.Veteran)?.maxChargeVoltage ?: -1
val WheelSettings.brakePressureAlarm: Int get() = (this as? WheelSettings.Veteran)?.brakePressureAlarm ?: -1
val WheelSettings.lateralCutoffAngle: Int get() = (this as? WheelSettings.Veteran)?.lateralCutoffAngle ?: -1
val WheelSettings.dynamicAssist: Int get() = (this as? WheelSettings.Veteran)?.dynamicAssist ?: -1
val WheelSettings.accelerationLimit: Int get() = (this as? WheelSettings.Veteran)?.accelerationLimit ?: -1
val WheelSettings.wheelDisplayUnit: Int get() = (this as? WheelSettings.Veteran)?.wheelDisplayUnit ?: -1

val WheelSettings.lockState: Int get() = when (this) {
    is WheelSettings.Kingsong -> lockState
    is WheelSettings.Veteran -> lockState
    is WheelSettings.Begode, is WheelSettings.LeaperkimCan, is WheelSettings.InMotionV2,
    is WheelSettings.InMotionV1, is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> -1
}

val WheelSettings.ksAlarm1Speed: Int get() = (this as? WheelSettings.Kingsong)?.ksAlarm1Speed ?: -1
val WheelSettings.ksAlarm2Speed: Int get() = (this as? WheelSettings.Kingsong)?.ksAlarm2Speed ?: -1
val WheelSettings.ksAlarm3Speed: Int get() = (this as? WheelSettings.Kingsong)?.ksAlarm3Speed ?: -1
val WheelSettings.ksTiltbackSpeed: Int get() = (this as? WheelSettings.Kingsong)?.ksTiltbackSpeed ?: -1

val WheelSettings.tiltBackSpeed: Int get() = when (this) {
    is WheelSettings.Begode -> tiltBackSpeed
    is WheelSettings.Veteran -> tiltBackSpeed
    is WheelSettings.Kingsong, is WheelSettings.LeaperkimCan, is WheelSettings.InMotionV2,
    is WheelSettings.InMotionV1, is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> 0
}

val WheelSettings.alertSpeed: Int get() = (this as? WheelSettings.Veteran)?.alertSpeed ?: 0

val WheelSettings.autoOffTime: Int get() = when (this) {
    is WheelSettings.Kingsong -> autoOffTime
    is WheelSettings.Veteran -> autoOffTime
    is WheelSettings.Begode, is WheelSettings.LeaperkimCan, is WheelSettings.InMotionV2,
    is WheelSettings.InMotionV1, is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> 0
}

val WheelSettings.chargeVoltageBase: Int get() = (this as? WheelSettings.Veteran)?.chargeVoltageBase ?: 145
val WheelSettings.batteryTempMode: Int get() = (this as? WheelSettings.Veteran)?.batteryTempMode ?: 0

// --- Boolean fields ---

val WheelSettings.inMiles: Boolean get() = (this as? WheelSettings.Begode)?.inMiles ?: false
val WheelSettings.plateProtection: Boolean get() = (this as? WheelSettings.Begode)?.plateProtection ?: false

val WheelSettings.rideMode: Boolean get() = when (this) {
    is WheelSettings.LeaperkimCan -> rideMode
    is WheelSettings.InMotionV2 -> rideMode
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.Veteran,
    is WheelSettings.InMotionV1, is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> false
}

val WheelSettings.fancierMode: Boolean get() = (this as? WheelSettings.InMotionV2)?.fancierMode ?: false

val WheelSettings.mute: Boolean get() = when (this) {
    is WheelSettings.Kingsong -> mute
    is WheelSettings.InMotionV2 -> mute
    is WheelSettings.Begode, is WheelSettings.Veteran, is WheelSettings.LeaperkimCan,
    is WheelSettings.InMotionV1, is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> false
}

val WheelSettings.handleButton: Boolean get() = when (this) {
    is WheelSettings.Kingsong -> handleButton
    is WheelSettings.LeaperkimCan -> handleButton
    is WheelSettings.InMotionV2 -> handleButton
    is WheelSettings.NinebotZ -> handleButton
    is WheelSettings.Begode, is WheelSettings.Veteran, is WheelSettings.InMotionV1,
    is WheelSettings.Ninebot, is WheelSettings.None -> false
}

val WheelSettings.drl: Boolean get() = when (this) {
    is WheelSettings.InMotionV2 -> drl
    is WheelSettings.NinebotZ -> drl
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.Veteran,
    is WheelSettings.LeaperkimCan, is WheelSettings.InMotionV1,
    is WheelSettings.Ninebot, is WheelSettings.None -> false
}

val WheelSettings.transportMode: Boolean get() = when (this) {
    is WheelSettings.Veteran -> transportMode
    is WheelSettings.LeaperkimCan -> transportMode
    is WheelSettings.InMotionV2 -> transportMode
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.InMotionV1,
    is WheelSettings.Ninebot, is WheelSettings.NinebotZ, is WheelSettings.None -> false
}

val WheelSettings.goHomeMode: Boolean get() = (this as? WheelSettings.InMotionV2)?.goHomeMode ?: false
val WheelSettings.fanQuiet: Boolean get() = (this as? WheelSettings.InMotionV2)?.fanQuiet ?: false
val WheelSettings.bermAngleMode: Boolean get() = (this as? WheelSettings.InMotionV2)?.bermAngleMode ?: false
val WheelSettings.onePedalMode: Boolean get() = (this as? WheelSettings.InMotionV2)?.onePedalMode ?: false
val WheelSettings.speedingBrakingMode: Boolean get() = (this as? WheelSettings.InMotionV2)?.speedingBrakingMode ?: false
val WheelSettings.soundWave: Boolean get() = (this as? WheelSettings.InMotionV2)?.soundWave ?: false
val WheelSettings.safeSpeedLimit: Boolean get() = (this as? WheelSettings.InMotionV2)?.safeSpeedLimit ?: false
val WheelSettings.backwardOverspeedAlert: Boolean get() = (this as? WheelSettings.InMotionV2)?.backwardOverspeedAlert ?: false
val WheelSettings.autoHeadlight: Boolean get() = (this as? WheelSettings.InMotionV2)?.autoHeadlight ?: false
val WheelSettings.lightEffect: Boolean get() = (this as? WheelSettings.InMotionV2)?.lightEffect ?: false
val WheelSettings.twoBatteryMode: Boolean get() = (this as? WheelSettings.InMotionV2)?.twoBatteryMode ?: false
val WheelSettings.lowBatterySafeMode: Boolean get() = (this as? WheelSettings.InMotionV2)?.lowBatterySafeMode ?: false
val WheelSettings.spinKill: Boolean get() = (this as? WheelSettings.InMotionV2)?.spinKill ?: false
val WheelSettings.cruise: Boolean get() = (this as? WheelSettings.InMotionV2)?.cruise ?: false
val WheelSettings.loadDetect: Boolean get() = (this as? WheelSettings.InMotionV2)?.loadDetect ?: false
val WheelSettings.highSpeedMode: Boolean get() = (this as? WheelSettings.Veteran)?.highSpeedMode ?: false
val WheelSettings.lowVoltageMode: Boolean get() = (this as? WheelSettings.Veteran)?.lowVoltageMode ?: false
