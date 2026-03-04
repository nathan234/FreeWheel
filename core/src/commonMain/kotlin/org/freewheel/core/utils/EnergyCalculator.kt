package org.freewheel.core.utils

/**
 * Calculates energy consumption metrics over a rolling time window.
 *
 * Tracks power samples over the last 10 seconds to calculate:
 * - **Power-hour (Wh)**: Total energy consumed in the window
 * - **Wh/km**: Energy consumption per kilometer
 *
 * ## Usage
 * ```
 * val calculator = EnergyCalculator()
 *
 * // Push samples as they arrive (typically every 100-500ms)
 * calculator.pushSample(powerWatts, distanceMeters, currentTimeMs)
 *
 * // Get current metrics
 * val wh = calculator.getPowerHour(currentTimeMs)
 * val whPerKm = calculator.getWhPerKm(currentTimeMs)
 * ```
 *
 * This class is thread-safe.
 */
class EnergyCalculator {

    private data class PowerSample(
        val timeMs: Long,
        val distanceMeters: Int,
        val powerWatts: Double
    )

    private val samples = mutableListOf<PowerSample>()
    private val lock = Lock()

    // Cache for computed values
    private var cachedPowerHour: Double = 0.0
    private var cachedPowerHourTime: Long = 0
    private var cachedWhPerKm: Double = 0.0
    private var cachedWhPerKmTime: Long = 0

    companion object {
        /** Maximum age of samples to keep (10 seconds) */
        private const val MAX_SAMPLE_AGE_MS = 10_000L

        /** Data is stale if last sample is older than this (2 seconds) */
        private const val STALE_THRESHOLD_MS = 2_000L

        /** Cache validity duration (1 second) */
        private const val CACHE_DURATION_MS = 1_000L
    }

    /**
     * Add a power sample.
     *
     * @param powerWatts Current power consumption in watts
     * @param distanceMeters Total distance traveled in meters
     * @param currentTimeMs Current time in milliseconds
     */
    fun pushSample(powerWatts: Double, distanceMeters: Int, currentTimeMs: Long) {
        lock.withLock {
            samples.add(PowerSample(currentTimeMs, distanceMeters, powerWatts))
            pruneOldSamplesInternal(currentTimeMs)
        }
    }

    /**
     * Get energy consumed in the sample window (watt-hours).
     *
     * @param currentTimeMs Current time in milliseconds
     * @return Energy in Wh, or 0.0 if no recent data
     */
    fun getPowerHour(currentTimeMs: Long): Double = lock.withLock {
        pruneOldSamplesInternal(currentTimeMs)

        // Return cached value if still valid (check after prune)
        if (currentTimeMs - cachedPowerHourTime < CACHE_DURATION_MS) {
            return@withLock cachedPowerHour
        }

        if (!isDataFreshInternal(currentTimeMs)) {
            return@withLock cachedPowerHour
        }

        if (samples.size < 2) {
            return@withLock 0.0
        }

        val elapsedTimeMs = samples.last().timeMs - samples.first().timeMs
        if (elapsedTimeMs <= 0) {
            return@withLock 0.0
        }

        // Average power over the window
        val avgPower = samples.sumOf { it.powerWatts } / samples.size

        // Convert to watt-hours: power * time / (ms per hour)
        cachedPowerHour = avgPower * elapsedTimeMs / 3_600_000.0
        cachedPowerHourTime = currentTimeMs

        cachedPowerHour
    }

    /**
     * Get energy consumption per kilometer (Wh/km).
     *
     * @param currentTimeMs Current time in milliseconds
     * @return Wh/km, or 0.0 if no distance traveled or no recent data
     */
    fun getWhPerKm(currentTimeMs: Long): Double = lock.withLock {
        // Return cached value if still valid
        if (currentTimeMs - cachedWhPerKmTime < CACHE_DURATION_MS) {
            return@withLock cachedWhPerKm
        }

        if (!isDataFreshInternal(currentTimeMs)) {
            return@withLock cachedWhPerKm
        }

        if (samples.size < 2) {
            return@withLock 0.0
        }

        val distanceMeters = samples.last().distanceMeters - samples.first().distanceMeters
        if (distanceMeters <= 0) {
            return@withLock 0.0
        }

        // Need to compute power hour without lock (already holding it)
        val powerHour = computePowerHourInternal(currentTimeMs)

        // Convert meters to km
        cachedWhPerKm = powerHour * 1000.0 / distanceMeters
        cachedWhPerKmTime = currentTimeMs

        cachedWhPerKm
    }

    /**
     * Clear all samples and reset the calculator.
     */
    fun reset() {
        lock.withLock {
            samples.clear()
            cachedPowerHour = 0.0
            cachedPowerHourTime = 0
            cachedWhPerKm = 0.0
            cachedWhPerKmTime = 0
        }
    }

    /**
     * Get the number of samples currently stored.
     */
    val sampleCount: Int
        get() = lock.withLock { samples.size }

    // Internal methods (must be called while holding lock)

    private fun isDataFreshInternal(currentTimeMs: Long): Boolean {
        if (samples.isEmpty()) return false
        return currentTimeMs - samples.last().timeMs < STALE_THRESHOLD_MS
    }

    private fun pruneOldSamplesInternal(currentTimeMs: Long) {
        val expiryTime = currentTimeMs - MAX_SAMPLE_AGE_MS
        samples.removeAll { it.timeMs < expiryTime }
    }

    private fun computePowerHourInternal(currentTimeMs: Long): Double {
        if (samples.size < 2) return 0.0

        val elapsedTimeMs = samples.last().timeMs - samples.first().timeMs
        if (elapsedTimeMs <= 0) return 0.0

        val avgPower = samples.sumOf { it.powerWatts } / samples.size
        return avgPower * elapsedTimeMs / 3_600_000.0
    }
}
