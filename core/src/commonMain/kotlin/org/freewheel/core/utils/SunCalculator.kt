package org.freewheel.core.utils

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Simplified sunrise/sunset calculator using the NOAA solar equations.
 *
 * Returns sunrise and sunset times as hours-of-day (0.0–24.0) in UTC.
 * Accuracy: within ~2 minutes for latitudes up to ±60°.
 *
 * Returns null for polar regions where the sun doesn't rise or set on the given day.
 */
object SunCalculator {

    private const val DEG_TO_RAD = PI / 180.0
    private const val RAD_TO_DEG = 180.0 / PI

    /**
     * @param dayOfYear 1-based day of year (1 = Jan 1, 365/366 = Dec 31)
     * @param latitudeDeg latitude in decimal degrees (positive = north)
     * @param longitudeDeg longitude in decimal degrees (positive = east)
     * @return pair of (sunriseHourUtc, sunsetHourUtc) or null if sun never rises/sets
     */
    fun calculate(dayOfYear: Int, latitudeDeg: Double, longitudeDeg: Double): SunTimes? {
        // Fractional year (radians)
        val gamma = 2.0 * PI / 365.0 * (dayOfYear - 1)

        // Equation of time (minutes)
        val eqTime = 229.18 * (
            0.000075 +
            0.001868 * cos(gamma) -
            0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) -
            0.040849 * sin(2 * gamma)
        )

        // Solar declination (radians)
        val decl = 0.006918 -
            0.399912 * cos(gamma) +
            0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) +
            0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) +
            0.00148 * sin(3 * gamma)

        val latRad = latitudeDeg * DEG_TO_RAD

        // Hour angle (degrees)
        val zenith = 90.833 * DEG_TO_RAD // official sunrise/sunset zenith
        val cosHa = (cos(zenith) / (cos(latRad) * cos(decl))) - tan(latRad) * tan(decl)

        // Sun never rises or never sets at this latitude on this day
        if (cosHa < -1.0 || cosHa > 1.0) return null

        val ha = acos(cosHa) * RAD_TO_DEG

        // Sunrise and sunset in minutes from midnight UTC
        val sunriseMin = 720 - 4 * (longitudeDeg + ha) - eqTime
        val sunsetMin = 720 - 4 * (longitudeDeg - ha) - eqTime

        return SunTimes(
            sunriseHourUtc = sunriseMin / 60.0,
            sunsetHourUtc = sunsetMin / 60.0
        )
    }

    /**
     * Convenience overload: takes epoch millis and extracts day-of-year.
     */
    fun calculate(epochMillis: Long, latitudeDeg: Double, longitudeDeg: Double): SunTimes? {
        val dayOfYear = dayOfYearFromEpoch(epochMillis)
        return calculate(dayOfYear, latitudeDeg, longitudeDeg)
    }

    /**
     * Returns true if the given UTC hour-of-day falls between sunset and sunrise (i.e., it's dark).
     */
    fun isDark(currentHourUtc: Double, sunTimes: SunTimes): Boolean {
        return currentHourUtc < sunTimes.sunriseHourUtc || currentHourUtc > sunTimes.sunsetHourUtc
    }

    // Extract day-of-year from epoch millis (1-based, UTC)
    internal fun dayOfYearFromEpoch(epochMillis: Long): Int {
        val days = epochMillis / 86_400_000L // days since epoch
        // Approximate: epoch is 1970-01-01
        val y400 = 146097L // days in 400-year cycle
        val dayInCycle = ((days % y400) + y400) % y400

        // Simplified: use Gregorian calendar arithmetic
        var remaining = days
        var year = 1970
        while (true) {
            val daysInYear = if (isLeapYear(year)) 366 else 365
            if (remaining < daysInYear) break
            remaining -= daysInYear
            year++
        }
        // While going backwards for negative remaining
        while (remaining < 0) {
            year--
            val daysInYear = if (isLeapYear(year)) 366 else 365
            remaining += daysInYear
        }
        return remaining.toInt() + 1 // 1-based
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}

data class SunTimes(
    val sunriseHourUtc: Double,
    val sunsetHourUtc: Double
)
