package com.cooper.wheellog.core.service

import com.cooper.wheellog.core.ble.DiscoveredServices
import com.cooper.wheellog.core.ble.WheelConnectionInfo
import com.cooper.wheellog.core.ble.WheelTypeDetector
import com.cooper.wheellog.core.domain.BmsState
import com.cooper.wheellog.core.domain.TelemetryState
import com.cooper.wheellog.core.domain.WheelIdentity
import com.cooper.wheellog.core.domain.WheelSettingsState
import com.cooper.wheellog.core.domain.WheelState
import com.cooper.wheellog.core.domain.WheelType
import com.cooper.wheellog.core.protocol.DecoderConfig
import com.cooper.wheellog.core.protocol.WheelCommand
import com.cooper.wheellog.core.protocol.WheelDecoder
import com.cooper.wheellog.core.protocol.WheelDecoderFactory
import com.cooper.wheellog.core.domain.SettingsCommandId
import com.cooper.wheellog.core.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central manager for wheel connections.
 * Coordinates BLE communication, protocol decoding, and state management.
 *
 * This is the main entry point for interacting with a wheel from the UI layer.
 *
 * ## Usage
 * ```
 * val manager = WheelConnectionManager(bleManager, decoderFactory, scope)
 *
 * // Observe state
 * manager.wheelState.collect { state -> updateUI(state) }
 * manager.connectionState.collect { state -> updateConnectionUI(state) }
 *
 * // Connect
 * manager.connect(address, WheelType.KINGSONG)
 *
 * // Disconnect
 * manager.disconnect()
 * ```
 */
