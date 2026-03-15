package org.freewheel.core.charger

import org.freewheel.core.service.BleManager
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.service.FlowObservation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * iOS helper for ChargerConnectionManager.
 * Provides Swift-friendly accessors (no default params, no coroutines).
 * Follows the same pattern as WheelConnectionManagerHelper.
 */
object ChargerConnectionManagerHelper {

    fun create(bleManager: BleManager): ChargerConnectionManager {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        return ChargerConnectionManager(
            bleManager = bleManager,
            scope = scope
        )
    }

    // MARK: - Connection

    fun connect(manager: ChargerConnectionManager, address: String, password: String) {
        manager.connect(address, password)
    }

    fun disconnect(manager: ChargerConnectionManager) {
        manager.disconnect()
    }

    // MARK: - State Getters

    fun getChargerState(manager: ChargerConnectionManager): ChargerState {
        return manager.chargerState.value
    }

    fun getConnectionState(manager: ChargerConnectionManager): ConnectionState {
        return manager.connectionState.value
    }

    fun isConnected(manager: ChargerConnectionManager): Boolean {
        return manager.connectionState.value.isConnected
    }

    // MARK: - BLE Callbacks (called from Swift BLE delegate)

    fun onDataReceived(manager: ChargerConnectionManager, data: ByteArray) {
        manager.onDataReceived(data)
    }

    fun onServicesDiscovered(manager: ChargerConnectionManager) {
        manager.onServicesDiscovered()
    }

    fun onBleError(manager: ChargerConnectionManager) {
        manager.onBleError()
    }

    // MARK: - Commands

    fun setOutputVoltage(manager: ChargerConnectionManager, voltage: Float) {
        manager.setOutputVoltage(voltage)
    }

    fun setOutputCurrent(manager: ChargerConnectionManager, current: Float) {
        manager.setOutputCurrent(current)
    }

    fun toggleOutput(manager: ChargerConnectionManager, enable: Boolean) {
        manager.toggleOutput(enable)
    }

    fun setPowerLimit(manager: ChargerConnectionManager, watts: Int) {
        manager.setPowerLimit(watts)
    }

    fun setTwoStageCharging(manager: ChargerConnectionManager, enabled: Boolean) {
        manager.setTwoStageCharging(enabled)
    }

    fun setEndOfChargeCurrent(manager: ChargerConnectionManager, current: Float) {
        manager.setEndOfChargeCurrent(current)
    }

    fun setPowerOnOutput(manager: ChargerConnectionManager, enabled: Boolean) {
        manager.setPowerOnOutput(enabled)
    }

    // MARK: - Flow Observers

    fun observeChargerState(manager: ChargerConnectionManager, onChange: (ChargerState) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.chargerState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeConnectionState(manager: ChargerConnectionManager, onChange: (ConnectionState) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.connectionState.collect { onChange(it) } }
        return FlowObservation(scope)
    }
}
