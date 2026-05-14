package org.freewheel.core.domain.telemetry

import org.freewheel.core.utils.ByteUtils

/**
 * High-frequency telemetry data that changes on every BLE notification.
 * Only components observing telemetry will recompose when these fields change.
 *
 * All values use internal units (typically 1/100 for precision).
 */
data class TelemetryState(
    val speed: Int = 0,
    val voltage: Int = 0,
    val current: Int = 0,
    val phaseCurrent: Int = 0,
    val power: Int = 0,
    val temperature: Int = 0,
    val temperature2: Int = 0,
    val batteryLevel: Int = 0,
    val bmsSoc: Int = -1,
    val totalDistance: Long = 0,
    val totalEnergyWh: Long = 0,
    val wheelDistance: Long = 0,
    val topSpeed: Int = 0,
    val rideTime: Int = 0,
    val totalOnTime: Int = 0,
    val output: Int = 0,
    val calculatedPwm: Double = 0.0,
    val angle: Double = 0.0,
    val roll: Double = 0.0,
    val torque: Double = 0.0,
    val motorPower: Double = 0.0,
    val cpuTemp: Int = 0,
    val imuTemp: Int = 0,
    val cpuLoad: Int = 0,
    val hwFaults: Int = 0,
    val speedLimit: Double = 0.0,
    val currentLimit: Double = 0.0,
    val fanStatus: Int = 0,
    val chargingStatus: Int = 0,
    val wheelAlarm: Boolean = false,
    val error: String = "",
    val faultCode: Int = 0,
    val alert: String = "",
    val timestamp: Long = 0,

    // Settings-derived values exposed here for dashboard metric extraction.
    // Settings-derived values exposed here for dashboard metric extraction.
    val alertSpeed: Int = 0,
    val autoOffTime: Int = 0,
    val maxSpeed: Int = -1
) {
    val speedKmh: Double get() = speed / 100.0
    val speedMph: Double get() = speedKmh * ByteUtils.KM_TO_MILES_MULTIPLIER
    val voltageV: Double get() = voltage / 100.0
    val currentA: Double get() = current / 100.0
    val phaseCurrentA: Double get() = phaseCurrent / 100.0
    val powerW: Double get() = power / 100.0
    val temperatureC: Int get() = temperature / 100
    val temperatureF: Double get() = temperatureC * 9.0 / 5.0 + 32
    val temperature2C: Int get() = temperature2 / 100
    val totalDistanceKm: Double get() = totalDistance / 1000.0
    val wheelDistanceKm: Double get() = wheelDistance / 1000.0

    /**
     * PWM as a 0-100% percentage, **clamped for safe user-facing display**.
     *
     * A duty cycle outside 0..100 is physically impossible, so presenting a raw
     * out-of-bounds value would mislead users. The unclamped diagnostic signal
     * lives in [org.freewheel.core.validation.TelemetryValidator], which reads
     * [calculatedPwm] directly — do not use this property for bug detection.
     */
    val pwmPercent: Double get() = (calculatedPwm * 100.0).coerceIn(0.0, 100.0)

    /**
     * Battery level clamped to 0..100 for safe user-facing display.
     *
     * The validator reads the raw [batteryLevel] field directly so an
     * out-of-bounds decoder reading is still reported to the error log.
     */
    val batteryLevelDisplay: Int get() = batteryLevel.coerceIn(0, 100)

    val outputPercent: Int get() = output / 100
    companion object {
        /** Swift-callable factory — Kotlin default-parameter constructors aren't visible from ObjC/Swift. */
        fun empty(): TelemetryState = TelemetryState()
    }
}
