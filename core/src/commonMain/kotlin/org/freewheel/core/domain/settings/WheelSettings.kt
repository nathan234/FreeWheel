package org.freewheel.core.domain.settings

/**
 * Type-safe, per-wheel-type settings state.
 *
 * Replaces the flat [WheelSettingsState] with a sealed hierarchy where each
 * variant holds only the settings fields relevant to that wheel type.
 * Extension properties below provide uniform field access for [SettingsCommandId.readInt]
 * and [SettingsCommandId.readBool].
 *
 * "Unknown" (not yet read from wheel) is represented as -1 for ints and null for booleans.
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
        val plateProtection: Boolean? = null
    ) : WheelSettings()

    data class Kingsong(
        override val pedalsMode: Int = -1,
        override val lightMode: Int = -1,
        override val ledMode: Int = -1,
        val mute: Boolean? = null,
        val handleButton: Boolean? = null,
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
        val highSpeedMode: Boolean? = null,
        val lowVoltageMode: Boolean? = null,
        val voltageCorrection: Int = -1,
        val transportMode: Boolean? = null,
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
        val rideMode: Boolean? = null,
        val handleButton: Boolean? = null,
        override val ledMode: Int = -1,
        val transportMode: Boolean? = null
    ) : WheelSettings()

    data class Lorin(
        override val pedalsMode: Int = -1,
        val maxSpeed: Int = -1,
        val pedalTilt: Int = -1,
        val pedalSensitivity: Int = -1,
        val rideMode: Boolean? = null,
        val fancierMode: Boolean? = null,
        val speakerVolume: Int = -1,
        val mute: Boolean? = null,
        val handleButton: Boolean? = null,
        val drl: Boolean? = null,
        val lightBrightness: Int = -1,
        val transportMode: Boolean? = null,
        val goHomeMode: Boolean? = null,
        val fanQuiet: Boolean? = null,
        override val lightMode: Int = -1,
        override val ledMode: Int = -1,
        val cutoutAngle: Int = -1,
        val bermAngleMode: Boolean? = null,
        val bermAngle: Int = -1,
        val turningSensitivity: Int = -1,
        val onePedalMode: Boolean? = null,
        val speedingBrakingMode: Boolean? = null,
        val speedingBrakingAngle: Int = -1,
        val soundWave: Boolean? = null,
        val soundWaveSensitivity: Int = -1,
        val safeSpeedLimit: Boolean? = null,
        val backwardOverspeedAlert: Boolean? = null,
        val tailLightMode: Int = -1,
        val turnSignalMode: Int = -1,
        val logoLightBrightness: Int = -1,
        val autoHeadlight: Boolean? = null,
        val lightEffect: Boolean? = null,
        val lightEffectMode: Int = -1,
        val twoBatteryMode: Boolean? = null,
        val lowBatterySafeMode: Boolean? = null,
        val spinKill: Boolean? = null,
        val cruise: Boolean? = null,
        val loadDetect: Boolean? = null,
        val standbyTime: Int = -1,
        val chargeLimit: Int = -1,
        val speedAlarm: Int = -1,
        val pwmTiltBackLimit: Int = -1,
        val pwmAlarm1: Int = -1,
        val pwmAlarm2: Int = -1,
        val balanceAngle: Int = -1,
        // P6-specific settings
        val autoScreenOff: Boolean? = null,
        val autoLock: Boolean? = null,
        val ignoreTirePressure: Boolean? = null,
        val rideConnectSwitch: Boolean? = null,
        val rideConnectLowBattery: Boolean? = null,
        val speedTiltbackEnabled: Boolean? = null,
        val minTirePressure: Int = -1,
        val chargingCurrentAC110V: Int = -1,
        val chargingCurrentAC220V: Int = -1,
        /** Lorin model ID (e.g. 61=V11, 131=P6). Used by buildCommand for model-dependent routing. */
        val modelId: Int = 0,
        /** Main board firmware version string (e.g. "1.4.123"). Used by buildCommand for version checks. */
        val mainBoardVersion: String = ""
    ) : WheelSettings()

    data class InMotionV1(
        override val lightMode: Int = -1,
        override val ledMode: Int = -1,
        val handleButton: Boolean? = null,
        val rideMode: Boolean? = null,
        val maxSpeed: Int = -1,
        /** Pedal tilt in tenths of a degree, matching the shared settings convention. */
        val pedalTilt: Int = -1,
        val pedalSensitivity: Int = -1,
        val speakerVolume: Int = -1
    ) : WheelSettings()

    data object Ninebot : WheelSettings()

    data class NinebotZ(
        override val lightMode: Int = -1,
        val drl: Boolean? = null,
        override val ledMode: Int = -1,
        val handleButton: Boolean? = null,
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
    is WheelSettings.Lorin -> cutoutAngle
    is WheelSettings.Kingsong, is WheelSettings.Veteran, is WheelSettings.LeaperkimCan,
    is WheelSettings.InMotionV1, is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> -1
}

