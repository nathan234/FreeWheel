package org.freewheel.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveRouteBufferTest {

    private fun point(
        timestampMs: Long,
        latitude: Double = 37.7749,
        longitude: Double = -122.4194,
        speedKmh: Double = 25.0
    ) = RoutePoint(
        timestampMs = timestampMs,
        latitude = latitude,
        longitude = longitude,
        altitude = 0.0,
        bearing = 0.0,
        speedKmh = speedKmh,
        gpsSpeedKmh = speedKmh
    )

    @Test
    fun `first point is always accepted`() {
        val buffer = LiveRouteBuffer()
        assertTrue(buffer.addPointIfNeeded(point(1000)))
        assertEquals(1, buffer.size)
    }

    @Test
    fun `point within minIntervalMs is rejected even if far away`() {
        val buffer = LiveRouteBuffer(minIntervalMs = 1000, minDistanceMeters = 1.0)
        buffer.addPointIfNeeded(point(1000, latitude = 37.7749))
        // 500ms later, ~2.7 km away — fails the time gate
        val rejected = buffer.addPointIfNeeded(point(1500, latitude = 37.8000))
        assertFalse(rejected)
        assertEquals(1, buffer.size)
    }

    @Test
    fun `point past minIntervalMs but within minDistanceMeters is rejected`() {
        val buffer = LiveRouteBuffer(minIntervalMs = 1000, minDistanceMeters = 5.0)
        buffer.addPointIfNeeded(point(1000, latitude = 37.7749, longitude = -122.4194))
        // ~0.011m drift, way under 5m
        val rejected = buffer.addPointIfNeeded(point(3000, latitude = 37.77490001, longitude = -122.4194))
        assertFalse(rejected)
        assertEquals(1, buffer.size)
    }

    @Test
    fun `point past both thresholds is accepted`() {
        val buffer = LiveRouteBuffer(minIntervalMs = 1000, minDistanceMeters = 1.0)
        buffer.addPointIfNeeded(point(1000, latitude = 37.7749, longitude = -122.4194))
        // 0.0001 deg lat ≈ 11.1m, well past 1m
        val accepted = buffer.addPointIfNeeded(point(3000, latitude = 37.7750, longitude = -122.4194))
        assertTrue(accepted)
        assertEquals(2, buffer.size)
    }

    @Test
    fun `totalDistanceMeters accumulates across segments`() {
        val buffer = LiveRouteBuffer(minIntervalMs = 100, minDistanceMeters = 0.0)
        buffer.addPointIfNeeded(point(1000, latitude = 37.7749))
        buffer.addPointIfNeeded(point(2000, latitude = 37.7750))
        buffer.addPointIfNeeded(point(3000, latitude = 37.7751))

        // Two segments of ~11.1m each ≈ 22.2m
        val total = buffer.totalDistanceMeters()
        assertTrue(total in 20.0..25.0, "expected ~22m, got $total")
    }

    @Test
    fun `maxPoints cap drops oldest and adjusts total distance`() {
        val buffer = LiveRouteBuffer(minIntervalMs = 100, minDistanceMeters = 0.0, maxPoints = 3)
        // 5 points, each ~11m apart
        for (i in 0 until 5) {
            buffer.addPointIfNeeded(point(1000L + i * 200L, latitude = 37.7749 + i * 0.0001))
        }
        assertEquals(3, buffer.size)
        // Remaining: indices 2, 3, 4 → two ~11m segments ≈ 22m
        val total = buffer.totalDistanceMeters()
        assertTrue(total in 20.0..25.0, "expected ~22m after drop, got $total")
    }

    @Test
    fun `speedRangeKmh returns null when empty`() {
        assertNull(LiveRouteBuffer().speedRangeKmh())
    }

    @Test
    fun `speedRangeKmh returns min and max`() {
        val buffer = LiveRouteBuffer(minIntervalMs = 100, minDistanceMeters = 0.0)
        buffer.addPointIfNeeded(point(1000, latitude = 37.7749, speedKmh = 10.0))
        buffer.addPointIfNeeded(point(2000, latitude = 37.7750, speedKmh = 30.0))
        buffer.addPointIfNeeded(point(3000, latitude = 37.7751, speedKmh = 20.0))
        val range = buffer.speedRangeKmh()
        assertNotNull(range)
        assertEquals(10.0, range.min, 0.001)
        assertEquals(30.0, range.max, 0.001)
    }

    @Test
    fun `speedRangeKmh with single point has equal min and max`() {
        val buffer = LiveRouteBuffer()
        buffer.addPointIfNeeded(point(1000, speedKmh = 17.5))
        val range = buffer.speedRangeKmh()
        assertNotNull(range)
        assertEquals(17.5, range.min, 0.001)
        assertEquals(17.5, range.max, 0.001)
    }

    @Test
    fun `clear resets all state`() {
        val buffer = LiveRouteBuffer(minIntervalMs = 100, minDistanceMeters = 0.0)
        buffer.addPointIfNeeded(point(1000, latitude = 37.7749))
        buffer.addPointIfNeeded(point(2000, latitude = 37.7750))
        buffer.clear()
        assertEquals(0, buffer.size)
        assertEquals(0.0, buffer.totalDistanceMeters(), 0.001)
        assertNull(buffer.speedRangeKmh())
        // After clear, time gate should reset
        assertTrue(buffer.addPointIfNeeded(point(0, latitude = 37.7749)))
    }

    @Test
    fun `snapshot returns independent immutable copy`() {
        val buffer = LiveRouteBuffer(minIntervalMs = 100, minDistanceMeters = 0.0)
        buffer.addPointIfNeeded(point(1000, latitude = 37.7749))
        val snap1 = buffer.snapshot()
        buffer.addPointIfNeeded(point(2000, latitude = 37.7750))
        val snap2 = buffer.snapshot()
        assertEquals(1, snap1.size)
        assertEquals(2, snap2.size)
    }
}
