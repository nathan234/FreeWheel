package org.freewheel.core.telemetry

import kotlin.math.abs

/**
 * Aggregate summary of a ride, computed from a list of [TelemetrySample].
 *
 * Consumed by Android (Compose) and iOS (SwiftUI) replay screens so the stats
 * header, replay panel, and max-PWM badge all read the same numbers.
 */
data class TripStats(
    val durationMs: Long,
    val maxSpeedKmh: Double,
    val avgSpeedKmh: Double,
    val maxPowerW: Double,
    val avgPowerW: Double,
    val maxCurrentA: Double,
    val maxPwmPercent: Double,
    val maxTemperatureC: Double,
    val minBatteryPercent: Double,
    val maxBatteryPercent: Double
)

/**
 * Pure functions that prepare telemetry sample data for chart rendering.
 *
 * Extracted here so Android (Vico) and iOS (Swift Charts) can share ride-
 * replay data prep instead of each maintaining its own `chartFullDomain`,
 * `nearestSample`, and `computeStats` helpers.
 *
 * All functions are pure (no state) and work on any [TelemetrySample] list,
 * making them trivially unit-testable.
 */
object ChartDataPrep {

    /**
     * Full time span of a sample list in milliseconds.
     *
     * Returns 0 for empty or single-sample lists. Useful for initializing
     * chart visible-domain state on iOS, where SwiftUI Charts' `chartXVisibleDomain`
     * does not auto-fit the way Vico's `Zoom.Content` does on Android.
     */
    fun fullDomainMs(samples: List<TelemetrySample>): Long {
        if (samples.size < 2) return 0L
        return samples.last().timestampMs - samples.first().timestampMs
    }

    /**
     * Sample whose timestamp is closest to [targetTimestampMs], or null if
     * [samples] is empty. Used by chart hover/selection handlers to map a
     * tap position back to a sample.
     *
     * Linear scan — fine for the typical 2-3k sample range after downsampling
     * in [org.freewheel.core.logging.CsvParser].
     */
    fun nearestSample(samples: List<TelemetrySample>, targetTimestampMs: Long): TelemetrySample? {
        if (samples.isEmpty()) return null
        var best = samples[0]
        var bestDist = abs(best.timestampMs - targetTimestampMs)
        for (i in 1 until samples.size) {
            val s = samples[i]
            val d = abs(s.timestampMs - targetTimestampMs)
            if (d < bestDist) {
                best = s
                bestDist = d
            }
        }
        return best
    }

    /**
     * Single-pass aggregate over [samples]. Returns null when there is not
     * enough data to produce a meaningful summary (fewer than 2 samples).
     */
    fun computeTripStats(samples: List<TelemetrySample>): TripStats? {
        if (samples.size < 2) return null

        var maxSpeed = Double.NEGATIVE_INFINITY
        var speedSum = 0.0
        var maxPower = Double.NEGATIVE_INFINITY
        var powerSum = 0.0
        var maxCurrent = Double.NEGATIVE_INFINITY
        var maxPwm = Double.NEGATIVE_INFINITY
        var maxTemp = Double.NEGATIVE_INFINITY
        var minBattery = Double.POSITIVE_INFINITY
        var maxBattery = Double.NEGATIVE_INFINITY

        for (s in samples) {
            if (s.speedKmh > maxSpeed) maxSpeed = s.speedKmh
            speedSum += s.speedKmh
            if (s.powerW > maxPower) maxPower = s.powerW
            powerSum += s.powerW
            if (s.currentA > maxCurrent) maxCurrent = s.currentA
            if (s.pwmPercent > maxPwm) maxPwm = s.pwmPercent
            if (s.temperatureC > maxTemp) maxTemp = s.temperatureC
            if (s.batteryPercent < minBattery) minBattery = s.batteryPercent
            if (s.batteryPercent > maxBattery) maxBattery = s.batteryPercent
        }

        val n = samples.size
        return TripStats(
            durationMs = samples.last().timestampMs - samples.first().timestampMs,
            maxSpeedKmh = maxSpeed,
            avgSpeedKmh = speedSum / n,
            maxPowerW = maxPower,
            avgPowerW = powerSum / n,
            maxCurrentA = maxCurrent,
            maxPwmPercent = maxPwm,
            maxTemperatureC = maxTemp,
            minBatteryPercent = minBattery,
            maxBatteryPercent = maxBattery
        )
    }
}
