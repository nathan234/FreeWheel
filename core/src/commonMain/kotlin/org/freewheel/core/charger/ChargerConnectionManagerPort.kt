package org.freewheel.core.charger

import org.freewheel.core.service.ConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the subset of [ChargerConnectionManager] that the ViewModel depends on.
 * Enables testing without instantiating the real event-loop-based implementation.
 */
interface ChargerConnectionManagerPort {
    val chargerState: StateFlow<ChargerState>
    val connectionState: StateFlow<ConnectionState>

    fun connect(address: String, password: String)
    fun disconnect()
    fun setOutputVoltage(voltage: Float)
    fun setOutputCurrent(current: Float)
    fun toggleOutput(enable: Boolean)
    fun setPowerLimit(watts: Int)
    fun setTwoStageCharging(enabled: Boolean)
    fun setEndOfChargeCurrent(current: Float)
    fun setPowerOnOutput(enabled: Boolean)
}