val WheelSettings.maxSpeed: Int get() = when (this) {
    is WheelSettings.Begode -> tiltBackSpeed
    is WheelSettings.InMotionV1 -> maxSpeed
    is WheelSettings.Lorin -> maxSpeed
    else -> -1
}

val WheelSettings.pedalTilt: Int get() = when (this) {
    is WheelSettings.LeaperkimCan -> pedalTilt
    is WheelSettings.InMotionV1 -> pedalTilt
    is WheelSettings.Lorin -> pedalTilt
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.Veteran,
    is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> -1
}

val WheelSettings.pedalSensitivity: Int get() = when (this) {
    is WheelSettings.Veteran -> pedalSensitivity
    is WheelSettings.LeaperkimCan -> pedalSensitivity
    is WheelSettings.InMotionV1 -> pedalSensitivity
    is WheelSettings.Lorin -> pedalSensitivity
    is WheelSettings.NinebotZ -> pedalSensitivity
    is WheelSettings.Begode, is WheelSettings.Kingsong,
    is WheelSettings.Ninebot, is WheelSettings.None -> -1
}

val WheelSettings.speakerVolume: Int get() = when (this) {
    is WheelSettings.InMotionV1 -> speakerVolume
    is WheelSettings.Lorin -> speakerVolume
    is WheelSettings.NinebotZ -> speakerVolume
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.Veteran,
    is WheelSettings.LeaperkimCan,
    is WheelSettings.Ninebot, is WheelSettings.None -> -1
}

val WheelSettings.beeperVolume: Int get() = (this as? WheelSettings.Begode)?.beeperVolume ?: -1
val WheelSettings.lightBrightness: Int get() = (this as? WheelSettings.Lorin)?.lightBrightness ?: -1
val WheelSettings.bermAngle: Int get() = (this as? WheelSettings.Lorin)?.bermAngle ?: -1
val WheelSettings.turningSensitivity: Int get() = (this as? WheelSettings.Lorin)?.turningSensitivity ?: -1
val WheelSettings.speedingBrakingAngle: Int get() = (this as? WheelSettings.Lorin)?.speedingBrakingAngle ?: -1
val WheelSettings.soundWaveSensitivity: Int get() = (this as? WheelSettings.Lorin)?.soundWaveSensitivity ?: -1
val WheelSettings.tailLightMode: Int get() = (this as? WheelSettings.Lorin)?.tailLightMode ?: -1
val WheelSettings.turnSignalMode: Int get() = (this as? WheelSettings.Lorin)?.turnSignalMode ?: -1
val WheelSettings.logoLightBrightness: Int get() = (this as? WheelSettings.Lorin)?.logoLightBrightness ?: -1
val WheelSettings.lightEffectMode: Int get() = (this as? WheelSettings.Lorin)?.lightEffectMode ?: -1
val WheelSettings.standbyTime: Int get() = (this as? WheelSettings.Lorin)?.standbyTime ?: -1
val WheelSettings.chargeLimit: Int get() = (this as? WheelSettings.Lorin)?.chargeLimit ?: -1
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
    is WheelSettings.Begode, is WheelSettings.LeaperkimCan, is WheelSettings.Lorin,
    is WheelSettings.InMotionV1, is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> -1
}

