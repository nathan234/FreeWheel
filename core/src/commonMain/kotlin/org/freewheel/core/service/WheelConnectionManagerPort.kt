package org.freewheel.core.service

import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.protocol.DecoderConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the subset of [WheelConnectionManager] that the ViewModel depends on.
 * Enables testing without instantiating the real event-loop-based implementation.
 */
interface WheelConnectionManagerPort {
    val wheelState: StateFlow<WheelState>
    val connectionState: StateFlow<ConnectionState>
    val capabilities: StateFlow<CapabilitySet>

    var captureCallback: ((data: ByteArray, direction: BlePacketDirection) -> Unit)?
    var unhandledCallback: ((reason: String, frameData: ByteArray) -> Unit)?

    fun connect(address: String, wheelType: WheelType? = null)
    fun disconnect()
    fun updateConfig(config: DecoderConfig)
    fun getConfig(): DecoderConfig
    fun getConnectionInfo(): WheelConnectionInfo?

    fun sendCommand(command: org.freewheel.core.protocol.WheelCommand)
    fun wheelBeep()
    fun toggleLight(enabled: Boolean)
    fun setPedalsMode(mode: Int)
    fun executeCommand(commandId: SettingsCommandId, intValue: Int = 0, boolValue: Boolean = false)
}
