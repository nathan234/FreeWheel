package com.cooper.wheellog.core.logging

import com.cooper.wheellog.core.domain.WheelState
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
        val state = WheelState(speed = 2500) // 25 km/h
        logger.writeSample(state, null, startMs + 1000)
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

        logger.writeSample(WheelState(speed = 2000), null, 1000)  // 20 km/h
        logger.writeSample(WheelState(speed = 3500), null, 2000)  // 35 km/h
        logger.writeSample(WheelState(speed = 2500), null, 3000)  // 25 km/h

        val metadata = logger.stop(4000)
        assertNotNull(metadata)
        assertEquals(35.0, metadata.maxSpeedKmh, 0.01)
    }

    @Test
    fun `stop returns correct average speed`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        logger.writeSample(WheelState(speed = 2000), null, 1000)  // 20 km/h
        logger.writeSample(WheelState(speed = 3000), null, 2000)  // 30 km/h
        logger.writeSample(WheelState(speed = 4000), null, 3000)  // 40 km/h

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

        logger.writeSample(WheelState(speed = 2000), null, 1000)
        logger.writeSample(WheelState(speed = 3000), null, 2000)
        logger.writeSample(WheelState(speed = 4000), null, 3000)

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
        logger.writeSample(WheelState(totalDistance = 50000), null, 1000)
        logger.writeSample(WheelState(totalDistance = 51000), null, 2000)

        val metadata = logger.stop(3000, lastTotalDistance = 51000)
        assertNotNull(metadata)
        assertEquals(1000, metadata.distanceMeters) // 51000 - 50000
    }

    @Test
    fun `stop returns max current as absolute value`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        logger.writeSample(WheelState(current = 500), null, 1000)    // 5A
        logger.writeSample(WheelState(current = -1500), null, 2000)  // -15A (regen)
        logger.writeSample(WheelState(current = 800), null, 3000)    // 8A

        val metadata = logger.stop(4000)
        assertNotNull(metadata)
        assertEquals(15.0, metadata.maxCurrentA, 0.01) // abs(-15)
    }

    @Test
    fun `stop returns max power as absolute value`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        logger.writeSample(WheelState(power = 100000), null, 1000)    // 1000W
        logger.writeSample(WheelState(power = -200000), null, 2000)   // -2000W
        logger.writeSample(WheelState(power = 50000), null, 3000)     // 500W

        val metadata = logger.stop(4000)
        assertNotNull(metadata)
        assertEquals(2000.0, metadata.maxPowerW, 0.01) // abs(-2000)
    }

    @Test
    fun `fileName is extracted from path`() {
        val logger = RideLogger()
        val tmpPath = createTempPath("ride_2024.csv")
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)
        logger.writeSample(WheelState(), null, 1000)

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
        logger.writeSample(WheelState(speed = 2000), null, 1000)
        // Sample at t=1500 (500ms later) should be throttled
        logger.writeSample(WheelState(speed = 3000), null, 1500)
        // Sample at t=1999 (999ms after first) should be throttled
        logger.writeSample(WheelState(speed = 4000), null, 1999)
        // Sample at t=2000 (1000ms after first) should pass
        logger.writeSample(WheelState(speed = 5000), null, 2000)

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

        logger.writeSample(WheelState(speed = 2000), null, 1000)  // 20 km/h - written
        logger.writeSample(WheelState(speed = 9900), null, 1100)  // 99 km/h - throttled
        logger.writeSample(WheelState(speed = 3000), null, 2000)  // 30 km/h - written

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
        logger.writeSample(WheelState(speed = 2000), null, 1000)
        // No crash, no state mutation — verified by calling stop returning null
        assertNull(logger.stop(2000))
    }

    // ==================== GPS Handling ====================

    @Test
    fun `writeSample ignores GPS when started without GPS`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 0)

        val gps = GpsLocation(37.0, -122.0, 20.0, 50.0, 90.0, 500.0)
        // This should not crash and should ignore GPS data
        logger.writeSample(WheelState(speed = 2000), gps, 1000)

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
        logger.writeSample(WheelState(power = 10000, totalDistance = 1000), null, 1000)
        logger.writeSample(WheelState(power = 10000, totalDistance = 2000), null, 2000)

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
        logger.writeSample(WheelState(power = 10000, totalDistance = 0), null, 1000)
        logger.writeSample(WheelState(power = 10000, totalDistance = 10000), null, 2000)

        val metadata = logger.stop(3601_000, lastTotalDistance = 10000)
        assertNotNull(metadata)
        // 100 Wh / 10 km = 10 Wh/km
        assertEquals(10.0, metadata.consumptionWhPerKm, 1.0)
    }

    @Test
    fun `zero duration yields zero consumption`() {
        val logger = RideLogger()
        val tmpPath = createTempPath()
        logger.start(tmpPath, withGps = false, currentTimeMs = 1000)
        logger.writeSample(WheelState(power = 10000), null, 1500)

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
        logger.writeSample(WheelState(power = 10000, totalDistance = 0), null, 1000)

        val metadata = logger.stop(5000, lastTotalDistance = 0)
        assertNotNull(metadata)
        assertEquals(0.0, metadata.consumptionWhPerKm, 0.01)
    }

    // ==================== Helpers ====================

    private var tempCounter = 0

    private fun createTempPath(name: String? = null): String {
        val fileName = name ?: "ride_test_${tempCounter++}.csv"
        return "/tmp/wheellog_test/$fileName"
    }
}
