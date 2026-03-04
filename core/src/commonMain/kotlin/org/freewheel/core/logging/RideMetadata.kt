package org.freewheel.core.logging

/**
 * Metadata about a completed ride recording.
 */
data class RideMetadata(
    val fileName: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationSeconds: Long,
    val distanceMeters: Long,
    val maxSpeedKmh: Double,
    val avgSpeedKmh: Double,
    val sampleCount: Int,
    val maxCurrentA: Double = 0.0,
    val maxPowerW: Double = 0.0,
    val maxPwmPercent: Double = 0.0,
    val consumptionWh: Double = 0.0,
    val consumptionWhPerKm: Double = 0.0
)
