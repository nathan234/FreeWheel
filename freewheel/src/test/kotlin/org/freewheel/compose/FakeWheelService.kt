package org.freewheel.compose

import android.location.Location
import org.freewheel.compose.service.WheelServiceContract
import org.freewheel.core.charger.ChargerConnectionManagerPort
import org.freewheel.core.service.BleManagerPort

/**
 * Fake [WheelServiceContract] for ViewModel tests.
 * Accepts real [ChargerConnectionManager] and [BleManagerPort] instances
 * so the ViewModel can wire up charger state collection without mocking.
 */
class FakeWheelService(
    override val chargerConnectionManager: ChargerConnectionManagerPort,
    override val chargerBleManager: BleManagerPort
) : WheelServiceContract {

    override var onLightToggleRequested: (() -> Unit)? = null
    override var onLogToggleRequested: (() -> Unit)? = null
    override var onGpsLocationUpdate: ((Location) -> Unit)? = null

    var startLocationTrackingCallCount = 0
        private set
    var stopLocationTrackingCallCount = 0
        private set
    var shutdownCallCount = 0
        private set

    override fun startLocationTracking() {
        startLocationTrackingCallCount++
    }

    override fun stopLocationTracking() {
        stopLocationTrackingCallCount++
    }

    override fun shutdown() {
        shutdownCallCount++
    }
}
