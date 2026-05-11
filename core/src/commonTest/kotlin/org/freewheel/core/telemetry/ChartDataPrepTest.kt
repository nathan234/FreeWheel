package org.freewheel.core.telemetry

import org.freewheel.core.logging.RoutePoint
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

    // ==================== nearestRoutePoint ====================

    private fun routePoint(
        timestampMs: Long,
        latitude: Double = 37.7749,
        longitude: Double = -122.4194,
        speedKmh: Double = 0.0
    ) = RoutePoint(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitude = 0.0,
        bearing = 0.0,
        speedKmh = speedKmh,
        gpsSpeedKmh = 0.0
    )

    @Test
    fun `nearestRoutePoint returns null for empty list`() {
        assertNull(ChartDataPrep.nearestRoutePoint(emptyList(), 1000L))
    }

    @Test
    fun `nearestRoutePoint returns only point when list has one`() {
        val only = routePoint(1000L, latitude = 37.78)
        assertEquals(only, ChartDataPrep.nearestRoutePoint(listOf(only), 5000L))
    }

    @Test
    fun `nearestRoutePoint picks closest by absolute distance`() {
        val points = listOf(
            routePoint(0L, speedKmh = 10.0),
            routePoint(1000L, speedKmh = 20.0),
            routePoint(5000L, speedKmh = 30.0),
            routePoint(9000L, speedKmh = 40.0)
        )
        assertEquals(20.0, ChartDataPrep.nearestRoutePoint(points, 600L)?.speedKmh)
        assertEquals(30.0, ChartDataPrep.nearestRoutePoint(points, 4000L)?.speedKmh)
    }

    @Test
    fun `nearestRoutePoint handles targets outside range`() {
        val points = listOf(
            routePoint(1000L, speedKmh = 10.0),
            routePoint(2000L, speedKmh = 20.0)
        )
        assertEquals(10.0, ChartDataPrep.nearestRoutePoint(points, 0L)?.speedKmh)
        assertEquals(20.0, ChartDataPrep.nearestRoutePoint(points, 10_000L)?.speedKmh)
    }

    // ==================== speedColorFraction ====================

    @Test
    fun `speedColorFraction returns 0 for minimum speed`() {
        val points = listOf(
            routePoint(0L, speedKmh = 10.0),
            routePoint(1000L, speedKmh = 30.0),
            routePoint(2000L, speedKmh = 50.0)
        )
        assertEquals(0.0, ChartDataPrep.speedColorFraction(10.0, points), 0.001)
    }

    @Test
    fun `speedColorFraction returns 1 for maximum speed`() {
        val points = listOf(
            routePoint(0L, speedKmh = 10.0),
            routePoint(1000L, speedKmh = 30.0),
            routePoint(2000L, speedKmh = 50.0)
        )
        assertEquals(1.0, ChartDataPrep.speedColorFraction(50.0, points), 0.001)
    }

    @Test
    fun `speedColorFraction returns 0_5 for midpoint speed`() {
        val points = listOf(
            routePoint(0L, speedKmh = 0.0),
            routePoint(1000L, speedKmh = 100.0)
        )
        assertEquals(0.5, ChartDataPrep.speedColorFraction(50.0, points), 0.001)
    }

    @Test
    fun `speedColorFraction returns 0 when all speeds are equal`() {
        val points = listOf(
            routePoint(0L, speedKmh = 25.0),
            routePoint(1000L, speedKmh = 25.0)
        )
        assertEquals(0.0, ChartDataPrep.speedColorFraction(25.0, points), 0.001)
    }

    @Test
    fun `speedColorFraction clamps values outside range`() {
        val points = listOf(
            routePoint(0L, speedKmh = 10.0),
            routePoint(1000L, speedKmh = 50.0)
        )
        assertEquals(0.0, ChartDataPrep.speedColorFraction(5.0, points), 0.001)
        assertEquals(1.0, ChartDataPrep.speedColorFraction(60.0, points), 0.001)
    }

    @Test
    fun `speedColorFraction returns 0 for empty list`() {
        assertEquals(0.0, ChartDataPrep.speedColorFraction(25.0, emptyList()), 0.001)
    }

    // ==================== routePointForReplay ====================
    //
    // GPS points and telemetry samples are recorded at different cadences
    // (GPS ~1 Hz, telemetry ~10 Hz), so the route point at replay index N is
    // NOT routePoints[N] — it's the point whose timestamp is nearest the
    // current sample's timestamp. These tests pin that behavior so the
    // replay-cursor → map-marker wiring can rely on it.

    @Test
    fun `routePointForReplay returns null when samples is empty`() {
        val state = ReplayPlaybackState()
        val points = listOf(routePoint(1000L, speedKmh = 10.0))
        assertNull(ChartDataPrep.routePointForReplay(state, emptyList(), points))
    }

    @Test
    fun `routePointForReplay returns null when routePoints is empty`() {
        val samples = listOf(sample(0L), sample(1000L))
        val state = ReplayPlaybackState(currentIndex = 0)
        assertNull(ChartDataPrep.routePointForReplay(state, samples, emptyList()))
    }

    @Test
    fun `routePointForReplay at index 0 returns point nearest first sample timestamp`() {
        val samples = listOf(sample(0L), sample(500L), sample(1000L))
        val points = listOf(
            routePoint(0L, speedKmh = 10.0),
            routePoint(1000L, speedKmh = 20.0)
        )
        val state = ReplayPlaybackState(currentIndex = 0)
        assertEquals(10.0, ChartDataPrep.routePointForReplay(state, samples, points)?.speedKmh)
    }

    @Test
    fun `routePointForReplay after seekTo(0_5) tracks the middle sample`() {
        // 5 samples evenly spaced 0..4000ms — seek(0.5) lands on index 2 (timestamp 2000ms).
        val samples = (0..4).map { sample((it * 1000L)) }
        val points = listOf(
            routePoint(0L, speedKmh = 10.0),
            routePoint(2000L, speedKmh = 25.0),
            routePoint(4000L, speedKmh = 40.0)
        )
        val state = ReplayPlaybackReducer.seekTo(ReplayPlaybackState(), 0.5f, samples)
        assertEquals(25.0, ChartDataPrep.routePointForReplay(state, samples, points)?.speedKmh)
    }

    @Test
    fun `routePointForReplay after seekTo(1_0) returns last route point`() {
        val samples = (0..4).map { sample((it * 1000L)) }
        val points = listOf(
            routePoint(0L, speedKmh = 10.0),
            routePoint(2000L, speedKmh = 25.0),
            routePoint(4000L, speedKmh = 40.0)
        )
        val state = ReplayPlaybackReducer.seekTo(ReplayPlaybackState(), 1.0f, samples)
        assertEquals(40.0, ChartDataPrep.routePointForReplay(state, samples, points)?.speedKmh)
    }

    @Test
    fun `routePointForReplay tracks advanceOne progression`() {
        val samples = listOf(sample(0L), sample(1000L), sample(2000L), sample(3000L))
        val points = listOf(
            routePoint(0L, speedKmh = 10.0),
            routePoint(1000L, speedKmh = 20.0),
            routePoint(2000L, speedKmh = 30.0),
            routePoint(3000L, speedKmh = 40.0)
        )
        var state = ReplayPlaybackReducer.play(ReplayPlaybackState())
        assertEquals(10.0, ChartDataPrep.routePointForReplay(state, samples, points)?.speedKmh)
        state = ReplayPlaybackReducer.advanceOne(state, samples).state
        assertEquals(20.0, ChartDataPrep.routePointForReplay(state, samples, points)?.speedKmh)
        state = ReplayPlaybackReducer.advanceOne(state, samples).state
        assertEquals(30.0, ChartDataPrep.routePointForReplay(state, samples, points)?.speedKmh)
        state = ReplayPlaybackReducer.advanceOne(state, samples).state
        assertEquals(40.0, ChartDataPrep.routePointForReplay(state, samples, points)?.speedKmh)
    }

    @Test
    fun `routePointForReplay uses timestamp not index when GPS is sparser than telemetry`() {
        // 10 telemetry samples at 100ms cadence (0..900ms),
        // 2 route points at 1Hz-ish cadence (0, 900ms) — typical real-world ratio.
        // Indexing naively (routePoints[currentIndex]) would either out-of-bounds or
        // pick the wrong point. The helper must pick by nearest timestamp.
        val samples = (0..9).map { sample((it * 100L)) }
        val points = listOf(
            routePoint(0L, speedKmh = 5.0),
            routePoint(900L, speedKmh = 45.0)
        )
        // Sample 0 (ts=0): closer to point at 0ms → speed 5
        val s0 = ReplayPlaybackState(currentIndex = 0)
        assertEquals(5.0, ChartDataPrep.routePointForReplay(s0, samples, points)?.speedKmh)
        // Sample 4 (ts=400): closer to 0ms (|400-0|=400) than 900ms (|400-900|=500) → speed 5
        val s4 = ReplayPlaybackState(currentIndex = 4)
        assertEquals(5.0, ChartDataPrep.routePointForReplay(s4, samples, points)?.speedKmh)
        // Sample 5 (ts=500): closer to 900ms (|500-900|=400) than 0ms (|500-0|=500) → speed 45
        val s5 = ReplayPlaybackState(currentIndex = 5)
        assertEquals(45.0, ChartDataPrep.routePointForReplay(s5, samples, points)?.speedKmh)
        // Sample 9 (ts=900): exact match on point at 900ms → speed 45
        val s9 = ReplayPlaybackState(currentIndex = 9)
        assertEquals(45.0, ChartDataPrep.routePointForReplay(s9, samples, points)?.speedKmh)
    }

    @Test
    fun `routePointForReplay returns last route point when state is finished`() {
        // advanceOne flips isFinished=true on the final index. currentSample still points
        // at the last sample, so the helper should still return the corresponding route point
        // (not null) — otherwise the map marker would jump away when playback ends.
        val samples = listOf(sample(0L), sample(1000L))
        val points = listOf(
            routePoint(0L, speedKmh = 10.0),
            routePoint(1000L, speedKmh = 30.0)
        )
        var state = ReplayPlaybackReducer.play(ReplayPlaybackState())
        state = ReplayPlaybackReducer.advanceOne(state, samples).state
        assertEquals(true, state.isFinished)
        assertEquals(30.0, ChartDataPrep.routePointForReplay(state, samples, points)?.speedKmh)
    }
}