// --- Veteran lockState bit accessors (subtype-5 byte 51) ---
//
// Bit semantics per Leaperkim app v1.4.8 LockSettingActivity:
//   bit 0 — set when the wheel's last password command (action 11) succeeded
//   bit 5 — auto-lock is currently enabled
//   bit 6 — a password is currently set on the wheel
//
// All three return null when lockState is -1 (not yet read from a subtype-5 frame),
// so callers can distinguish "unknown" from a confirmed false. They live on the
// Veteran subtype because only Veteran reports these bits — promoting them to
// dedicated WheelSettings.Veteran fields would duplicate the same byte.

/** Bit 0: wheel acknowledged the last password command. Null until lockState is read. */
val WheelSettings.Veteran.lastPasswordCommandSucceeded: Boolean?
    get() = if (lockState < 0) null else (lockState and 0x01) != 0

/** Bit 5: auto-lock is enabled on the wheel. Null until lockState is read. */
val WheelSettings.Veteran.autoLockEnabled: Boolean?
    get() = if (lockState < 0) null else (lockState and 0x20) != 0

/** Bit 6: the wheel currently has a password set. Null until lockState is read. */
val WheelSettings.Veteran.hasPassword: Boolean?
    get() = if (lockState < 0) null else (lockState and 0x40) != 0

val WheelSettings.ksAlarm1Speed: Int get() = (this as? WheelSettings.Kingsong)?.ksAlarm1Speed ?: -1
val WheelSettings.ksAlarm2Speed: Int get() = (this as? WheelSettings.Kingsong)?.ksAlarm2Speed ?: -1
val WheelSettings.ksAlarm3Speed: Int get() = (this as? WheelSettings.Kingsong)?.ksAlarm3Speed ?: -1
val WheelSettings.ksTiltbackSpeed: Int get() = (this as? WheelSettings.Kingsong)?.ksTiltbackSpeed ?: -1

val WheelSettings.chargeVoltageBase: Int get() = (this as? WheelSettings.Veteran)?.chargeVoltageBase ?: 145
val WheelSettings.batteryTempMode: Int get() = (this as? WheelSettings.Veteran)?.batteryTempMode ?: 0

// --- Boolean fields ---
//
// All readback boolean fields return Boolean?, with null = "not yet read from wheel".
// Wheel types that don't have the field also return null. Callers must distinguish
// null (unknown) from false (explicitly off) — collapsing to false hides startup state
// and breaks visibleWhen gating.

val WheelSettings.plateProtection: Boolean? get() = (this as? WheelSettings.Begode)?.plateProtection

val WheelSettings.rideMode: Boolean? get() = when (this) {
    is WheelSettings.LeaperkimCan -> rideMode
    is WheelSettings.InMotionV1 -> rideMode
    is WheelSettings.Lorin -> rideMode
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.Veteran,
    is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> null
}

val WheelSettings.fancierMode: Boolean? get() = (this as? WheelSettings.Lorin)?.fancierMode

val WheelSettings.mute: Boolean? get() = when (this) {
    is WheelSettings.Kingsong -> mute
    is WheelSettings.Lorin -> mute
    is WheelSettings.Begode, is WheelSettings.Veteran, is WheelSettings.LeaperkimCan,
    is WheelSettings.InMotionV1, is WheelSettings.Ninebot, is WheelSettings.NinebotZ,
    is WheelSettings.None -> null
}

val WheelSettings.handleButton: Boolean? get() = when (this) {
    is WheelSettings.Kingsong -> handleButton
    is WheelSettings.LeaperkimCan -> handleButton
    is WheelSettings.InMotionV1 -> handleButton
    is WheelSettings.Lorin -> handleButton
    is WheelSettings.NinebotZ -> handleButton
    is WheelSettings.Begode, is WheelSettings.Veteran,
    is WheelSettings.Ninebot, is WheelSettings.None -> null
}

val WheelSettings.drl: Boolean? get() = when (this) {
    is WheelSettings.Lorin -> drl
    is WheelSettings.NinebotZ -> drl
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.Veteran,
    is WheelSettings.LeaperkimCan, is WheelSettings.InMotionV1,
    is WheelSettings.Ninebot, is WheelSettings.None -> null
}

