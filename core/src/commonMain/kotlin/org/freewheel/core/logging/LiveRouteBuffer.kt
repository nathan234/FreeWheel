package org.freewheel.core.logging

import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.withLock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thread-safe in-memory buffer of GPS points for the live-ride map polyline.
 *
 * New points are accepted only when both [minIntervalMs] and [minDistanceMeters]
 * have been exceeded since the previous point — this filters out GPS jitter
 * when the wheel is stationary or moving very slowly. The first point is always
 * accepted. When [maxPoints] is exceeded, the oldest points are dropped and
 * [totalDistanceMeters] is decremented to reflect the remaining polyline.
 */
class LiveRouteBuffer(
    private val minIntervalMs: Long = 1_000,
    private val minDistanceMeters: Double = 1.0,
    private val maxPoints: Int = 10_000
) {
    private val lock = Lock()
    private val _points = ArrayDeque<RoutePoint>()
    private var _totalDistanceMeters: Double = 0.0

    val size: Int get() = lock.withLock { _points.size }

    fun snapshot(): List<RoutePoint> = lock.withLock { _points.toList() }

    fun totalDistanceMeters(): Double = lock.withLock { _totalDistanceMeters }

    fun speedRangeKmh(): SpeedRange? = lock.withLock {
        if (_points.isEmpty()) return@withLock null
        var min = _points.first().speedKmh
        var max = min
        for (p in _points) {
            if (p.speedKmh < min) min = p.speedKmh
            if (p.speedKmh > max) max = p.speedKmh
        }
        SpeedRange(min, max)
    }

    fun clear() = lock.withLock {
        _points.clear()
        _totalDistanceMeters = 0.0
    }

    /**
     * Append [point] if both throttles have elapsed since the previous point.
     * Returns true if the point was added.
     */
    fun addPointIfNeeded(point: RoutePoint): Boolean = lock.withLock {
        val last = _points.lastOrNull()
        val newSegment: Double
        if (last != null) {
            if (point.timestampMs - last.timestampMs < minIntervalMs) return@withLock false
            newSegment = haversineMeters(last.latitude, last.longitude, point.latitude, point.longitude)
            if (newSegment < minDistanceMeters) return@withLock false
        } else {
            newSegment = 0.0
        }
        _points.addLast(point)
        _totalDistanceMeters += newSegment

        while (_points.size > maxPoints) {
            val dropped = _points.removeFirst()
            val newHead = _points.firstOrNull() ?: break
            val brokenSegment = haversineMeters(
                dropped.latitude, dropped.longitude,
                newHead.latitude, newHead.longitude
            )
            _totalDistanceMeters -= brokenSegment
        }

        true
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat2 - lat1) * PI / 180.0
            val dLon = (lon2 - lon1) * PI / 180.0
            val sinLat = sin(dLat / 2)
            val sinLon = sin(dLon / 2)
            val a = sinLat * sinLat +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sinLon * sinLon
            return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

/** Min/max speed in km/h for the points currently in the buffer. */
data class SpeedRange(val min: Double, val max: Double)
