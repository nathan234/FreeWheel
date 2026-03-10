package org.freewheel.core.domain

/**
 * Wheel settings state that changes rarely (only when the wheel reports new settings).
 * Separated from [WheelState] to avoid triggering UI updates on every telemetry frame.
 *
 * Default value of -1 means "not yet read from wheel".
 */
data class WheelSettingsState(
    val inMiles: Boolean = false,
    val pedalsMode: Int = -1,
    val speedAlarms: Int = -1,
    val rollAngle: Int = -1,
    val tiltBackSpeed: Int = 0,
    val lightMode: Int = -1,
    val ledMode: Int = -1,
    val cutoutAngle: Int = -1,
    val beeperVolume: Int = -1,
    // Begode extended settings
    val weakMagnetism: Int = -1,
    val extendedRollAngle: Int = -1,
    val powerAlarm: Int = -1,
    val plateProtection: Boolean = false,
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
    // InMotion V2 extended settings
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
    // Veteran/Leaperkim extended settings
    val highSpeedMode: Boolean = false,
    val lowVoltageMode: Boolean = false,
    val keyTone: Int = -1,
    val lockState: Int = -1,
    val alertSpeed: Int = 0,
    val autoOffTime: Int = 0
)
