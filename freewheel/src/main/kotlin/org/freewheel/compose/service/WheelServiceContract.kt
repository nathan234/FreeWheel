package org.freewheel.compose.service

import android.location.Location
import org.freewheel.core.charger.ChargerConnectionManagerPort
import org.freewheel.core.service.BleManagerPort

/**
 * Interface for the subset of [WheelService] that [WheelViewModel][org.freewheel.compose.WheelViewModel] depends on.
 * Enables testing without mocking the Android Service.
 */
interface WheelServiceContract {
    val chargerConnectionManager: ChargerConnectionManagerPort
    val chargerBleManager: BleManagerPort

    var onLightToggleRequested: (() -> Unit)?
    var onLogToggleRequested: (() -> Unit)?
    var onGpsLocationUpdate: ((Location) -> Unit)?

    fun startLocationTracking()
    fun stopLocationTracking()
    fun shutdown()
}
