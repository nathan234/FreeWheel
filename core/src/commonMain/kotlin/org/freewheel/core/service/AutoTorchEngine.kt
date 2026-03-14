package org.freewheel.core.service

import org.freewheel.core.utils.SunCalculator
import org.freewheel.core.utils.currentTimeMillis

/**
 * Pure-logic engine that decides whether the headlight should be on.
 *
 * Two independent triggers (either one activates the light):
 * 1. **Speed trigger**: wheel speed exceeds [speedThresholdKmh]
 * 2. **Sunset trigger**: current time is after sunset or before sunrise at the rider's GPS location
 *
 * The engine is stateless — call [shouldLightBeOn] on each telemetry tick.
 * The caller is responsible for:
 * - Sending the actual SetLight command when the result changes
 * - Throttling to avoid spamming commands
 * - Respecting the user's manual light toggle (auto-torch should not override manual off)
 */
object AutoTorchEngine {

    /**
     * @param speedKmh current wheel speed in km/h
     * @param speedThresholdKmh speed above which light should be on (0 = disabled)
     * @param useSunset whether to use sunset-based trigger
     * @param latitudeDeg rider latitude (ignored if [useSunset] is false)
     * @param longitudeDeg rider longitude (ignored if [useSunset] is false)
     * @param epochMillis current time in millis since epoch (defaults to now)
     * @return result indicating whether light should be on and why
     */
    fun shouldLightBeOn(
        speedKmh: Double,
        speedThresholdKmh: Int,
        useSunset: Boolean,
        latitudeDeg: Double = 0.0,
        longitudeDeg: Double = 0.0,
        epochMillis: Long = currentTimeMillis()
    ): AutoTorchResult {
        val speedTriggered = speedThresholdKmh > 0 && speedKmh >= speedThresholdKmh
        val sunsetTriggered = if (useSunset && (latitudeDeg != 0.0 || longitudeDeg != 0.0)) {
            isSunsetNow(epochMillis, latitudeDeg, longitudeDeg)
        } else {
            false
        }

        return AutoTorchResult(
            shouldBeOn = speedTriggered || sunsetTriggered,
            speedTriggered = speedTriggered,
            sunsetTriggered = sunsetTriggered
        )
    }

    private fun isSunsetNow(epochMillis: Long, latitudeDeg: Double, longitudeDeg: Double): Boolean {
        val sunTimes = SunCalculator.calculate(epochMillis, latitudeDeg, longitudeDeg)
            ?: return false // polar region, can't determine

        // Current UTC hour-of-day
        val millisInDay = epochMillis % 86_400_000L
        val currentHourUtc = millisInDay / 3_600_000.0

        return SunCalculator.isDark(currentHourUtc, sunTimes)
    }
}

data class AutoTorchResult(
    val shouldBeOn: Boolean,
    val speedTriggered: Boolean,
    val sunsetTriggered: Boolean
)
