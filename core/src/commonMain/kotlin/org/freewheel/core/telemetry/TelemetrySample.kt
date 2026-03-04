package org.freewheel.core.telemetry

import org.freewheel.core.domain.WheelState

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
        fun fromWheelState(
            state: WheelState,
            timestampMs: Long,
            gpsSpeedKmh: Double = 0.0
        ): TelemetrySample = TelemetrySample(
            timestampMs = timestampMs,
            speedKmh = state.speedKmh,
            voltageV = state.voltageV,
            currentA = state.currentA,
            powerW = state.powerW,
            temperatureC = state.temperatureC.toDouble(),
            batteryPercent = state.batteryLevel.toDouble(),
            pwmPercent = state.pwmPercent,
            gpsSpeedKmh = gpsSpeedKmh
        )

        fun computeTripStats(samples: List<TelemetrySample>): TripStats? {
            if (samples.size < 2) return null
            return TripStats(
                durationMs = samples.last().timestampMs - samples.first().timestampMs,
                maxSpeedKmh = samples.maxOf { it.speedKmh },
                avgSpeedKmh = samples.map { it.speedKmh }.average(),
                maxPowerW = samples.maxOf { it.powerW }
            )
        }
    }
}

data class TripStats(
    val durationMs: Long,
    val maxSpeedKmh: Double,
    val avgSpeedKmh: Double,
    val maxPowerW: Double
)
