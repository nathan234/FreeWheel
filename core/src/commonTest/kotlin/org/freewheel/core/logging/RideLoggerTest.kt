package org.freewheel.core.logging

import org.freewheel.core.domain.TelemetryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [RideLogger].
 *
 * Uses a [CapturingFileWriter] to avoid real file I/O and capture written lines.
 */
class RideLoggerTest {

    /**
     * In-memory FileWriter replacement that captures all written lines.
     * Since FileWriter is an expect/actual class, we use a real instance but redirect
     * to a temp path. However, to keep tests fast and I/O-free, we test the logic
     * through RideLogger's public API using a real FileWriter with temp files.
     */

    // ==================== Start/Stop Lifecycle ====================

    @Test
    fun `isLogging is false initially`() {
        val logger = RideLogger()
        assertFalse(logger.isLogging)
    }

    @Test
    fun `start sets isLogging to true`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        val result = logger.start(tmpPath, withGps = false, currentTimeMs = 1000)
        assertTrue(result)
        assertTrue(logger.isLogging)
        logger.stop(2000)
    }

    @Test
    fun `start while already logging returns false`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        assertTrue(logger.start(tmpPath, withGps = false, currentTimeMs = 1000))
        assertFalse(logger.start(tmpPath, withGps = false, currentTimeMs = 2000))
        logger.stop(3000)
    }

    @Test
    fun `stop sets isLogging to false`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 1000)
        logger.stop(2000)
        assertFalse(logger.isLogging)
    }

    @Test
    fun `stop while not logging returns null`() {
        val logger = RideLogger()
        assertNull(logger.stop(1000))
    }

    // ==================== Metadata Tracking ====================

    @Test
    fun `stop returns metadata with correct duration`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        val startMs = 10_000L
        val endMs = 70_000L  // 60 seconds later

        logger.start(tmpPath, withGps = false, currentTimeMs = startMs)
        // Write at least one sample
        val telemetry = TelemetryState(speed = 2500) // 25 km/h
        logger.writeSample(telemetry, gps = null, currentTimeMs = startMs + 1000)
        val metadata = logger.stop(endMs, lastTotalDistance = 1000)

        assertNotNull(metadata)
        assertEquals(60, metadata.durationSeconds)
        assertEquals(startMs, metadata.startTimeMillis)
        assertEquals(endMs, metadata.endTimeMillis)
    }

    @Test
    fun `stop returns correct max speed`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)  // 20 km/h
        logger.writeSample(TelemetryState(speed = 3500), gps = null, currentTimeMs = 2000)  // 35 km/h
        logger.writeSample(TelemetryState(speed = 2500), gps = null, currentTimeMs = 3000)  // 25 km/h

        val metadata = logger.stop(4000)
        assertNotNull(metadata)
        assertEquals(35.0, metadata.maxSpeedKmh, 0.01)
    }

    @Test
    fun `stop returns correct average speed`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)  // 20 km/h
        logger.writeSample(TelemetryState(speed = 3000), gps = null, currentTimeMs = 2000)  // 30 km/h
        logger.writeSample(TelemetryState(speed = 4000), gps = null, currentTimeMs = 3000)  // 40 km/h

        val metadata = logger.stop(4000)
        assertNotNull(metadata)
        // Average: (20 + 30 + 40) / 3 = 30
        assertEquals(30.0, metadata.avgSpeedKmh, 0.01)
    }

    @Test
    fun `stop returns correct sample count`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)
        logger.writeSample(TelemetryState(speed = 3000), gps = null, currentTimeMs = 2000)
        logger.writeSample(TelemetryState(speed = 4000), gps = null, currentTimeMs = 3000)

        val metadata = logger.stop(4000)
        assertNotNull(metadata)
        assertEquals(3, metadata.sampleCount)
    }

    @Test
    fun `stop returns correct distance`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        // First sample sets startTotalDistance
        logger.writeSample(TelemetryState(totalDistance = 50000), gps = null, currentTimeMs = 1000)
        logger.writeSample(TelemetryState(totalDistance = 51000), gps = null, currentTimeMs = 2000)

        val metadata = logger.stop(3000, lastTotalDistance = 51000)
        assertNotNull(metadata)
        assertEquals(1000, metadata.distanceMeters) // 51000 - 50000
    }

    @Test
    fun `stop returns max current as absolute value`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        logger.writeSample(TelemetryState(current = 500), gps = null, currentTimeMs = 1000)    // 5A
        logger.writeSample(TelemetryState(current = -1500), gps = null, currentTimeMs = 2000)  // -15A (regen)
        logger.writeSample(TelemetryState(current = 800), gps = null, currentTimeMs = 3000)    // 8A

        val metadata = logger.stop(4000)
        assertNotNull(metadata)
        assertEquals(15.0, metadata.maxCurrentA, 0.01) // abs(-15)
    }

    @Test
    fun `stop returns max power as absolute value`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        logger.writeSample(TelemetryState(power = 100000), gps = null, currentTimeMs = 1000)    // 1000W
        logger.writeSample(TelemetryState(power = -200000), gps = null, currentTimeMs = 2000)   // -2000W
        logger.writeSample(TelemetryState(power = 50000), gps = null, currentTimeMs = 3000)     // 500W

        val metadata = logger.stop(4000)
        assertNotNull(metadata)
        assertEquals(2000.0, metadata.maxPowerW, 0.01) // abs(-2000)
    }

    @Test
    fun `fileName is extracted from path`() {
        val logger = RideLogger()
        val tmpPath = createTempPath("ride_2024.csv")
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)
        logger.writeSample(TelemetryState(), gps = null, currentTimeMs = 1000)

        val metadata = logger.stop(2000)
        assertNotNull(metadata)
        assertTrue(metadata.fileName.endsWith(".csv"))
    }

    // ==================== 1Hz Throttling ====================

    @Test
    fun `writeSample throttles to 1Hz`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        // First sample at t=1000
        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)
        // Sample at t=1500 (500ms later) should be throttled
        logger.writeSample(TelemetryState(speed = 3000), gps = null, currentTimeMs = 1500)
        // Sample at t=1999 (999ms after first) should be throttled
        logger.writeSample(TelemetryState(speed = 4000), gps = null, currentTimeMs = 1999)
        // Sample at t=2000 (1000ms after first) should pass
        logger.writeSample(TelemetryState(speed = 5000), gps = null, currentTimeMs = 2000)

        val metadata = logger.stop(3000)
        assertNotNull(metadata)
        // Only 2 samples should have been written (t=1000 and t=2000)
        assertEquals(2, metadata.sampleCount)
    }

    @Test
    fun `throttled samples do not affect metadata`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)  // 20 km/h - written
        logger.writeSample(TelemetryState(speed = 9900), gps = null, currentTimeMs = 1100)  // 99 km/h - throttled
        logger.writeSample(TelemetryState(speed = 3000), gps = null, currentTimeMs = 2000)  // 30 km/h - written

        val metadata = logger.stop(3000)
        assertNotNull(metadata)
        // Max speed should NOT include throttled sample
        assertEquals(30.0, metadata.maxSpeedKmh, 0.01)
        assertEquals(25.0, metadata.avgSpeedKmh, 0.01) // (20+30)/2
    }

    @Test
    fun `writeSample does nothing when not active`() {
        val logger = RideLogger()
        // Don't call start()
        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)
        // No crash, no state mutation — verified by calling stop returning null
        assertNull(logger.stop(2000))
    }

    // ==================== Pause/Resume ====================

    @Test
    fun `resume allows immediate write after long pause`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)
        logger.writeSample(TelemetryState(speed = 3000), gps = null, currentTimeMs = 2000)

        // Simulate disconnect at t=3000, reconnect at t=300000
        logger.pause(3000)
        logger.resume(300_000)

        logger.writeSample(TelemetryState(speed = 4000), gps = null, currentTimeMs = 300_000)

        val metadata = logger.stop(301_000)
        assertNotNull(metadata)
        assertEquals(3, metadata.sampleCount)
        assertEquals(40.0, metadata.maxSpeedKmh, 0.01)
    }

    @Test
    fun `pause-resume subtracts paused gap from duration`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        // Start at t=0, ride for 10s, pause for 60s, ride for 10s more
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)
        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)

        logger.pause(10_000)      // disconnect at 10s
        logger.resume(70_000)     // reconnect at 70s (60s paused)

        logger.writeSample(TelemetryState(speed = 3000), gps = null, currentTimeMs = 71_000)

        val metadata = logger.stop(80_000)  // stop at 80s
        assertNotNull(metadata)
        // Total wall time = 80s, paused = 60s, active = 20s
        assertEquals(20, metadata.durationSeconds)
    }

    @Test
    fun `pause-resume subtracts paused gap from liveStats elapsed`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)
        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)

        logger.pause(10_000)
        logger.resume(70_000)

        val stats = logger.liveStats(80_000, 0)
        assertNotNull(stats)
        // Wall time = 80s, paused = 60s, active elapsed = 20s
        assertEquals(20_000, stats.elapsedMs)
        logger.stop(81_000)
    }

    @Test
    fun `liveStats during active pause excludes ongoing pause`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)
        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)

        logger.pause(10_000)

        // Query live stats while still paused at t=70000
        val stats = logger.liveStats(70_000, 0)
        assertNotNull(stats)
        // Should show only 10s of active time, not 70s
        assertEquals(10_000, stats.elapsedMs)
        logger.resume(70_000)
        logger.stop(71_000)
    }

    @Test
    fun `stop during active pause finalizes paused gap`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)
        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)

        logger.pause(10_000)
        // Stop without resuming — paused gap should be subtracted
        val metadata = logger.stop(70_000)
        assertNotNull(metadata)
        // Active time = 10s (0-10s), paused = 60s (10s-70s)
        assertEquals(10, metadata.durationSeconds)
    }

    @Test
    fun `multiple pause-resume cycles accumulate correctly`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)
        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)

        // Pause 1: 10s-30s (20s paused)
        logger.pause(10_000)
        logger.resume(30_000)
        logger.writeSample(TelemetryState(speed = 3000), gps = null, currentTimeMs = 31_000)

        // Pause 2: 40s-50s (10s paused)
        logger.pause(40_000)
        logger.resume(50_000)
        logger.writeSample(TelemetryState(speed = 4000), gps = null, currentTimeMs = 51_000)

        val metadata = logger.stop(60_000)
        assertNotNull(metadata)
        // Wall time = 60s, total paused = 30s, active = 30s
        assertEquals(30, metadata.durationSeconds)
    }

    @Test
    fun `pause-resume correctly computes energy consumption`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        // 100W power sample
        logger.writeSample(TelemetryState(power = 10000, totalDistance = 50000), gps = null, currentTimeMs = 1000)

        // Pause for 1 hour (should NOT count toward energy consumption)
        logger.pause(10_000)
        logger.resume(3610_000)

        logger.writeSample(TelemetryState(power = 10000, totalDistance = 60000), gps = null, currentTimeMs = 3611_000)

        val metadata = logger.stop(3620_000)
        assertNotNull(metadata)
        // Active duration = 3620 - 0 - 3600 paused = 20s
        // avgPower = 100W, consumption = 100 * 20 / 3600 ≈ 0.56 Wh (not 100 Wh)
        assertEquals(20, metadata.durationSeconds)
        assertTrue(metadata.consumptionWh < 1.0, "Paused time should not inflate energy consumption")
    }

    // ==================== GPS Handling ====================

    @Test
    fun `writeSample ignores GPS when started without GPS`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        val gps = GpsLocation(37.0, -122.0, 20.0, 50.0, 90.0, 500.0)
        // This should not crash and should ignore GPS data
        logger.writeSample(TelemetryState(speed = 2000), gps = gps, currentTimeMs = 1000)

        val metadata = logger.stop(2000)
        assertNotNull(metadata)
        assertEquals(1, metadata.sampleCount)
    }

    // ==================== Energy Calculations ====================

    @Test
    fun `consumptionWh calculated from average power and duration`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        // 3600 seconds of 100W average = 100 Wh
        // Write samples with 100W power (10000 in internal units)
        logger.writeSample(TelemetryState(power = 10000, totalDistance = 1000), gps = null, currentTimeMs = 1000)
        logger.writeSample(TelemetryState(power = 10000, totalDistance = 2000), gps = null, currentTimeMs = 2000)

        // Stop at 3601 seconds (1 hour)
        val metadata = logger.stop(3601_000, lastTotalDistance = 2000)
        assertNotNull(metadata)
        // avgPower = 100W, duration = 3600s, consumption = 100 * 3600 / 3600 = 100 Wh
        assertEquals(100.0, metadata.consumptionWh, 1.0)
    }

    @Test
    fun `consumptionWhPerKm calculated from energy and distance`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        // 100W average over 3600s = 100Wh, distance = 10km (10000m)
        // startTotalDistance must be > 0 for distance calculation to work
        logger.writeSample(TelemetryState(power = 10000, totalDistance = 50000), gps = null, currentTimeMs = 1000)
        logger.writeSample(TelemetryState(power = 10000, totalDistance = 60000), gps = null, currentTimeMs = 2000)

        val metadata = logger.stop(3601_000, lastTotalDistance = 60000)
        assertNotNull(metadata)
        // distance = 60000 - 50000 = 10000m = 10km
        // 100 Wh / 10 km = 10 Wh/km
        assertEquals(10.0, metadata.consumptionWhPerKm, 1.0)
    }

    @Test
    fun `zero duration yields zero consumption`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 1000)
        logger.writeSample(TelemetryState(power = 10000), gps = null, currentTimeMs = 1500)

        // Stop almost immediately (same second)
        val metadata = logger.stop(1500)
        assertNotNull(metadata)
        assertEquals(0.0, metadata.consumptionWh, 0.01)
    }

    @Test
    fun `zero distance yields zero consumptionWhPerKm`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)
        logger.writeSample(TelemetryState(power = 10000, totalDistance = 0), gps = null, currentTimeMs = 1000)

        val metadata = logger.stop(5000, lastTotalDistance = 0)
        assertNotNull(metadata)
        assertEquals(0.0, metadata.consumptionWhPerKm, 0.01)
    }

    // ==================== Double-Stop Idempotency ====================

    @Test
    fun `double stop after active logging returns null the second time`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)
        logger.writeSample(TelemetryState(speed = 2500), gps = null, currentTimeMs = 1000)

        val first = logger.stop(5000)
        assertNotNull(first)
        assertFalse(logger.isLogging)

        val second = logger.stop(6000)
        assertNull(second)
        assertFalse(logger.isLogging)
    }

    @Test
    fun `can start a new session after double stop`() {
        val logger = RideLogger()

        // First session
        logger.start(createTempPath(), withGps = false, currentTimeMs = 0)
        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 1000)
        assertNotNull(logger.stop(2000))
        assertNull(logger.stop(3000)) // double stop

        // Second session should work normally
        assertTrue(logger.start(createTempPath(), withGps = false, currentTimeMs = 10000))
        assertTrue(logger.isLogging)
        logger.writeSample(TelemetryState(speed = 3000), gps = null, currentTimeMs = 11000)
        val metadata = logger.stop(12000)
        assertNotNull(metadata)
        assertEquals(30.0, metadata.maxSpeedKmh, 0.01)
    }

    // ==================== Live Stats ====================

    @Test
    fun `liveStats returns null when not logging`() {
        val logger = RideLogger()
        assertNull(logger.liveStats(1000, 0))
    }

    @Test
    fun `liveStats returns correct elapsed time`() {
        val logger = RideLogger()
        logger.start(createTempPath(), withGps = false, currentTimeMs = 10_000)
        logger.writeSample(TelemetryState(speed = 2000), gps = null, currentTimeMs = 11_000)

        val stats = logger.liveStats(15_000, 0)
        assertNotNull(stats)
        assertEquals(10_000, stats.startTimeMs)
        assertEquals(5_000, stats.elapsedMs)
        logger.stop(16_000)
    }

    @Test
    fun `liveStats returns correct max speed and distance`() {
        val logger = RideLogger()
        logger.start(createTempPath(), withGps = false, currentTimeMs = 0)

        logger.writeSample(TelemetryState(speed = 2000, totalDistance = 50_000), gps = null, currentTimeMs = 1000)
        logger.writeSample(TelemetryState(speed = 4000, totalDistance = 51_000), gps = null, currentTimeMs = 2000)
        logger.writeSample(TelemetryState(speed = 3000, totalDistance = 52_000), gps = null, currentTimeMs = 3000)

        val stats = logger.liveStats(3500, 52_000)
        assertNotNull(stats)
        assertEquals(40.0, stats.maxSpeedKmh, 0.01)
        assertEquals(2000, stats.distanceMeters)
        logger.stop(4000)
    }

    @Test
    fun `liveStats returns null after stop`() {
        val logger = RideLogger()
        logger.start(createTempPath(), withGps = false, currentTimeMs = 0)
        logger.writeSample(TelemetryState(), gps = null, currentTimeMs = 1000)
        logger.stop(2000)
        assertNull(logger.liveStats(3000, 0))
    }

    // ==================== Helpers ====================

    private var tempCounter = 0

    private fun createTempPath(name: String? = null): String {
        val fileName = name ?: "ride_test_${tempCounter++}.csv"
        return "/tmp/wheellog_test/$fileName"
    }
}
