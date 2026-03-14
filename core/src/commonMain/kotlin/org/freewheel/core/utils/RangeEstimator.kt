package org.freewheel.core.utils

/**
 * Estimates remaining range from observed battery consumption rate.
 *
 * Uses the session's battery-per-km rate: if you've traveled X km and used Y% battery,
 * each 1% ≈ X/Y km, so remaining range ≈ currentBattery × (X/Y).
 *
 * This approach doesn't require knowing battery capacity in Wh — it works purely from
 * observed consumption, getting more accurate as you ride further.
 *
 * Returns null when insufficient data is available (< 1% battery used or < 0.5 km traveled).
 */
object RangeEstimator {

    /**
     * @param currentBattery current battery level 0-100
     * @param tripDistanceKm distance traveled this session
     * @param startBattery battery level at start of session
     * @return estimated remaining range in km, or null if insufficient data
     */
    fun estimate(
        currentBattery: Int,
        tripDistanceKm: Double,
        startBattery: Int
    ): Double? {
        val batteryUsed = startBattery - currentBattery
        if (batteryUsed < 1 || tripDistanceKm < 0.5) return null
        val kmPerPercent = tripDistanceKm / batteryUsed
        return currentBattery * kmPerPercent
    }
}
