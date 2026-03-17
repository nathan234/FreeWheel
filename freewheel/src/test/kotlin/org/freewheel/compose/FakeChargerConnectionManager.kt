package org.freewheel.compose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.freewheel.core.charger.ChargerConnectionManagerPort
import org.freewheel.core.charger.ChargerState
import org.freewheel.core.service.ConnectionState

/**
 * Fake [ChargerConnectionManagerPort] for ViewModel tests.
 * Exposes mutable flows for controlling state and records method calls.
 */
class FakeChargerConnectionManager : ChargerConnectionManagerPort {
    private val _chargerState = MutableStateFlow(ChargerState())
    override val chargerState: StateFlow<ChargerState> = _chargerState.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    override fun connect(address: String, password: String) {}
    override fun disconnect() {}
    override fun setOutputVoltage(voltage: Float) {}
    override fun setOutputCurrent(current: Float) {}
    override fun toggleOutput(enable: Boolean) {}
    override fun setPowerLimit(watts: Int) {}
    override fun setTwoStageCharging(enabled: Boolean) {}
    override fun setEndOfChargeCurrent(current: Float) {}
    override fun setPowerOnOutput(enabled: Boolean) {}

    // Test helpers
    fun setConnectionState(state: ConnectionState) { _connectionState.value = state }
    fun setChargerState(state: ChargerState) { _chargerState.value = state }
}