class WheelConnectionManager(
    private val bleManager: BleManagerPort,
    private val decoderFactory: WheelDecoderFactory,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val _wheelState = MutableStateFlow(WheelState())
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    // Granular sub-state flows — emitted selectively when the relevant sub-state changes.
    // UI components can observe these to avoid recomposition on irrelevant field changes.
    private val _telemetryState = MutableStateFlow(TelemetryState())
    private val _settingsState = MutableStateFlow(WheelSettingsState())
    private val _identityState = MutableStateFlow(WheelIdentity())
    private val _bmsState = MutableStateFlow(BmsState())

    private var currentDecoder: WheelDecoder? = null
    private var decoderConfig = DecoderConfig()
    private var connectionInfo: WheelConnectionInfo? = null

    private val wheelTypeDetector = WheelTypeDetector()
    private val keepAliveTimer = KeepAliveTimer(scope, dispatcher)
    private val dataTimeoutTracker = DataTimeoutTracker(scope, dispatcher)
    private val commandScheduler = CommandScheduler(scope, dispatcher)

    // Track last data time for timeout detection
    private var lastDataReceivedTime: Long = 0

    /**
     * Current wheel state as an observable flow.
     */
    val wheelState: StateFlow<WheelState> = _wheelState.asStateFlow()

    /**
     * Current connection state as an observable flow.
     */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Telemetry sub-state (speed, voltage, current, etc.). Updated on every BLE notification.
     */
    val telemetryState: StateFlow<TelemetryState> = _telemetryState.asStateFlow()

    /**
     * Settings sub-state (pedals mode, light mode, etc.). Updated rarely.
     */
    val settingsState: StateFlow<WheelSettingsState> = _settingsState.asStateFlow()

    /**
     * Identity sub-state (wheel type, model, serial, etc.). Set once per connection.
     */
    val identityState: StateFlow<WheelIdentity> = _identityState.asStateFlow()

    /**
     * BMS sub-state (battery pack snapshots). Updated periodically.
     */
    val bmsState: StateFlow<BmsState> = _bmsState.asStateFlow()

    /**
     * Whether the keep-alive timer is running.
     */
    val isKeepAliveRunning: StateFlow<Boolean> = keepAliveTimer.isRunning

    /**
     * Update decoder configuration.
     * Call this when user settings change.
     */
    fun updateConfig(config: DecoderConfig) {
        decoderConfig = config
    }

    /**
     * Get the current decoder configuration.
     */
    fun getConfig(): DecoderConfig = decoderConfig

    /**
     * Connect to a wheel at the given address.
     *
     * @param address BLE MAC address (Android) or peripheral identifier (iOS)
     * @param wheelType Optional wheel type hint; if null, will be auto-detected
     */
    suspend fun connect(address: String, wheelType: WheelType? = null) {
        _connectionState.value = ConnectionState.Connecting(address)

        try {
            val success = bleManager.connect(address)

            if (success) {
                _connectionState.value = ConnectionState.DiscoveringServices(address)

                // If wheel type is known, set up decoder immediately
                wheelType?.let { type ->
                    setupDecoder(type)
                }

                // Start data timeout monitoring
                startDataTimeoutMonitor(address)

            } else {
                _connectionState.value = ConnectionState.Failed(
                    error = "Connection failed",
                    address = address
                )
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Failed(error = e.message ?: "Connection failed", address = address)
        }
    }

    /**
     * Disconnect from the current wheel.
     */
    suspend fun disconnect() {
        stopTimers()
        commandScheduler.cancelAll()

        currentDecoder?.reset()
        currentDecoder = null
        connectionInfo = null

        bleManager.disconnect()
        _wheelState.value = WheelState()
        _telemetryState.value = TelemetryState()
        _settingsState.value = WheelSettingsState()
        _identityState.value = WheelIdentity()
        _bmsState.value = BmsState()
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Send a command to the wheel.
     */
    suspend fun sendCommand(command: WheelCommand) {
        when (command) {
            is WheelCommand.SendBytes -> {
                bleManager.write(command.data)
            }
            is WheelCommand.SendDelayed -> {
                commandScheduler.schedule(command.delayMs) {
                    bleManager.write(command.data)
                }
            }
            else -> {
                val rawCommands = currentDecoder?.buildCommand(command) ?: return
                commandScheduler.scheduleSequence {
                    for (cmd in rawCommands) {
                        when (cmd) {
                            is WheelCommand.SendBytes -> bleManager.write(cmd.data)
                            is WheelCommand.SendDelayed -> {
                                delay(cmd.delayMs)
                                bleManager.write(cmd.data)
                            }
                            else -> {} // prevent recursion
                        }
                    }
                }
            }
        }
    }

    /**
     * Process incoming data from the wheel.
     * Called by platform-specific BLE implementation when data is received.
     */
    fun onDataReceived(data: ByteArray) {
        lastDataReceivedTime = currentTimeMillis()
        dataTimeoutTracker.onDataReceived()

        val decoder = currentDecoder
        if (decoder == null) {
            Logger.w("WheelConnectionManager", "Data received (${data.size} bytes) but no decoder set")
            return
        }

        val oldState = _wheelState.value
        val result = decoder.decode(data, oldState, decoderConfig)
        if (result == null) {
            Logger.d("WheelConnectionManager", "decode() returned null for ${data.size} bytes (decoder=${decoder.wheelType})")
            return
        }

        val newState = result.newState
        _wheelState.value = newState

        // Emit granular sub-state flows only when the relevant sub-state changes
        emitGranularStates(oldState, newState)

        // Send any response commands
        if (result.commands.isNotEmpty()) {
            scope.launch {
                result.commands.forEach { cmd ->
                    sendCommand(cmd)
                }
            }
        }

        // Update connection state if decoder is ready
        if (decoder.isReady() && _connectionState.value !is ConnectionState.Connected) {
            val address = getCurrentAddress() ?: ""
            Logger.d("WheelConnectionManager", "Decoder ready, transitioning to Connected")
            _connectionState.value = ConnectionState.Connected(
                address = address,
                wheelName = result.newState.displayName
            )
        } else if (!decoder.isReady()) {
            Logger.d("WheelConnectionManager", "Decoded OK but isReady()=false (decoder=${decoder.wheelType})")
        }
    }

    /**
     * Handle BLE service discovery.
     * Called by platform-specific code after services are discovered.
     *
     * @param services The discovered BLE services
     * @param deviceName The device name for detection heuristics
     */
    fun onServicesDiscovered(services: DiscoveredServices, deviceName: String?) {
        Logger.d("WheelConnectionManager", "onServicesDiscovered: deviceName=$deviceName, services=${services.serviceUuids()}")
        if (!deviceName.isNullOrBlank()) {
            val oldState = _wheelState.value
            _wheelState.value = oldState.copy(btName = deviceName)
            emitGranularStates(oldState, _wheelState.value)
        }

        val result = wheelTypeDetector.detect(services, deviceName)
        Logger.d("WheelConnectionManager", "Detection result: $result")

        when (result) {
            is WheelTypeDetector.DetectionResult.Detected -> {
                Logger.d("WheelConnectionManager", "Detected: ${result.wheelType}, read=${result.readServiceUuid}/${result.readCharacteristicUuid}")
                connectionInfo = WheelConnectionInfo(
                    wheelType = result.wheelType,
                    readServiceUuid = result.readServiceUuid,
                    readCharacteristicUuid = result.readCharacteristicUuid,
                    writeServiceUuid = result.writeServiceUuid,
                    writeCharacteristicUuid = result.writeCharacteristicUuid
                )
                setupDecoder(result.wheelType)
            }
            is WheelTypeDetector.DetectionResult.Ambiguous -> {
                Logger.d("WheelConnectionManager", "Ambiguous: ${result.possibleTypes}, using GOTWAY_VIRTUAL")
                connectionInfo = wheelTypeDetector.getUuidsForType(WheelType.GOTWAY_VIRTUAL)
                setupDecoder(WheelType.GOTWAY_VIRTUAL)
            }
            is WheelTypeDetector.DetectionResult.Unknown -> {
                Logger.w("WheelConnectionManager", "Unknown wheel: ${result.reason}")
                _connectionState.value = ConnectionState.Failed(
                    error = "Unknown wheel type: ${result.reason}",
                    address = getCurrentAddress()
                )
            }
        }
    }

    /**
     * Handle wheel type detection.
     * Called when wheel type is determined (either from services or auto-detect).
     */
    fun onWheelTypeDetected(wheelType: WheelType) {
        setupDecoder(wheelType)
        // setupDecoder already sets wheelType and emits granular states

        // Update connection info if we don't have it
        if (connectionInfo == null) {
            connectionInfo = wheelTypeDetector.getUuidsForType(wheelType)
        }
    }

    /**
     * Get the connection info for the current wheel.
     * Returns null if not connected or wheel type unknown.
     */
    fun getConnectionInfo(): WheelConnectionInfo? = connectionInfo

    /**
     * Get the current decoder.
     * Exposed for testing and advanced use cases.
     */
    fun getCurrentDecoder(): WheelDecoder? = currentDecoder

    /**
     * Play beep on the connected wheel.
     */
    suspend fun wheelBeep() {
        sendCommand(WheelCommand.Beep)
    }

    /**
     * Toggle light on the connected wheel.
     */
    suspend fun toggleLight(enabled: Boolean) {
        sendCommand(WheelCommand.SetLight(enabled))
    }

    /**
     * Set pedals mode on the connected wheel.
     * @param mode 0=Hard, 1=Medium, 2=Soft
     */
    suspend fun setPedalsMode(mode: Int) {
        sendCommand(WheelCommand.SetPedalsMode(mode))
    }

    suspend fun setLightMode(mode: Int) { sendCommand(WheelCommand.SetLightMode(mode)) }
    suspend fun setLed(enabled: Boolean) { sendCommand(WheelCommand.SetLed(enabled)) }
    suspend fun setLedMode(mode: Int) { sendCommand(WheelCommand.SetLedMode(mode)) }
    suspend fun setStrobeMode(mode: Int) { sendCommand(WheelCommand.SetStrobeMode(mode)) }
    suspend fun setAlarmMode(mode: Int) { sendCommand(WheelCommand.SetAlarmMode(mode)) }
    suspend fun calibrate() { sendCommand(WheelCommand.Calibrate) }
    suspend fun powerOff() { sendCommand(WheelCommand.PowerOff) }
    suspend fun setLock(locked: Boolean) { sendCommand(WheelCommand.SetLock(locked)) }
    suspend fun resetTrip() { sendCommand(WheelCommand.ResetTrip) }
    suspend fun setMaxSpeed(speed: Int) { sendCommand(WheelCommand.SetMaxSpeed(speed)) }
    suspend fun setAlarmSpeed(speed: Int, num: Int) { sendCommand(WheelCommand.SetAlarmSpeed(speed, num)) }
    suspend fun setAlarmEnabled(enabled: Boolean, num: Int) { sendCommand(WheelCommand.SetAlarmEnabled(enabled, num)) }
    suspend fun setLimitedMode(enabled: Boolean) { sendCommand(WheelCommand.SetLimitedMode(enabled)) }
    suspend fun setLimitedSpeed(speed: Int) { sendCommand(WheelCommand.SetLimitedSpeed(speed)) }
    suspend fun setTailLight(enabled: Boolean) { sendCommand(WheelCommand.SetTailLight(enabled)) }
    suspend fun setDrl(enabled: Boolean) { sendCommand(WheelCommand.SetDrl(enabled)) }
    suspend fun setLedColor(value: Int, ledNum: Int) { sendCommand(WheelCommand.SetLedColor(value, ledNum)) }
    suspend fun setLightBrightness(value: Int) { sendCommand(WheelCommand.SetLightBrightness(value)) }
    suspend fun setHandleButton(enabled: Boolean) { sendCommand(WheelCommand.SetHandleButton(enabled)) }
    suspend fun setBrakeAssist(enabled: Boolean) { sendCommand(WheelCommand.SetBrakeAssist(enabled)) }
    suspend fun setTransportMode(enabled: Boolean) { sendCommand(WheelCommand.SetTransportMode(enabled)) }
    suspend fun setRideMode(enabled: Boolean) { sendCommand(WheelCommand.SetRideMode(enabled)) }
    suspend fun setGoHomeMode(enabled: Boolean) { sendCommand(WheelCommand.SetGoHomeMode(enabled)) }
    suspend fun setFancierMode(enabled: Boolean) { sendCommand(WheelCommand.SetFancierMode(enabled)) }
    suspend fun setRollAngleMode(mode: Int) { sendCommand(WheelCommand.SetRollAngleMode(mode)) }
    suspend fun setMute(enabled: Boolean) { sendCommand(WheelCommand.SetMute(enabled)) }
    suspend fun setSpeakerVolume(volume: Int) { sendCommand(WheelCommand.SetSpeakerVolume(volume)) }
    suspend fun setBeeperVolume(volume: Int) { sendCommand(WheelCommand.SetBeeperVolume(volume)) }
    suspend fun setFanQuiet(enabled: Boolean) { sendCommand(WheelCommand.SetFanQuiet(enabled)) }
    suspend fun setFan(enabled: Boolean) { sendCommand(WheelCommand.SetFan(enabled)) }
    suspend fun setPedalTilt(angle: Int) { sendCommand(WheelCommand.SetPedalTilt(angle)) }
    suspend fun setPedalSensitivity(sensitivity: Int) { sendCommand(WheelCommand.SetPedalSensitivity(sensitivity)) }
    suspend fun setMilesMode(enabled: Boolean) { sendCommand(WheelCommand.SetMilesMode(enabled)) }
    suspend fun setCutoutAngle(angle: Int) { sendCommand(WheelCommand.SetCutoutAngle(angle)) }
    suspend fun requestBmsData(bmsNum: Int, dataType: Int) { sendCommand(WheelCommand.RequestBmsData(bmsNum, dataType)) }
    suspend fun setKingsongAlarms(a1: Int, a2: Int, a3: Int, max: Int) { sendCommand(WheelCommand.SetKingsongAlarms(a1, a2, a3, max)) }
    suspend fun requestAlarmSettings() { sendCommand(WheelCommand.RequestAlarmSettings) }

    /**
     * Execute a settings command by ID.
     * Used by the shared WheelSettingsConfig to dispatch UI actions.
     */
    suspend fun executeCommand(commandId: SettingsCommandId, intValue: Int = 0, boolValue: Boolean = false) {
        when (commandId) {
            SettingsCommandId.LIGHT_MODE -> setLightMode(intValue)
            SettingsCommandId.LED -> setLed(boolValue)
            SettingsCommandId.LED_MODE -> setLedMode(intValue)
            SettingsCommandId.STROBE_MODE -> setStrobeMode(intValue)
            SettingsCommandId.TAIL_LIGHT -> setTailLight(boolValue)
            SettingsCommandId.DRL -> setDrl(boolValue)
            SettingsCommandId.LIGHT_BRIGHTNESS -> setLightBrightness(intValue)
            SettingsCommandId.PEDALS_MODE -> setPedalsMode(intValue)
            SettingsCommandId.ROLL_ANGLE_MODE -> setRollAngleMode(intValue)
            SettingsCommandId.CUTOUT_ANGLE -> setCutoutAngle(intValue)
            SettingsCommandId.HANDLE_BUTTON -> setHandleButton(boolValue)
            SettingsCommandId.BRAKE_ASSIST -> setBrakeAssist(boolValue)
            SettingsCommandId.RIDE_MODE -> setRideMode(boolValue)
            SettingsCommandId.GO_HOME_MODE -> setGoHomeMode(boolValue)
            SettingsCommandId.FANCIER_MODE -> setFancierMode(boolValue)
            SettingsCommandId.TRANSPORT_MODE -> setTransportMode(boolValue)
            SettingsCommandId.PEDAL_TILT -> setPedalTilt(intValue)
            SettingsCommandId.PEDAL_SENSITIVITY -> setPedalSensitivity(intValue)
            SettingsCommandId.MAX_SPEED -> setMaxSpeed(intValue)
            SettingsCommandId.LIMITED_MODE -> setLimitedMode(boolValue)
            SettingsCommandId.LIMITED_SPEED -> setLimitedSpeed(intValue)
            SettingsCommandId.ALARM_ENABLED_1 -> setAlarmEnabled(boolValue, 1)
            SettingsCommandId.ALARM_ENABLED_2 -> setAlarmEnabled(boolValue, 2)
            SettingsCommandId.ALARM_ENABLED_3 -> setAlarmEnabled(boolValue, 3)
            SettingsCommandId.ALARM_SPEED_1 -> setAlarmSpeed(intValue, 1)
            SettingsCommandId.ALARM_SPEED_2 -> setAlarmSpeed(intValue, 2)
            SettingsCommandId.ALARM_SPEED_3 -> setAlarmSpeed(intValue, 3)
            SettingsCommandId.SPEAKER_VOLUME -> setSpeakerVolume(intValue)
            SettingsCommandId.BEEPER_VOLUME -> setBeeperVolume(intValue)
            SettingsCommandId.MUTE -> setMute(boolValue)
            SettingsCommandId.FAN -> setFan(boolValue)
            SettingsCommandId.FAN_QUIET -> setFanQuiet(boolValue)
            SettingsCommandId.CALIBRATE -> calibrate()
            SettingsCommandId.POWER_OFF -> powerOff()
            SettingsCommandId.LOCK -> setLock(boolValue)
            SettingsCommandId.RESET_TRIP -> resetTrip()
        }
    }

    // ==================== Private Methods ====================

    private fun setupDecoder(wheelType: WheelType) {
        currentDecoder?.reset()
        currentDecoder = decoderFactory.createDecoder(wheelType)
        val oldState = _wheelState.value
        _wheelState.value = oldState.copy(wheelType = wheelType)
        emitGranularStates(oldState, _wheelState.value)

        // Send init commands
        currentDecoder?.getInitCommands()?.let { commands ->
            scope.launch {
                commands.forEach { cmd ->
                    sendCommand(cmd)
                }
            }
        }

        // Start keep-alive immediately so polling decoders (InMotion V2) can
        // request live data without waiting for isReady(). Safe for non-polling
        // decoders because startKeepAliveTimer() exits early when intervalMs <= 0.
        startKeepAliveTimer()
    }

    private fun startKeepAliveTimer() {
        val decoder = currentDecoder ?: return
        val intervalMs = decoder.keepAliveIntervalMs

        if (intervalMs <= 0) {
            // This wheel doesn't need keep-alive (e.g., Gotway, Kingsong)
            return
        }

        keepAliveTimer.start(
            intervalMs = intervalMs,
            initialDelayMs = intervalMs // Wait one interval before first tick
        ) {
            decoder.getKeepAliveCommand()?.let { command ->
                sendCommand(command)
            }
        }
    }

    private fun startDataTimeoutMonitor(address: String) {
        dataTimeoutTracker.start(
            timeoutMs = DataTimeoutTracker.DEFAULT_TIMEOUT_MS
        ) {
            // Data timeout occurred - connection may be lost
            _connectionState.value = ConnectionState.ConnectionLost(
                address = address,
                reason = "No data received for ${DataTimeoutTracker.DEFAULT_TIMEOUT_MS / 1000} seconds"
            )
            stopTimers()
        }
    }

    private fun stopTimers() {
        keepAliveTimer.stop()
        dataTimeoutTracker.stop()
    }

    private fun getCurrentAddress(): String? {
        return when (val state = _connectionState.value) {
            is ConnectionState.Connecting -> state.address
            is ConnectionState.DiscoveringServices -> state.address
            is ConnectionState.Connected -> state.address
            is ConnectionState.ConnectionLost -> state.address
            else -> null
        }
    }

    /**
     * Compare old and new WheelState, emitting only the sub-state flows that changed.
     * This avoids triggering observers for fields that didn't change.
     */
    private fun emitGranularStates(oldState: WheelState, newState: WheelState) {
        val newTelemetry = newState.toTelemetryState()
        if (newTelemetry != _telemetryState.value) {
            _telemetryState.value = newTelemetry
        }

        val newSettings = newState.toSettingsState()
        if (newSettings != _settingsState.value) {
            _settingsState.value = newSettings
        }

        val newIdentity = newState.toIdentity()
        if (newIdentity != _identityState.value) {
            _identityState.value = newIdentity
        }

        val newBms = newState.toBmsState()
        if (newBms != _bmsState.value) {
            _bmsState.value = newBms
        }
    }
}

/**
 * Platform-specific BLE manager.
 * Implements [BleManagerPort] via expect/actual for each platform.
 */
expect class BleManager : BleManagerPort {
    override val connectionState: StateFlow<ConnectionState>

    override suspend fun connect(address: String): Boolean
    override suspend fun disconnect()
    override suspend fun write(data: ByteArray): Boolean
    override suspend fun startScan(onDeviceFound: (BleDevice) -> Unit)
    override suspend fun stopScan()
}

/**
 * Represents a BLE connection to a wheel.
 */
expect class BleConnection

/**
 * Represents a discovered BLE device.
 */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int
)
