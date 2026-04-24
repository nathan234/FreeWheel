package org.freewheel.core.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.freewheel.core.utils.Lock
import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.withLock
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

interface ChargingStationSource {
    suspend fun fetchNearby(latitude: Double, longitude: Double): List<ChargingStation>
}

class OpenChargeMapSource(
    private val client: OpenChargeMapClient,
    private val radiusKm: Int = OpenChargeMapClient.DEFAULT_RADIUS_KM,
) : ChargingStationSource {
    override suspend fun fetchNearby(latitude: Double, longitude: Double): List<ChargingStation> =
        client.fetchNearby(latitude = latitude, longitude = longitude, radiusKm = radiusKm)
}

/**
 * Caches nearby charging stations and decides when to refetch as the user pans.
 *
 * The refetch threshold should be comfortably smaller than the source's query
 * radius so that panned-to-edge regions still have coverage without hitting
 * the API on every camera idle.
 */
class ChargingStationRepository(
    private val source: ChargingStationSource,
    private val refetchThresholdKm: Double = DEFAULT_REFETCH_THRESHOLD_KM,
) {
    private val lock = Lock()
    private val _stations = MutableStateFlow<List<ChargingStation>>(emptyList())
    val stations: StateFlow<List<ChargingStation>> = _stations.asStateFlow()

    private var lastFetchLat: Double? = null
    private var lastFetchLng: Double? = null

    suspend fun refreshNearby(latitude: Double, longitude: Double) {
        val shouldFetch = lock.withLock {
            val lastLat = lastFetchLat
            val lastLng = lastFetchLng
            lastLat == null || lastLng == null ||
                haversineKm(lastLat, lastLng, latitude, longitude) >= refetchThresholdKm
        }
        if (!shouldFetch) return

        val fetched = try {
            source.fetchNearby(latitude, longitude)
        } catch (t: Throwable) {
            Logger.w("ChargingStationRepository", "fetchNearby failed: ${t.message}")
            return
        }

        lock.withLock {
            lastFetchLat = latitude
            lastFetchLng = longitude
        }
        _stations.value = fetched
    }

    companion object {
        const val DEFAULT_REFETCH_THRESHOLD_KM: Double = 5.0
    }
}

private const val EARTH_RADIUS_KM = 6371.0

private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = (lat2 - lat1).degToRad()
    val dLng = (lng2 - lng1).degToRad()
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1.degToRad()) * cos(lat2.degToRad()) * sin(dLng / 2) * sin(dLng / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return EARTH_RADIUS_KM * c
}

private fun Double.degToRad(): Double = this * PI / 180.0
