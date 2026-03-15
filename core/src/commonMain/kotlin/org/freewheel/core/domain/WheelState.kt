package org.freewheel.core.domain

import org.freewheel.core.utils.ByteUtils

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
    /** BMS-reported state of charge 0-100. Populated by: KS. -1 = unknown. */
    val bmsSoc: Int = -1,

    // Distance tracking — populated by all decoders
    val totalDistance: Long = 0,
    /** Total energy consumption in Wh. Populated by: KS. */
    val totalEnergyWh: Long = 0,
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
    /** Hardware fault bitfield. Populated by: KS. See KingsongDecoder.HwFault. */
    val hwFaults: Int = 0,

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

    // Begode extended settings — populated by: GW only
    val weakMagnetism: Int = -1,          // 0-6 (-1=unknown)
    val extendedRollAngle: Int = -1,      // 0-9 (-1=unknown)
    val powerAlarm: Int = -1,             // 50-90% (-1=unknown)
    val plateProtection: Boolean = false,

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

    // InMotion V2 extended settings — populated by: IM2 only
    val bermAngleMode: Boolean = false,
    val bermAngle: Int = -1,            // degrees (-1=unknown)
    val turningSensitivity: Int = -1,   // 0-100 (-1=unknown)
    val onePedalMode: Boolean = false,
    val speedingBrakingMode: Boolean = false,
    val speedingBrakingAngle: Int = -1, // degrees (-1=unknown)
    val soundWave: Boolean = false,
    val soundWaveSensitivity: Int = -1, // 0-100 (-1=unknown)
    val safeSpeedLimit: Boolean = false,
    val backwardOverspeedAlert: Boolean = false,
    val tailLightMode: Int = -1,        // enum: 0=off, 1=highlight, 2=hazard (-1=unknown)
    val turnSignalMode: Int = -1,       // enum: 0=off, 1=always_on, 2=common, 3=strobe, 4=sync (-1=unknown)
    val logoLightBrightness: Int = -1,  // 0-100 (-1=unknown)
    val autoHeadlight: Boolean = false,
    val lightEffect: Boolean = false,
    val lightEffectMode: Int = -1,      // enum (-1=unknown)
    val twoBatteryMode: Boolean = false,
    val lowBatterySafeMode: Boolean = false,
    val spinKill: Boolean = false,
    val cruise: Boolean = false,
    val loadDetect: Boolean = false,
    val standbyTime: Int = -1,          // minutes (-1=unknown)
    val chargeLimit: Int = -1,          // percentage (-1=unknown)

    // Veteran/Leaperkim extended settings — populated by: VT only
    val highSpeedMode: Boolean = false,
    val lowVoltageMode: Boolean = false,
    val keyTone: Int = -1,           // 0-100% (-1=unknown)
    val lockState: Int = -1,         // lock bitfield (-1=unknown)
    val alertSpeed: Int = 0,         // km/h (alert/warning speed threshold)
    val autoOffTime: Int = 0,        // seconds (auto power-off timer)
    val screenBacklight: Int = -1,   // 0-100% (-1=unknown)
    val stopSpeed: Int = -1,         // km/h, encoded +10 (-1=unknown)
    val pwmLimit: Int = -1,          // 30-100% (-1=unknown)
    val voltageCorrection: Int = -1, // -15 to +15 (×0.1%), -1=unknown
    val maxChargeVoltage: Int = -1,  // 0-120 (×0.1V + base), -1=unknown
    val brakePressureAlarm: Int = -1, // 80-125% brake overpressure alarm (-1=unknown)
    val lateralCutoffAngle: Int = -1, // degrees, encoded +35 (-1=unknown)
    val dynamicAssist: Int = -1,      // 0-100% acceleration/deceleration assist (-1=unknown)
    val accelerationLimit: Int = -1,  // 0-100% acceleration reduction (-1=unknown)
    val chargeVoltageBase: Int = 145, // base voltage for charge limit calculation (read-only)
    val wheelDisplayUnit: Int = -1,   // 0=km, 1=miles (-1=unknown)
    /** Battery temperature alert bitmask. Populated by: VT. 111=normal, 100/101/110=high-temp zone, 0=not reported. */
    val batteryTempMode: Int = 0,

    // Error tracking
    val error: String = "",
    /** Wheel error/fault code. Populated by: KS. 0 = no fault. */
    val faultCode: Int = 0,
    /** Alert string. Populated by: GW, KS, IM1, IM2. */
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

    // ==================== Sub-state Views ====================
    // These methods extract focused sub-states for granular StateFlow emission.
    // WheelConnectionManager emits these selectively — only when the relevant
    // sub-state changes — so UI only recomposes for fields it observes.

    /** Extract telemetry fields (high-frequency, changes every BLE notification). */
    fun toTelemetryState() = TelemetryState(
        speed = speed, voltage = voltage, current = current, phaseCurrent = phaseCurrent,
        power = power, temperature = temperature, temperature2 = temperature2,
        batteryLevel = batteryLevel, bmsSoc = bmsSoc,
        totalDistance = totalDistance, totalEnergyWh = totalEnergyWh, wheelDistance = wheelDistance,
        output = output, calculatedPwm = calculatedPwm, angle = angle, roll = roll,
        torque = torque, motorPower = motorPower, cpuTemp = cpuTemp, imuTemp = imuTemp,
        cpuLoad = cpuLoad, hwFaults = hwFaults, speedLimit = speedLimit, currentLimit = currentLimit,
        fanStatus = fanStatus, chargingStatus = chargingStatus, wheelAlarm = wheelAlarm,
        faultCode = faultCode, alert = alert, timestamp = timestamp
    )

    /** Extract settings fields (rare updates, only when wheel reports new settings). */
    fun toSettingsState() = WheelSettingsState(
        inMiles = inMiles, pedalsMode = pedalsMode, speedAlarms = speedAlarms,
        rollAngle = rollAngle, tiltBackSpeed = tiltBackSpeed, lightMode = lightMode,
        ledMode = ledMode, cutoutAngle = cutoutAngle, beeperVolume = beeperVolume,
        weakMagnetism = weakMagnetism, extendedRollAngle = extendedRollAngle,
        powerAlarm = powerAlarm, plateProtection = plateProtection,
        maxSpeed = maxSpeed, pedalTilt = pedalTilt, pedalSensitivity = pedalSensitivity,
        rideMode = rideMode, fancierMode = fancierMode, speakerVolume = speakerVolume,
        mute = mute, handleButton = handleButton, drl = drl,
        lightBrightness = lightBrightness, transportMode = transportMode,
        goHomeMode = goHomeMode, fanQuiet = fanQuiet,
        bermAngleMode = bermAngleMode, bermAngle = bermAngle,
        turningSensitivity = turningSensitivity, onePedalMode = onePedalMode,
        speedingBrakingMode = speedingBrakingMode, speedingBrakingAngle = speedingBrakingAngle,
        soundWave = soundWave, soundWaveSensitivity = soundWaveSensitivity,
        safeSpeedLimit = safeSpeedLimit, backwardOverspeedAlert = backwardOverspeedAlert,
        tailLightMode = tailLightMode, turnSignalMode = turnSignalMode,
        logoLightBrightness = logoLightBrightness, autoHeadlight = autoHeadlight,
        lightEffect = lightEffect, lightEffectMode = lightEffectMode,
        twoBatteryMode = twoBatteryMode, lowBatterySafeMode = lowBatterySafeMode,
        spinKill = spinKill, cruise = cruise, loadDetect = loadDetect,
        standbyTime = standbyTime, chargeLimit = chargeLimit,
        highSpeedMode = highSpeedMode, lowVoltageMode = lowVoltageMode,
        keyTone = keyTone, lockState = lockState,
        alertSpeed = alertSpeed, autoOffTime = autoOffTime,
        screenBacklight = screenBacklight, stopSpeed = stopSpeed,
        pwmLimit = pwmLimit, voltageCorrection = voltageCorrection,
        maxChargeVoltage = maxChargeVoltage, brakePressureAlarm = brakePressureAlarm,
        lateralCutoffAngle = lateralCutoffAngle,
        dynamicAssist = dynamicAssist, accelerationLimit = accelerationLimit,
        chargeVoltageBase = chargeVoltageBase, wheelDisplayUnit = wheelDisplayUnit
    )

    /** Extract identity fields (set once per connection). */
    fun toIdentity() = WheelIdentity(
        wheelType = wheelType, name = name, model = model, modeStr = modeStr,
        version = version, serialNumber = serialNumber, btName = btName
    )

    /** Extract BMS fields (periodic updates). */
    fun toBmsState() = BmsState(bms1 = bms1, bms2 = bms2)

    companion object {
        /** Use [ByteUtils.KM_TO_MILES_MULTIPLIER] directly. Kept for backward compatibility. */
        const val KM_TO_MILES = ByteUtils.KM_TO_MILES_MULTIPLIER

        /** Creates a default empty WheelState. Useful from Swift/ObjC where default-parameter constructors aren't available. */
        fun empty(): WheelState = WheelState()
    }
}
