package org.freewheel.core.service

import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettingsState
import org.freewheel.core.domain.WheelState
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.WheelCommand
import org.freewheel.core.protocol.WheelDecoder
import org.freewheel.core.protocol.WheelDecoderFactory
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central manager for wheel connections.
 * Coordinates BLE communication, protocol decoding, and state management.
 *
 * All state mutations are serialized through a single event loop coroutine
 * that consumes [WheelEvent]s from a Channel. This eliminates race conditions
 * between concurrent callers (BLE callbacks, UI actions, timers).
 *
 * ## Usage
 * ```
 * val manager = WheelConnectionManager(bleManager, decoderFactory, scope)
 *
 * // Observe state
 * manager.wheelState.collect { state -> updateUI(state) }
 * manager.connectionState.collect { state -> updateConnectionUI(state) }
 *
 * // Connect (fire-and-forget — observe connectionState for result)
 * manager.connect(address, WheelType.KINGSONG)
 *
 * // Disconnect (fire-and-forget — observe connectionState for result)
 * manager.disconnect()
 * ```
 */
class WheelConnectionManager(
    private val bleManager: BleManagerPort,
    private val decoderFactory: WheelDecoderFactory,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    // ==================== StateFlows (public API, unchanged) ====================

    private val _wheelState = MutableStateFlow(WheelState())
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    // Granular sub-state flows — emitted selectively when the relevant sub-state changes.
    // UI components can observe these to avoid recomposition on irrelevant field changes.
    private val _telemetryState = MutableStateFlow(TelemetryState())
    private val _settingsState = MutableStateFlow(WheelSettingsState())
    private val _identityState = MutableStateFlow(WheelIdentity())
    private val _bmsState = MutableStateFlow(BmsState())

    private val _consecutiveDecodeErrors = MutableStateFlow(0)

    // ==================== Event loop state (owned exclusively by the event loop) ====================

    private var currentDecoder: WheelDecoder? = null
    private var decoderConfig = DecoderConfig()
    private var connectionInfo: WheelConnectionInfo? = null

    /** Child job running bleManager.connect(), cancelled on disconnect. */
    private var bleConnectJob: Job? = null

    // ==================== Helpers (timers, scheduler, detector) ====================

    private val wheelTypeDetector = WheelTypeDetector()
    private val keepAliveTimer = KeepAliveTimer(scope, dispatcher)
    private val dataTimeoutTracker = DataTimeoutTracker(scope, dispatcher)
    private val commandScheduler = CommandScheduler(scope, dispatcher)

    // ==================== BLE Capture Hook ====================

    /**
     * Optional callback invoked for every BLE packet sent or received.
     * Set by the UI layer to capture raw traffic for protocol debugging.
     * Runs in the WCM event loop (single-threaded) — zero overhead when null.
     */
    var captureCallback: ((data: ByteArray, direction: BlePacketDirection) -> Unit)? = null

    // ==================== Event channel and loop ====================

    private val events = Channel<WheelEvent>(Channel.UNLIMITED)

    private val eventLoopJob: Job = scope.launch(dispatcher) {
        for (event in events) {
            processEvent(event)
        }
    }

    // ==================== Public StateFlow properties ====================

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
     * Count of consecutive null decode() returns. Resets to 0 on each successful decode.
     * Useful for diagnostics — a sustained high value indicates persistent decode failures.
     */
    val consecutiveDecodeErrors: StateFlow<Int> = _consecutiveDecodeErrors.asStateFlow()

    /**
     * Whether the keep-alive timer is running.
     */
    val isKeepAliveRunning: StateFlow<Boolean> = keepAliveTimer.isRunning

    // ==================== Public methods (emit events) ====================

    /**
     * Update decoder configuration.
     * Call this when user settings change.
     */
    fun updateConfig(config: DecoderConfig) {
        events.trySend(WheelEvent.ConfigUpdated(config))
    }

    /**
     * Get the current decoder configuration.
     */
    fun getConfig(): DecoderConfig = decoderConfig

    /**
     * Connect to a wheel at the given address.
     * Fire-and-forget — observe [connectionState] for the result.
     *
     * @param address BLE MAC address (Android) or peripheral identifier (iOS)
     * @param wheelType Optional wheel type hint; if null, will be auto-detected
     */
    fun connect(address: String, wheelType: WheelType? = null) {
        events.trySend(WheelEvent.ConnectRequested(address, wheelType))
    }

    /**
     * Disconnect from the current wheel.
     * Fire-and-forget — observe [connectionState] for the result.
     */
    fun disconnect() {
        events.trySend(WheelEvent.DisconnectRequested)
    }

    /**
     * Disconnect and shut down the event loop.
     * Sends a disconnect event, closes the channel so remaining events drain,
     * then waits for the event loop to finish. Call this when tearing down the
     * manager (e.g., in Service.onDestroy) to ensure BLE GATT is released.
     */
    suspend fun shutdown() {
        events.send(WheelEvent.DisconnectRequested)
        events.close()
        eventLoopJob.join()
    }

    /**
     * Send a command to the wheel.
     */
    fun sendCommand(command: WheelCommand) {
        events.trySend(WheelEvent.SendCommand(command))
    }

    /**
     * Process incoming data from the wheel.
     * Called by platform-specific BLE implementation when data is received.
     */
    fun onDataReceived(data: ByteArray) {
        // Reset timeout tracker immediately for accurate timing
        dataTimeoutTracker.onDataReceived()
        events.trySend(WheelEvent.DataReceived(data))
    }

    /**
     * Handle BLE service discovery.
     * Called by platform-specific code after services are discovered.
     *
     * Connection info is computed synchronously so [getConnectionInfo] returns
     * a valid result immediately after this call.
     *
     * @param services The discovered BLE services
     * @param deviceName The device name for detection heuristics
     */
    fun onServicesDiscovered(services: DiscoveredServices, deviceName: String?) {
        // Pre-compute connectionInfo synchronously so getConnectionInfo() works
        // immediately after this call (required by platform BLE layers).
        val result = wheelTypeDetector.detect(services, deviceName)
        when (result) {
            is WheelTypeDetector.DetectionResult.Detected -> {
                connectionInfo = WheelConnectionInfo(
                    wheelType = result.wheelType,
                    readServiceUuid = result.readServiceUuid,
                    readCharacteristicUuid = result.readCharacteristicUuid,
                    writeServiceUuid = result.writeServiceUuid,
                    writeCharacteristicUuid = result.writeCharacteristicUuid
                )
            }
            is WheelTypeDetector.DetectionResult.Ambiguous -> {
                connectionInfo = wheelTypeDetector.getUuidsForType(WheelType.GOTWAY_VIRTUAL)
            }
            is WheelTypeDetector.DetectionResult.Unknown -> {}
        }
        events.trySend(WheelEvent.ServicesDiscovered(services, deviceName))
    }

    /**
     * Handle wheel type detection.
     * Called when wheel type is determined (either from services or auto-detect).
     */
    fun onWheelTypeDetected(wheelType: WheelType) {
        // Pre-compute connectionInfo synchronously (same reason as onServicesDiscovered)
        if (connectionInfo == null) {
            connectionInfo = wheelTypeDetector.getUuidsForType(wheelType)
        }
        events.trySend(WheelEvent.WheelTypeDetected(wheelType))
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

    // ==================== Convenience command methods ====================

    fun wheelBeep() { sendCommand(WheelCommand.Beep) }
    fun toggleLight(enabled: Boolean) { sendCommand(WheelCommand.SetLight(enabled)) }
    fun setPedalsMode(mode: Int) { sendCommand(WheelCommand.SetPedalsMode(mode)) }
    fun setLightMode(mode: Int) { sendCommand(WheelCommand.SetLightMode(mode)) }
    fun setLed(enabled: Boolean) { sendCommand(WheelCommand.SetLed(enabled)) }
    fun setLedMode(mode: Int) { sendCommand(WheelCommand.SetLedMode(mode)) }
    fun setStrobeMode(mode: Int) { sendCommand(WheelCommand.SetStrobeMode(mode)) }
    fun setAlarmMode(mode: Int) { sendCommand(WheelCommand.SetAlarmMode(mode)) }
    fun calibrate() { sendCommand(WheelCommand.Calibrate) }
    fun powerOff() { sendCommand(WheelCommand.PowerOff) }
    fun setLock(locked: Boolean) { sendCommand(WheelCommand.SetLock(locked)) }
    fun resetTrip() { sendCommand(WheelCommand.ResetTrip) }
    fun setMaxSpeed(speed: Int) { sendCommand(WheelCommand.SetMaxSpeed(speed)) }
    fun setAlarmSpeed(speed: Int, num: Int) { sendCommand(WheelCommand.SetAlarmSpeed(speed, num)) }
    fun setAlarmEnabled(enabled: Boolean, num: Int) { sendCommand(WheelCommand.SetAlarmEnabled(enabled, num)) }
    fun setLimitedMode(enabled: Boolean) { sendCommand(WheelCommand.SetLimitedMode(enabled)) }
    fun setLimitedSpeed(speed: Int) { sendCommand(WheelCommand.SetLimitedSpeed(speed)) }
    fun setTailLight(enabled: Boolean) { sendCommand(WheelCommand.SetTailLight(enabled)) }
    fun setDrl(enabled: Boolean) { sendCommand(WheelCommand.SetDrl(enabled)) }
    fun setLedColor(value: Int, ledNum: Int) { sendCommand(WheelCommand.SetLedColor(value, ledNum)) }
    fun setLightBrightness(value: Int) { sendCommand(WheelCommand.SetLightBrightness(value)) }
    fun setHandleButton(enabled: Boolean) { sendCommand(WheelCommand.SetHandleButton(enabled)) }
    fun setBrakeAssist(enabled: Boolean) { sendCommand(WheelCommand.SetBrakeAssist(enabled)) }
    fun setTransportMode(enabled: Boolean) { sendCommand(WheelCommand.SetTransportMode(enabled)) }
    fun setRideMode(enabled: Boolean) { sendCommand(WheelCommand.SetRideMode(enabled)) }
    fun setGoHomeMode(enabled: Boolean) { sendCommand(WheelCommand.SetGoHomeMode(enabled)) }
    fun setFancierMode(enabled: Boolean) { sendCommand(WheelCommand.SetFancierMode(enabled)) }
    fun setRollAngleMode(mode: Int) { sendCommand(WheelCommand.SetRollAngleMode(mode)) }
    fun setMute(enabled: Boolean) { sendCommand(WheelCommand.SetMute(enabled)) }
    fun setSpeakerVolume(volume: Int) { sendCommand(WheelCommand.SetSpeakerVolume(volume)) }
    fun setBeeperVolume(volume: Int) { sendCommand(WheelCommand.SetBeeperVolume(volume)) }
    fun setFanQuiet(enabled: Boolean) { sendCommand(WheelCommand.SetFanQuiet(enabled)) }
    fun setFan(enabled: Boolean) { sendCommand(WheelCommand.SetFan(enabled)) }
    fun setPedalTilt(angle: Int) { sendCommand(WheelCommand.SetPedalTilt(angle)) }
    fun setPedalSensitivity(sensitivity: Int) { sendCommand(WheelCommand.SetPedalSensitivity(sensitivity)) }
    fun setMilesMode(enabled: Boolean) { sendCommand(WheelCommand.SetMilesMode(enabled)) }
    fun setCutoutAngle(angle: Int) { sendCommand(WheelCommand.SetCutoutAngle(angle)) }
    // Begode extended settings
    fun setWeakMagnetism(level: Int) { sendCommand(WheelCommand.SetWeakMagnetism(level)) }
    fun setExtendedRollAngle(level: Int) { sendCommand(WheelCommand.SetExtendedRollAngle(level)) }
    fun setPowerAlarm(percentage: Int) { sendCommand(WheelCommand.SetPowerAlarm(percentage)) }
    fun setPlateProtection(enabled: Boolean) { sendCommand(WheelCommand.SetPlateProtection(enabled)) }
    // InMotion V2 extended settings
    fun setBermAngleMode(enabled: Boolean) { sendCommand(WheelCommand.SetBermAngleMode(enabled)) }
    fun setBermAngle(angle: Int) { sendCommand(WheelCommand.SetBermAngle(angle)) }
    fun setTurningSensitivity(sensitivity: Int) { sendCommand(WheelCommand.SetTurningSensitivity(sensitivity)) }
    fun setOnePedalMode(enabled: Boolean) { sendCommand(WheelCommand.SetOnePedalMode(enabled)) }
    fun setSpeedingBrakingMode(enabled: Boolean) { sendCommand(WheelCommand.SetSpeedingBrakingMode(enabled)) }
    fun setSpeedingBrakingAngle(angle: Int) { sendCommand(WheelCommand.SetSpeedingBrakingAngle(angle)) }
    fun setSoundWave(enabled: Boolean) { sendCommand(WheelCommand.SetSoundWave(enabled)) }
    fun setSoundWaveSensitivity(sensitivity: Int) { sendCommand(WheelCommand.SetSoundWaveSensitivity(sensitivity)) }
    fun setSafeSpeedLimit(enabled: Boolean) { sendCommand(WheelCommand.SetSafeSpeedLimit(enabled)) }
    fun setBackwardOverspeedAlert(enabled: Boolean) { sendCommand(WheelCommand.SetBackwardOverspeedAlert(enabled)) }
    fun setTailLightMode(mode: Int) { sendCommand(WheelCommand.SetTailLightMode(mode)) }
    fun setTurnSignalMode(mode: Int) { sendCommand(WheelCommand.SetTurnSignalMode(mode)) }
    fun setLogoLightBrightness(brightness: Int) { sendCommand(WheelCommand.SetLogoLightBrightness(brightness)) }
    fun setAutoHeadlight(enabled: Boolean) { sendCommand(WheelCommand.SetAutoHeadlight(enabled)) }
    fun setLightEffect(enabled: Boolean) { sendCommand(WheelCommand.SetLightEffect(enabled)) }
    fun setLightEffectMode(mode: Int) { sendCommand(WheelCommand.SetLightEffectMode(mode)) }
    fun setTwoBatteryMode(enabled: Boolean) { sendCommand(WheelCommand.SetTwoBatteryMode(enabled)) }
    fun setLowBatterySafeMode(enabled: Boolean) { sendCommand(WheelCommand.SetLowBatterySafeMode(enabled)) }
    fun setSpinKill(enabled: Boolean) { sendCommand(WheelCommand.SetSpinKill(enabled)) }
    fun setCruise(enabled: Boolean) { sendCommand(WheelCommand.SetCruise(enabled)) }
    fun setLoadDetect(enabled: Boolean) { sendCommand(WheelCommand.SetLoadDetect(enabled)) }
    fun setStandbyTime(minutes: Int) { sendCommand(WheelCommand.SetStandbyTime(minutes)) }
    fun setChargeLimit(percentage: Int) { sendCommand(WheelCommand.SetChargeLimit(percentage)) }
    fun requestBmsData(bmsNum: Int, dataType: Int) { sendCommand(WheelCommand.RequestBmsData(bmsNum, dataType)) }
    fun setKingsongAlarms(a1: Int, a2: Int, a3: Int, max: Int) { sendCommand(WheelCommand.SetKingsongAlarms(a1, a2, a3, max)) }
    fun requestAlarmSettings() { sendCommand(WheelCommand.RequestAlarmSettings) }

    /**
     * Execute a settings command by ID.
     * Used by the shared WheelSettingsConfig to dispatch UI actions.
     */
    fun executeCommand(commandId: SettingsCommandId, intValue: Int = 0, boolValue: Boolean = false) {
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
            SettingsCommandId.PEDAL_TILT -> setPedalTilt(intValue * 10)
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
            // InMotion V2 extended settings
            SettingsCommandId.BERM_ANGLE_MODE -> setBermAngleMode(boolValue)
            SettingsCommandId.BERM_ANGLE -> setBermAngle(intValue)
            SettingsCommandId.TURNING_SENSITIVITY -> setTurningSensitivity(intValue)
            SettingsCommandId.ONE_PEDAL_MODE -> setOnePedalMode(boolValue)
            SettingsCommandId.SPEEDING_BRAKING_MODE -> setSpeedingBrakingMode(boolValue)
            SettingsCommandId.SPEEDING_BRAKING_ANGLE -> setSpeedingBrakingAngle(intValue)
            SettingsCommandId.SOUND_WAVE -> setSoundWave(boolValue)
            SettingsCommandId.SOUND_WAVE_SENSITIVITY -> setSoundWaveSensitivity(intValue)
            SettingsCommandId.SAFE_SPEED_LIMIT -> setSafeSpeedLimit(boolValue)
            SettingsCommandId.BACKWARD_OVERSPEED_ALERT -> setBackwardOverspeedAlert(boolValue)
            SettingsCommandId.TAIL_LIGHT_MODE -> setTailLightMode(intValue)
            SettingsCommandId.TURN_SIGNAL_MODE -> setTurnSignalMode(intValue)
            SettingsCommandId.LOGO_LIGHT_BRIGHTNESS -> setLogoLightBrightness(intValue)
            SettingsCommandId.AUTO_HEADLIGHT -> setAutoHeadlight(boolValue)
            SettingsCommandId.LIGHT_EFFECT -> setLightEffect(boolValue)
            SettingsCommandId.LIGHT_EFFECT_MODE -> setLightEffectMode(intValue)
            SettingsCommandId.TWO_BATTERY_MODE -> setTwoBatteryMode(boolValue)
            SettingsCommandId.LOW_BATTERY_SAFE_MODE -> setLowBatterySafeMode(boolValue)
            SettingsCommandId.SPIN_KILL -> setSpinKill(boolValue)
            SettingsCommandId.CRUISE -> setCruise(boolValue)
            SettingsCommandId.LOAD_DETECT -> setLoadDetect(boolValue)
            SettingsCommandId.STANDBY_TIME -> setStandbyTime(intValue)
            SettingsCommandId.CHARGE_LIMIT -> setChargeLimit(intValue)
            // Begode extended settings
            SettingsCommandId.WEAK_MAGNETISM -> setWeakMagnetism(intValue)
            SettingsCommandId.EXTENDED_ROLL_ANGLE -> setExtendedRollAngle(intValue)
            SettingsCommandId.POWER_ALARM -> setPowerAlarm(intValue)
            SettingsCommandId.PLATE_PROTECTION -> setPlateProtection(boolValue)
        }
    }

    // ==================== Event Processing ====================

    private suspend fun processEvent(event: WheelEvent) {
        when (event) {
            is WheelEvent.ConnectRequested -> handleConnect(event)
            is WheelEvent.DisconnectRequested -> handleDisconnect()
            is WheelEvent.BleConnectResult -> handleBleResult(event)
            is WheelEvent.ServicesDiscovered -> handleServicesDiscovered(event)
            is WheelEvent.WheelTypeDetected -> handleWheelTypeDetected(event)
            is WheelEvent.DataReceived -> handleDataReceived(event)
            is WheelEvent.KeepAliveTick -> handleKeepAliveTick()
            is WheelEvent.DataTimeout -> handleDataTimeout(event)
            is WheelEvent.SendCommand -> handleSendCommand(event)
            is WheelEvent.ConfigUpdated -> handleConfigUpdated(event)
        }
    }

    // ==================== Event Handlers ====================

    private fun handleConnect(event: WheelEvent.ConnectRequested) {
        // Cancel any pending BLE connect from a previous call
        bleConnectJob?.cancel()

        // Reset all state
        resetAllState()
        currentDecoder = null
        connectionInfo = null

        // Transition to Connecting
        _connectionState.value = ConnectionState.Connecting(event.address)

        // If wheel type is known, set up decoder immediately
        event.wheelType?.let { setupDecoder(it) }

        // Launch BLE connect as a child job so the event loop isn't blocked
        bleConnectJob = scope.launch(dispatcher) {
            try {
                val success = bleManager.connect(event.address)
                events.send(WheelEvent.BleConnectResult(success, event.address))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by disconnect — don't send result
            } catch (e: Exception) {
                events.send(WheelEvent.BleConnectResult(
                    success = false,
                    address = event.address,
                    error = e.message ?: "Connection failed"
                ))
            }
        }
    }

    private fun handleBleResult(event: WheelEvent.BleConnectResult) {
        bleConnectJob = null

        // Ignore stale results (e.g., already disconnected by a concurrent call)
        if (_connectionState.value !is ConnectionState.Connecting) {
            return
        }

        if (event.success) {
            _connectionState.value = ConnectionState.DiscoveringServices(event.address)
            startDataTimeoutMonitor(event.address)
        } else {
            _connectionState.value = ConnectionState.Failed(
                error = event.error ?: "Connection failed",
                address = event.address
            )
        }
    }

    private suspend fun handleDisconnect() {
        // Cancel pending BLE connect
        bleConnectJob?.cancel()
        bleConnectJob = null

        // Stop timers and scheduled commands
        stopTimers()
        commandScheduler.cancelAll()

        // Reset decoder
        currentDecoder?.reset()
        currentDecoder = null
        connectionInfo = null

        // Disconnect BLE
        bleManager.disconnect()

        // Reset all state flows
        resetAllState()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun handleServicesDiscovered(event: WheelEvent.ServicesDiscovered) {
        Logger.d(TAG, "onServicesDiscovered: deviceName=${event.deviceName}, services=${event.services.serviceUuids()}")
        if (!event.deviceName.isNullOrBlank()) {
            val oldState = _wheelState.value
            _wheelState.value = oldState.copy(btName = event.deviceName)
            emitGranularStates(oldState, _wheelState.value)
        }

        val result = wheelTypeDetector.detect(event.services, event.deviceName)
        Logger.d(TAG, "Detection result: $result")

        when (result) {
            is WheelTypeDetector.DetectionResult.Detected -> {
                Logger.d(TAG, "Detected: ${result.wheelType}, read=${result.readServiceUuid}/${result.readCharacteristicUuid}")
                // connectionInfo already set in onServicesDiscovered() public method
                setupDecoder(result.wheelType)
            }
            is WheelTypeDetector.DetectionResult.Ambiguous -> {
                Logger.d(TAG, "Ambiguous: ${result.possibleTypes}, using GOTWAY_VIRTUAL")
                // connectionInfo already set in onServicesDiscovered() public method
                setupDecoder(WheelType.GOTWAY_VIRTUAL)
            }
            is WheelTypeDetector.DetectionResult.Unknown -> {
                Logger.w(TAG, "Unknown wheel: ${result.reason}")
                _connectionState.value = ConnectionState.Failed(
                    error = "Unknown wheel type: ${result.reason}",
                    address = getCurrentAddress()
                )
            }
        }
    }

    private fun handleWheelTypeDetected(event: WheelEvent.WheelTypeDetected) {
        setupDecoder(event.wheelType)
        // connectionInfo already set in onWheelTypeDetected() public method
    }

    private fun handleDataReceived(event: WheelEvent.DataReceived) {
        captureCallback?.invoke(event.data, BlePacketDirection.RX)

        val decoder = currentDecoder
        if (decoder == null) {
            Logger.w(TAG, "Data received (${event.data.size} bytes) but no decoder set")
            return
        }

        val oldState = _wheelState.value
        val result = try {
            decoder.decode(event.data, oldState, decoderConfig)
        } catch (e: Exception) {
            Logger.e(TAG, "decode() threw for ${event.data.size} bytes (decoder=${decoder.wheelType})", e)
            _consecutiveDecodeErrors.value++
            return
        }
        if (result == null) {
            _consecutiveDecodeErrors.value++
            Logger.d(TAG, "decode() returned null for ${event.data.size} bytes (decoder=${decoder.wheelType})")
            return
        }

        _consecutiveDecodeErrors.value = 0

        val newState = result.newState
        _wheelState.value = newState

        // Emit granular sub-state flows only when the relevant sub-state changes
        emitGranularStates(oldState, newState)

        // Dispatch response commands directly (no need to re-queue through the channel)
        if (result.commands.isNotEmpty()) {
            commandScheduler.scheduleSequence {
                result.commands.forEach { cmd ->
                    dispatchCommand(cmd)
                }
            }
        }

        // Update connection state if decoder is ready
        if (decoder.isReady() && _connectionState.value !is ConnectionState.Connected) {
            val address = getCurrentAddress() ?: ""
            Logger.d(TAG, "Decoder ready, transitioning to Connected")
            _connectionState.value = ConnectionState.Connected(
                address = address,
                wheelName = result.newState.displayName
            )
        } else if (!decoder.isReady()) {
            Logger.d(TAG, "Decoded OK but isReady()=false (decoder=${decoder.wheelType})")
        }
    }

    private suspend fun handleKeepAliveTick() {
        val command = currentDecoder?.getKeepAliveCommand() ?: return
        dispatchCommand(command)
    }

    private fun handleDataTimeout(event: WheelEvent.DataTimeout) {
        _connectionState.value = ConnectionState.ConnectionLost(
            address = event.address,
            reason = "No data received"
        )
        // Don't stop timers — keep-alive must continue so polling wheels
        // can recover when signal returns. The timeout tracker continues
        // monitoring and will naturally reset via onDataReceived().
    }

    private suspend fun handleSendCommand(event: WheelEvent.SendCommand) {
        dispatchCommand(event.command)
    }

    private fun handleConfigUpdated(event: WheelEvent.ConfigUpdated) {
        decoderConfig = event.config
    }

    // ==================== Private Helpers ====================

    /**
     * Dispatch a command to the BLE layer. Called from event handlers (not through the channel).
     */
    private suspend fun dispatchCommand(command: WheelCommand) {
        when (command) {
            is WheelCommand.SendBytes -> {
                captureCallback?.invoke(command.data, BlePacketDirection.TX)
                bleManager.write(command.data)
            }
            is WheelCommand.SendDelayed -> {
                commandScheduler.schedule(command.delayMs) {
                    captureCallback?.invoke(command.data, BlePacketDirection.TX)
                    bleManager.write(command.data)
                }
            }
            else -> {
                val rawCommands = currentDecoder?.buildCommand(command) ?: return
                commandScheduler.scheduleSequence {
                    for (cmd in rawCommands) {
                        when (cmd) {
                            is WheelCommand.SendBytes -> {
                                captureCallback?.invoke(cmd.data, BlePacketDirection.TX)
                                bleManager.write(cmd.data)
                            }
                            is WheelCommand.SendDelayed -> {
                                delay(cmd.delayMs)
                                captureCallback?.invoke(cmd.data, BlePacketDirection.TX)
                                bleManager.write(cmd.data)
                            }
                            else -> {} // prevent recursion
                        }
                    }
                }
            }
        }
    }

    private fun setupDecoder(wheelType: WheelType) {
        currentDecoder?.reset()
        currentDecoder = decoderFactory.createDecoder(wheelType)
        val oldState = _wheelState.value
        _wheelState.value = oldState.copy(wheelType = wheelType)
        emitGranularStates(oldState, _wheelState.value)

        // Send init commands via commandScheduler so they are
        // cancelled on disconnect along with all other pending commands.
        currentDecoder?.getInitCommands()?.let { commands ->
            commandScheduler.scheduleSequence {
                commands.forEach { cmd ->
                    dispatchCommand(cmd)
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
            events.send(WheelEvent.KeepAliveTick)
        }
    }

    private fun startDataTimeoutMonitor(address: String) {
        // Proportional timeout: 40x the keep-alive interval, minimum 30 seconds.
        // 30s minimum — short drops during riding shouldn't trigger ConnectionLost.
        val decoder = currentDecoder
        val keepAliveMs = decoder?.keepAliveIntervalMs ?: 0L
        val timeoutMs = maxOf(keepAliveMs * 40, 30_000L)

        dataTimeoutTracker.start(timeoutMs = timeoutMs) {
            events.send(WheelEvent.DataTimeout(address))
        }
    }

    private fun stopTimers() {
        keepAliveTimer.stop()
        dataTimeoutTracker.stop()
    }

    private fun resetAllState() {
        _wheelState.value = WheelState()
        _telemetryState.value = TelemetryState()
        _settingsState.value = WheelSettingsState()
        _identityState.value = WheelIdentity()
        _bmsState.value = BmsState()
        _consecutiveDecodeErrors.value = 0
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

    companion object {
        private const val TAG = "WheelConnectionManager"
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
