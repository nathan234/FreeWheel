package com.cooper.wheellog.core.domain

import com.cooper.wheellog.core.utils.ByteUtils

/**
 * Immutable data class representing the current state of an electric unicycle.
 * All values use internal units (typically 1/100 for precision).
 *
 * Unit conventions:
 * - Speed: 1/100 km/h (e.g., 2500 = 25.00 km/h)
 * - Voltage: 1/100 V (e.g., 8400 = 84.00 V)
 * - Current: 1/100 A (e.g., 1500 = 15.00 A)
 * - Power: 1/100 W (e.g., 150000 = 1500.00 W)
 * - Temperature: 1/100 °C (e.g., 3500 = 35.00 °C)
 * - Distance: meters
 */
data class WheelState(
    // Core telemetry — populated by all decoders (KS, GW, VT, NB, NZ, IM1, IM2)
    val speed: Int = 0,
    val voltage: Int = 0,
    val current: Int = 0,
    /** Phase current in 1/100 A. Populated by: GW, VT. */
    val phaseCurrent: Int = 0,
    val power: Int = 0,
    val temperature: Int = 0,
    /** Secondary temperature (motor/board). Populated by: KS, GW, IM1, IM2. */
    val temperature2: Int = 0,
    val batteryLevel: Int = 0,

    // Distance tracking — populated by all decoders
    val totalDistance: Long = 0,
    val wheelDistance: Long = 0,

    // PWM and output — populated by: KS, GW, VT, IM2
    val output: Int = 0,
    val calculatedPwm: Double = 0.0,

    // Orientation
    /** Pitch angle in degrees. Populated by: VT, IM1, IM2. */
    val angle: Double = 0.0,
    /** Roll angle in degrees. Populated by: IM1, IM2. */
    val roll: Double = 0.0,

    // Motor and performance — IM2 only unless noted
    /** Torque in Nm. Populated by: IM2. */
    val torque: Double = 0.0,
    /** Motor power in W. Populated by: IM2. */
    val motorPower: Double = 0.0,
    /** CPU temperature in °C. Populated by: IM2. */
    val cpuTemp: Int = 0,
    /** IMU temperature in °C. Populated by: IM2. */
    val imuTemp: Int = 0,
    /** CPU load percentage. Populated by: KS. */
    val cpuLoad: Int = 0,

    // Limits — populated by: IM2
    val speedLimit: Double = 0.0,
    val currentLimit: Double = 0.0,

    // Status flags
    /** Fan status. Populated by: KS. */
    val fanStatus: Int = 0,
    /** Charging status. Populated by: KS. */
    val chargingStatus: Int = 0,
    /** Wheel alarm active. Populated by: GW. */
    val wheelAlarm: Boolean = false,

    // Wheel identification — populated by all decoders (sources differ per decoder)
    val wheelType: WheelType = WheelType.Unknown,
    /** Wheel BLE name. Populated by: KS. */
    val name: String = "",
    val model: String = "",
    /** Ride mode string. Populated by: KS, IM1, IM2. */
    val modeStr: String = "",
    val version: String = "",
    /** Serial number. Populated by: KS, NB, NZ, IM1, IM2. */
    val serialNumber: String = "",
    val btName: String = "",

    // BMS data — populated by: KS, GW, VT, NZ
    val bms1: BmsSnapshot? = null,
    val bms2: BmsSnapshot? = null,

    // Wheel settings reported via BLE — populated by: GW, KS, NZ
    val inMiles: Boolean = false,
    val pedalsMode: Int = -1,       // 0=Hard, 1=Medium, 2=Soft (-1=unknown)
    val speedAlarms: Int = -1,      // 0=alarms on, 1=off level 1, 2=off level 2
    val rollAngle: Int = -1,        // 0=Low, 1=Medium, 2=High
    val tiltBackSpeed: Int = 0,     // km/h (0-99)
    val lightMode: Int = -1,        // 0=Off, 1=On, 2=Strobe
    val ledMode: Int = -1,          // 0-9 LED pattern
    val cutoutAngle: Int = -1,      // degrees (45-90, -1=unknown; readback from FRAME_07 bytes 4-5)
    val beeperVolume: Int = -1,     // 0-9 (-1=unknown; readback from FRAME_00 byte 17)

    // InMotionV2 settings reported via BLE — populated by: IM2 only
    val maxSpeed: Int = -1,         // km/h (-1=unknown)
    val pedalTilt: Int = -1,        // 1/10 degree (-1=unknown)
    val pedalSensitivity: Int = -1, // raw 0-100 (-1=unknown)
    val rideMode: Boolean = false,  // false=classic, true=offroad
    val fancierMode: Boolean = false,
    val speakerVolume: Int = -1,    // 0-100 (-1=unknown)
    val mute: Boolean = false,
    val handleButton: Boolean = false,
    val drl: Boolean = false,
    val lightBrightness: Int = -1,  // 0-100 (-1=unknown)
    val transportMode: Boolean = false,
    val goHomeMode: Boolean = false,
    val fanQuiet: Boolean = false,

    // Error tracking
    val error: String = "",
    /** Alert string. Populated by: GW, IM1, IM2. */
    val alert: String = "",

    // Timestamp of last update
    val timestamp: Long = 0
) {
    // Computed properties for display

    /** Speed in km/h */
    val speedKmh: Double get() = speed / 100.0

    /** Speed in mph */
    val speedMph: Double get() = speedKmh * ByteUtils.KM_TO_MILES_MULTIPLIER

    /** Voltage in V */
    val voltageV: Double get() = voltage / 100.0

    /** Current in A */
    val currentA: Double get() = current / 100.0

    /** Phase current in A */
    val phaseCurrentA: Double get() = phaseCurrent / 100.0

    /** Power in W */
    val powerW: Double get() = power / 100.0

    /** Temperature in °C */
    val temperatureC: Int get() = temperature / 100

    /** Temperature in °F */
    val temperatureF: Double get() = temperatureC * 9.0 / 5.0 + 32

    /** Secondary temperature in °C */
    val temperature2C: Int get() = temperature2 / 100

    /** Total distance in km */
    val totalDistanceKm: Double get() = totalDistance / 1000.0

    /** Wheel distance in km */
    val wheelDistanceKm: Double get() = wheelDistance / 1000.0

    /** PWM as percentage (0-100) */
    val pwmPercent: Double get() = calculatedPwm * 100.0

    /** Output as percentage */
    val outputPercent: Int get() = output / 100

    /** Human-readable display name combining brand, model, and name */
    val displayName: String get() {
        val brand = wheelType.displayName
        val label = model.ifEmpty { name }.ifEmpty { btName }
        if (label.isEmpty()) return brand.ifEmpty { "Dashboard" }
        if (brand.isEmpty() || label.startsWith(brand, ignoreCase = true)) return label
        return "$brand $label"
    }

    companion object {
        /** Use [ByteUtils.KM_TO_MILES_MULTIPLIER] directly. Kept for backward compatibility. */
        const val KM_TO_MILES = ByteUtils.KM_TO_MILES_MULTIPLIER

        /** Creates a default empty WheelState. Useful from Swift/ObjC where default-parameter constructors aren't available. */
        fun empty(): WheelState = WheelState()
    }
}
