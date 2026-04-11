package org.freewheel.core.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChartDataPrepTest {

    private fun sample(
        timestampMs: Long,
        speedKmh: Double = 0.0,
        currentA: Double = 0.0,
        powerW: Double = 0.0,
        temperatureC: Double = 0.0,
        batteryPercent: Double = 0.0,
        pwmPercent: Double = 0.0,
    ) = TelemetrySample(
        timestampMs = timestampMs,
        speedKmh = speedKmh,
        voltageV = 0.0,
        currentA = currentA,
        powerW = powerW,
        temperatureC = temperatureC,
        batteryPercent = batteryPercent,
        pwmPercent = pwmPercent,
    )

    // ==================== fullDomainMs ====================

    @Test
    fun `fullDomainMs returns zero for empty list`() {
        assertEquals(0L, ChartDataPrep.fullDomainMs(emptyList()))
    }

    @Test
    fun `fullDomainMs returns zero for single sample`() {
        assertEquals(0L, ChartDataPrep.fullDomainMs(listOf(sample(1000L))))
    }

    @Test
    fun `fullDomainMs returns last minus first timestamp`() {
        val samples = listOf(sample(0L), sample(30_000L), sample(90_000L))
        assertEquals(90_000L, ChartDataPrep.fullDomainMs(samples))
    }

    // ==================== nearestSample ====================

    @Test
    fun `nearestSample returns null for empty list`() {
        assertNull(ChartDataPrep.nearestSample(emptyList(), 1000L))
    }

    @Test
    fun `nearestSample returns only sample when list has one`() {
        val only = sample(1000L, speedKmh = 15.0)
        assertEquals(only, ChartDataPrep.nearestSample(listOf(only), 5000L))
    }

    @Test
    fun `nearestSample picks closest by absolute distance`() {
        val samples = listOf(
            sample(0L, speedKmh = 1.0),
            sample(1000L, speedKmh = 2.0),
            sample(5000L, speedKmh = 3.0),
            sample(9000L, speedKmh = 4.0)
        )
        // 600 is closer to 1000 than to 0
        assertEquals(2.0, ChartDataPrep.nearestSample(samples, 600L)?.speedKmh)
        // 4000 is closer to 5000 than to 1000
        assertEquals(3.0, ChartDataPrep.nearestSample(samples, 4000L)?.speedKmh)
        // 7500 is exactly midway between 5000 and 9000 — first match (5000) wins
        assertEquals(3.0, ChartDataPrep.nearestSample(samples, 7000L)?.speedKmh)
    }

    @Test
    fun `nearestSample handles targets outside sample range`() {
        val samples = listOf(sample(1000L, speedKmh = 1.0), sample(2000L, speedKmh = 2.0))
        // Before first
        assertEquals(1.0, ChartDataPrep.nearestSample(samples, 0L)?.speedKmh)
        // After last
        assertEquals(2.0, ChartDataPrep.nearestSample(samples, 10_000L)?.speedKmh)
    }

    // ==================== computeTripStats ====================

    @Test
    fun `computeTripStats returns null for empty list`() {
        assertNull(ChartDataPrep.computeTripStats(emptyList()))
    }

    @Test
    fun `computeTripStats returns null for single sample`() {
        assertNull(ChartDataPrep.computeTripStats(listOf(sample(1000L, speedKmh = 25.0))))
    }

    @Test
    fun `computeTripStats aggregates all metrics in one pass`() {
        val samples = listOf(
            sample(0L, speedKmh = 10.0, currentA = 5.0, powerW = 420.0,
                   temperatureC = 30.0, batteryPercent = 95.0, pwmPercent = 20.0),
            sample(60_000L, speedKmh = 30.0, currentA = 25.0, powerW = 2100.0,
                   temperatureC = 42.0, batteryPercent = 80.0, pwmPercent = 75.0),
            sample(120_000L, speedKmh = 20.0, currentA = 10.0, powerW = 840.0,
                   temperatureC = 38.0, batteryPercent = 70.0, pwmPercent = 45.0)
        )

        val stats = ChartDataPrep.computeTripStats(samples)!!

        assertEquals(120_000L, stats.durationMs)
        assertEquals(30.0, stats.maxSpeedKmh)
        assertEquals(20.0, stats.avgSpeedKmh, 0.001)
        assertEquals(2100.0, stats.maxPowerW)
        assertEquals(1120.0, stats.avgPowerW, 0.001)
        assertEquals(25.0, stats.maxCurrentA)
        assertEquals(75.0, stats.maxPwmPercent)
        assertEquals(42.0, stats.maxTemperatureC)
        assertEquals(70.0, stats.minBatteryPercent)
        assertEquals(95.0, stats.maxBatteryPercent)
    }

    @Test
    fun `computeTripStats handles negative and zero values without crashing`() {
        // Braking: negative current. Cold start: 0 temp. Idle wheel: 0 pwm.
        val samples = listOf(
            sample(0L, speedKmh = 0.0, currentA = -5.0, powerW = 0.0,
                   temperatureC = 0.0, batteryPercent = 100.0, pwmPercent = 0.0),
            sample(1000L, speedKmh = 12.0, currentA = 8.0, powerW = 600.0,
                   temperatureC = 25.0, batteryPercent = 100.0, pwmPercent = 18.0)
        )

        val stats = ChartDataPrep.computeTripStats(samples)!!
        assertEquals(12.0, stats.maxSpeedKmh)
        assertEquals(8.0, stats.maxCurrentA)
        assertEquals(18.0, stats.maxPwmPercent)
        assertEquals(100.0, stats.minBatteryPercent)
        assertEquals(100.0, stats.maxBatteryPercent)
    }

    @Test
    fun `computeTripStats uses first and last timestamps for duration regardless of order sensitivity`() {
        // Samples are assumed to be in chronological order (as CsvParser produces them).
        val samples = listOf(sample(1_000L), sample(2_500L), sample(91_000L))
        val stats = ChartDataPrep.computeTripStats(samples)!!
        assertEquals(90_000L, stats.durationMs)
    }
}
