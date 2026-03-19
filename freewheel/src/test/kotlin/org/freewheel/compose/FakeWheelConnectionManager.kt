package org.freewheel.compose

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.WheelCommand
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.service.WheelConnectionManagerPort

/**
 * Fake [WheelConnectionManagerPort] for ViewModel tests.
 * Exposes mutable flows for controlling state and records method calls.
 */
class FakeWheelConnectionManager : WheelConnectionManagerPort {
    private val _wheelState = MutableStateFlow(WheelState())
    override val wheelState: StateFlow<WheelState> = _wheelState.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _capabilities = MutableStateFlow(CapabilitySet())
    override val capabilities: StateFlow<CapabilitySet> = _capabilities.asStateFlow()

    private val _telemetryState = MutableStateFlow(TelemetryState())
    override val telemetryState: StateFlow<TelemetryState> = _telemetryState.asStateFlow()

    private val _identityState = MutableStateFlow(WheelIdentity())
    override val identityState: StateFlow<WheelIdentity> = _identityState.asStateFlow()

    private val _bmsState = MutableStateFlow(BmsState())
    override val bmsState: StateFlow<BmsState> = _bmsState.asStateFlow()

    private val _settingsState = MutableStateFlow<WheelSettings>(WheelSettings.None)
    override val settingsState: StateFlow<WheelSettings> = _settingsState.asStateFlow()

    override var captureCallback: ((data: ByteArray, direction: BlePacketDirection, annotation: String) -> Unit)? = null
    override var unhandledCallback: ((reason: String, frameData: ByteArray) -> Unit)? = null

    var connectCallCount = 0
        private set
    var lastConnectAddress: String? = null
        private set
    var lastConnectWheelType: WheelType? = null
        private set
    var disconnectCallCount = 0
        private set

    private var config = DecoderConfig()

    override fun connect(address: String, wheelType: WheelType?) {
        connectCallCount++
        lastConnectAddress = address
        lastConnectWheelType = wheelType
    }

    override fun disconnect() {
        disconnectCallCount++
    }

    override fun updateConfig(config: DecoderConfig) {
        this.config = config
    }

    override fun getConfig(): DecoderConfig = config

    override fun getConnectionInfo(): WheelConnectionInfo? = null

    override fun sendCommand(command: WheelCommand) {}
    override fun wheelBeep() {}
    override fun toggleLight(enabled: Boolean) {}
    override fun setPedalsMode(mode: Int) {}
    override fun executeCommand(commandId: SettingsCommandId, intValue: Int, boolValue: Boolean) {}

    // Test helpers
    fun setConnectionState(state: ConnectionState) { _connectionState.value = state }
    fun setWheelState(state: WheelState) { _wheelState.value = state }
    fun setCapabilities(caps: CapabilitySet) { _capabilities.value = caps }
}
