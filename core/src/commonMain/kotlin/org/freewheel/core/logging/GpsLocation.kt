package org.freewheel.core.logging

/**
 * GPS location data for CSV logging.
 *
 * @property latitude Latitude in degrees.
 * @property longitude Longitude in degrees.
 * @property speedKmh Speed in km/h (converted from m/s by caller).
 * @property altitude Altitude in meters.
 * @property bearing Bearing/heading in degrees.
 * @property cumulativeDistance Cumulative trip distance in meters.
 */
data class GpsLocation(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val altitude: Double,
    val bearing: Double,
    val cumulativeDistance: Double
)
