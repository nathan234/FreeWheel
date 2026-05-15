package org.freewheel.core.service

import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.domain.telemetry.BmsState
import org.freewheel.core.domain.identity.CapabilitySet
import org.freewheel.core.domain.EventLogEntry
import org.freewheel.core.domain.identity.ProtocolFamily
import org.freewheel.core.domain.settings.SettingsCommandId
import org.freewheel.core.domain.telemetry.TelemetryState
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.settings.WheelSettings
import org.freewheel.core.domain.identity.WheelType
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.logging.ConnectionErrorEvent
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
    var errorLogCallback: ((ConnectionErrorEvent) -> Unit)?

    /**
     * Connect to [address] with an optional [ConnectionHint].
     * Implementations are fire-and-forget; observe [connectionState] for the result.
     */
    fun connect(address: String, hint: ConnectionHint? = null)

    /**
     * Migration shim: callers that still have a [WheelType] (legacy or explicit
     * picker) get wrapped as [HintSource.EXPLICIT_API]. Sentinel values
     * ([WheelType.GOTWAY_VIRTUAL], [WheelType.Unknown]) become null because
     * [ProtocolFamily] cannot represent them — see [ProtocolFamily.fromWheelType].
     */
    fun connect(address: String, wheelType: WheelType?) {
        val hint = wheelType
            ?.let { ProtocolFamily.fromWheelType(it) }
            ?.let { ConnectionHint(it, HintSource.EXPLICIT_API) }
        connect(address, hint)
    }

    fun disconnect()
    fun updateConfig(config: DecoderConfig)
    fun getConfig(): DecoderConfig
    fun getConnectionInfo(): WheelConnectionInfo?

    /**
     * Confirm a wheel type from the picker (Pass 4). Only meaningful while
     * [connectionState] is [ConnectionState.WheelTypeRequired]; calls in any
     * other state are no-ops.
     */
    fun confirmWheelType(wheelType: WheelType) {}

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

    /**
     * Full GATT topology from the most recent ServicesDiscovered event,
     * or null when no services have been discovered yet on the current
     * connection attempt. Cleared on disconnect. Surfaced for the
     * unrecognized-wheel report (Pass 3b).
     */
    val discoveredServices: StateFlow<DiscoveredServices?>
        get() = kotlinx.coroutines.flow.MutableStateFlow(null)
}
