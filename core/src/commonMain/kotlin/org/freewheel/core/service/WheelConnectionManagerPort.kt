package org.freewheel.core.service

import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.EventLogEntry
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.protocol.DecoderConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the subset of [WheelConnectionManager] that the ViewModel depends on.
 * Enables testing without instantiating the real event-loop-based implementation.
 */
interface WheelConnectionManagerPort {
    val connectionState: StateFlow<ConnectionState>
    val capabilities: StateFlow<CapabilitySet>
    val telemetryState: StateFlow<TelemetryState?>
    val identityState: StateFlow<WheelIdentity>
    val bmsState: StateFlow<BmsState>
    val settingsState: StateFlow<WheelSettings>

    var captureCallback: ((data: ByteArray, direction: BlePacketDirection, annotation: String) -> Unit)?
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

    /** Accumulated event log entries (Veteran/Leaperkim). Empty for other wheel types. */
    val eventLogEntries: StateFlow<List<EventLogEntry>>
        get() = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    fun requestEventLog() {}
    fun clearEventLog() {}
}
