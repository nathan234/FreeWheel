package org.freewheel.core.telemetry

import org.freewheel.core.domain.TelemetryState

/**
 * A single telemetry snapshot captured at a point in time.
 * Shared across Android and iOS via KMP.
 */
data class TelemetrySample(
    val timestampMs: Long,
    val speedKmh: Double,
    val voltageV: Double,
    val currentA: Double,
    val powerW: Double,
    val temperatureC: Double,
    val batteryPercent: Double,
    val pwmPercent: Double,
    val gpsSpeedKmh: Double = 0.0
) {
    companion object {
        fun fromTelemetry(
            telemetry: TelemetryState,
            timestampMs: Long,
            gpsSpeedKmh: Double = 0.0
        ): TelemetrySample = TelemetrySample(
            timestampMs = timestampMs,
            speedKmh = telemetry.speedKmh,
            voltageV = telemetry.voltageV,
            currentA = telemetry.currentA,
            powerW = telemetry.powerW,
            temperatureC = telemetry.temperatureC.toDouble(),
            batteryPercent = telemetry.batteryLevelDisplay.toDouble(),
            pwmPercent = telemetry.pwmPercent,
            gpsSpeedKmh = gpsSpeedKmh
        )
    }
}
