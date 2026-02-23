package com.cooper.wheellog.core.domain

import com.cooper.wheellog.core.utils.ByteUtils

/**
 * High-frequency telemetry data that changes on every BLE notification.
 * Separated from [WheelState] to enable granular UI updates —
 * only components observing telemetry will recompose when these fields change.
 *
 * All values use the same internal units as [WheelState] (typically 1/100 for precision).
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
    val totalDistance: Long = 0,
    val wheelDistance: Long = 0,
    val output: Int = 0,
    val calculatedPwm: Double = 0.0,
    val angle: Double = 0.0,
    val roll: Double = 0.0,
    val torque: Double = 0.0,
    val motorPower: Double = 0.0,
    val cpuTemp: Int = 0,
    val imuTemp: Int = 0,
    val cpuLoad: Int = 0,
    val speedLimit: Double = 0.0,
    val currentLimit: Double = 0.0,
    val fanStatus: Int = 0,
    val chargingStatus: Int = 0,
    val wheelAlarm: Boolean = false,
    val alert: String = "",
    val timestamp: Long = 0
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
    val pwmPercent: Double get() = calculatedPwm * 100.0
    val outputPercent: Int get() = output / 100
}
