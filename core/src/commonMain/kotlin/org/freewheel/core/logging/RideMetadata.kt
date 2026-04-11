package org.freewheel.core.logging

/**
 * Live statistics snapshot for an in-progress ride recording.
 */
data class LiveRideStats(
    val startTimeMs: Long,
    val elapsedMs: Long,
    val maxSpeedKmh: Double,
    val distanceMeters: Long,
    val maxPwmPercent: Double = 0.0
)

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
