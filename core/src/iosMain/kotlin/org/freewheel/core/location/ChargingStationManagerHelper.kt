package org.freewheel.core.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.freewheel.core.service.FlowObservation

/**
 * Swift-friendly wrapper around [ChargingStationRepository]. Swift never touches
 * Kotlin coroutines directly — it calls `refreshNearby` (fire-and-forget) and
 * subscribes via `observeStations`.
 */
object ChargingStationManagerHelper {

    fun create(apiKey: String): ChargingStationRepository {
        return ChargingStationRepository(
            source = OpenChargeMapSource(OpenChargeMapClient(apiKey = apiKey)),
        )
    }

    /** Fire-and-forget refresh call from Swift. */
    fun refreshNearby(repository: ChargingStationRepository, latitude: Double, longitude: Double) {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            try {
                repository.refreshNearby(latitude, longitude)
            } finally {
                scope.cancel()
            }
        }
    }

    fun observeStations(
        repository: ChargingStationRepository,
        onChange: (List<ChargingStation>) -> Unit,
    ): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { repository.stations.collect { onChange(it) } }
        return FlowObservation(scope)
    }
}