val WheelSettings.transportMode: Boolean? get() = when (this) {
    is WheelSettings.Veteran -> transportMode
    is WheelSettings.LeaperkimCan -> transportMode
    is WheelSettings.Lorin -> transportMode
    is WheelSettings.Begode, is WheelSettings.Kingsong, is WheelSettings.InMotionV1,
    is WheelSettings.Ninebot, is WheelSettings.NinebotZ, is WheelSettings.None -> null
}

val WheelSettings.goHomeMode: Boolean? get() = (this as? WheelSettings.Lorin)?.goHomeMode
val WheelSettings.fanQuiet: Boolean? get() = (this as? WheelSettings.Lorin)?.fanQuiet
val WheelSettings.bermAngleMode: Boolean? get() = (this as? WheelSettings.Lorin)?.bermAngleMode
val WheelSettings.onePedalMode: Boolean? get() = (this as? WheelSettings.Lorin)?.onePedalMode
val WheelSettings.speedingBrakingMode: Boolean? get() = (this as? WheelSettings.Lorin)?.speedingBrakingMode
val WheelSettings.soundWave: Boolean? get() = (this as? WheelSettings.Lorin)?.soundWave
val WheelSettings.safeSpeedLimit: Boolean? get() = (this as? WheelSettings.Lorin)?.safeSpeedLimit
val WheelSettings.backwardOverspeedAlert: Boolean? get() = (this as? WheelSettings.Lorin)?.backwardOverspeedAlert
val WheelSettings.autoHeadlight: Boolean? get() = (this as? WheelSettings.Lorin)?.autoHeadlight
val WheelSettings.lightEffect: Boolean? get() = (this as? WheelSettings.Lorin)?.lightEffect
val WheelSettings.twoBatteryMode: Boolean? get() = (this as? WheelSettings.Lorin)?.twoBatteryMode
val WheelSettings.lowBatterySafeMode: Boolean? get() = (this as? WheelSettings.Lorin)?.lowBatterySafeMode
val WheelSettings.spinKill: Boolean? get() = (this as? WheelSettings.Lorin)?.spinKill
val WheelSettings.cruise: Boolean? get() = (this as? WheelSettings.Lorin)?.cruise
val WheelSettings.loadDetect: Boolean? get() = (this as? WheelSettings.Lorin)?.loadDetect
val WheelSettings.highSpeedMode: Boolean? get() = (this as? WheelSettings.Veteran)?.highSpeedMode
val WheelSettings.lowVoltageMode: Boolean? get() = (this as? WheelSettings.Veteran)?.lowVoltageMode

// P6-specific boolean fields
val WheelSettings.autoScreenOff: Boolean? get() = (this as? WheelSettings.Lorin)?.autoScreenOff
val WheelSettings.autoLock: Boolean? get() = (this as? WheelSettings.Lorin)?.autoLock
val WheelSettings.ignoreTirePressure: Boolean? get() = (this as? WheelSettings.Lorin)?.ignoreTirePressure
val WheelSettings.rideConnectSwitch: Boolean? get() = (this as? WheelSettings.Lorin)?.rideConnectSwitch
val WheelSettings.rideConnectLowBattery: Boolean? get() = (this as? WheelSettings.Lorin)?.rideConnectLowBattery

// P6-specific int fields
val WheelSettings.minTirePressure: Int get() = (this as? WheelSettings.Lorin)?.minTirePressure ?: -1
val WheelSettings.chargingCurrentAC110V: Int get() = (this as? WheelSettings.Lorin)?.chargingCurrentAC110V ?: -1
val WheelSettings.chargingCurrentAC220V: Int get() = (this as? WheelSettings.Lorin)?.chargingCurrentAC220V ?: -1
val WheelSettings.balanceAngle: Int get() = (this as? WheelSettings.Lorin)?.balanceAngle ?: -1
val WheelSettings.speedTiltbackEnabled: Boolean? get() = (this as? WheelSettings.Lorin)?.speedTiltbackEnabled
val WheelSettings.speedAlarm: Int get() = (this as? WheelSettings.Lorin)?.speedAlarm ?: -1
