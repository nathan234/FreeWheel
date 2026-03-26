package org.freewheel.core.domain

/**
 * Type-safe, per-wheel-type settings state.
 *
 * Replaces the flat [WheelSettingsState] with a sealed hierarchy where each
 * variant holds only the settings fields relevant to that wheel type.
 * Extension properties below provide uniform field access for [SettingsCommandId.readInt]
 * and [SettingsCommandId.readBool].
 *
 * Default value of -1 means "not yet read from wheel".
 */
sealed class WheelSettings {
    // Properties promoted to members for Kotlin/Native Swift visibility.
    // Subclasses that have the field override it; others inherit the default.
    open val pedalsMode: Int get() = -1
    open val lightMode: Int get() = -1
    open val ledMode: Int get() = -1
    open val tiltBackSpeed: Int get() = 0
    open val alertSpeed: Int get() = 0
    open val autoOffTime: Int get() = 0
    open val inMiles: Boolean get() = false

    data object None : WheelSettings()

    data class Begode(
        override val pedalsMode: Int = -1,
        val speedAlarms: Int = -1,
        val rollAngle: Int = -1,
        override val tiltBackSpeed: Int = 0,
        override val lightMode: Int = -1,
        override val ledMode: Int = -1,
        val cutoutAngle: Int = -1,
        val beeperVolume: Int = -1,
        override val inMiles: Boolean = false,
        val weakMagnetism: Int = -1,
        val extendedRollAngle: Int = -1,
        val powerAlarm: Int = -1,
        val plateProtection: Boolean = false
    ) : WheelSettings()

    data class Kingsong(
        override val pedalsMode: Int = -1,
        override val lightMode: Int = -1,
        override val ledMode: Int = -1,
        val mute: Boolean = false,
        val handleButton: Boolean = false,
        val ksAlarm1Speed: Int = -1,
        val ksAlarm2Speed: Int = -1,
        val ksAlarm3Speed: Int = -1,
        val ksTiltbackSpeed: Int = -1,
        override val autoOffTime: Int = 0,
        val lockState: Int = -1
    ) : WheelSettings()

    data class Veteran(
        override val pedalsMode: Int = -1,
        override val lightMode: Int = -1,
        override val tiltBackSpeed: Int = 0,
        override val alertSpeed: Int = 0,
        override val autoOffTime: Int = 0,
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
        val batteryTempMode: Int = 0,
        /** Firmware major version (e.g. 3, 4, 43). Used by buildCommand for capability checks. */
        val mVer: Int = 0
    ) : WheelSettings()

    data class LeaperkimCan(
        val pedalTilt: Int = -1,
        override val lightMode: Int = -1,
        val pedalSensitivity: Int = -1,
        val rideMode: Boolean = false,
        val handleButton: Boolean = false,
        override val ledMode: Int = -1,
        val transportMode: Boolean = false
    ) : WheelSettings()

    data class InMotionV2(
        override val pedalsMode: Int = -1,
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
        override val lightMode: Int = -1,
        override val ledMode: Int = -1,
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
        val chargeLimit: Int = -1,
        val speedAlarm: Int = -1,
        val pwmTiltBackLimit: Int = -1,
        val pwmAlarm1: Int = -1,
        val pwmAlarm2: Int = -1,
        val balanceAngle: Int = -1,
        // P6-specific settings
        val autoScreenOff: Boolean = false,
        val autoLock: Boolean = false,
        val ignoreTirePressure: Boolean = false,
        val rideConnectSwitch: Boolean = false,
        val rideConnectLowBattery: Boolean = false,
        val speedTiltbackEnabled: Boolean = false,
        val minTirePressure: Int = -1,
        val chargingCurrentAC110V: Int = -1,
        val chargingCurrentAC220V: Int = -1,
        /** InMotionV2 model ID (e.g. 61=V11, 131=P6). Used by buildCommand for model-dependent routing. */
        val modelId: Int = 0,
        /** Main board firmware version string (e.g. "1.4.123"). Used by buildCommand for version checks. */
        val mainBoardVersion: String = ""
    ) : WheelSettings()

    data class InMotionV1(
        override val lightMode: Int = -1
    ) : WheelSettings()

    data object Ninebot : WheelSettings()

    data class NinebotZ(
        override val lightMode: Int = -1,
        val drl: Boolean = false,
        override val ledMode: Int = -1,
        val handleButton: Boolean = false,
        override val pedalsMode: Int = -1,
        val pedalSensitivity: Int = -1,
        val speakerVolume: Int = -1
    ) : WheelSettings()
}

// ==================== Extension Properties ====================
//
// Provide flat field access across all variants, used by
// SettingsCommandId.readInt/readBool.
// Returns -1 (Int) or false (Boolean) for variants that don't have the field.

// --- Int fields ---

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

val WheelSettings.chargeVoltageBase: Int get() = (this as? WheelSettings.Veteran)?.chargeVoltageBase ?: 145
val WheelSettings.batteryTempMode: Int get() = (this as? WheelSettings.Veteran)?.batteryTempMode ?: 0

// --- Boolean fields ---

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

// P6-specific boolean fields
val WheelSettings.autoScreenOff: Boolean get() = (this as? WheelSettings.InMotionV2)?.autoScreenOff ?: false
val WheelSettings.autoLock: Boolean get() = (this as? WheelSettings.InMotionV2)?.autoLock ?: false
val WheelSettings.ignoreTirePressure: Boolean get() = (this as? WheelSettings.InMotionV2)?.ignoreTirePressure ?: false
val WheelSettings.rideConnectSwitch: Boolean get() = (this as? WheelSettings.InMotionV2)?.rideConnectSwitch ?: false
val WheelSettings.rideConnectLowBattery: Boolean get() = (this as? WheelSettings.InMotionV2)?.rideConnectLowBattery ?: false

// P6-specific int fields
val WheelSettings.minTirePressure: Int get() = (this as? WheelSettings.InMotionV2)?.minTirePressure ?: -1
val WheelSettings.chargingCurrentAC110V: Int get() = (this as? WheelSettings.InMotionV2)?.chargingCurrentAC110V ?: -1
val WheelSettings.chargingCurrentAC220V: Int get() = (this as? WheelSettings.InMotionV2)?.chargingCurrentAC220V ?: -1
val WheelSettings.balanceAngle: Int get() = (this as? WheelSettings.InMotionV2)?.balanceAngle ?: -1
val WheelSettings.speedTiltbackEnabled: Boolean get() = (this as? WheelSettings.InMotionV2)?.speedTiltbackEnabled ?: false
val WheelSettings.speedAlarm: Int get() = (this as? WheelSettings.InMotionV2)?.speedAlarm ?: -1
