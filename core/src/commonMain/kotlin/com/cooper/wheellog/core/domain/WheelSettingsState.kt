package com.cooper.wheellog.core.domain

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
    val fanQuiet: Boolean = false
)
