package org.freewheel.core.service

import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.ble.WheelTypeDetector
import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
import org.freewheel.core.protocol.DecodeResult
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.protocol.WheelCommand
import org.freewheel.core.protocol.WheelDecoder
import org.freewheel.core.protocol.WheelDecoderFactory
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.logging.ConnectionErrorEvent
import org.freewheel.core.utils.Logger
import org.freewheel.core.utils.currentTimeMillis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * Central manager for wheel connections.
 * Coordinates BLE communication, protocol decoding, and state management.
 *
 * Uses a **reducer + scan** pattern (MVI): all events flow through a single
 * `scan` pipeline where a pure reducer computes `(State, Event) → (NewState, Effects)`.
 * Side effects (BLE I/O, timers, command dispatch) are extracted and executed
 * after each state transition, making every transition explicit and atomic.
 *
 * ## Usage
 * ```
 * val manager = WheelConnectionManager(bleManager, decoderFactory, scope)
 *
 * // Observe state
 * manager.telemetryState.collect { tel -> updateTelemetryUI(tel) }
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
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val wheelTypeDetector: WheelTypeDetector = WheelTypeDetector(),
    private val keepAliveTimer: KeepAliveTimer = KeepAliveTimer(scope, dispatcher),
    private val dataTimeoutTracker: DataTimeoutTracker = DataTimeoutTracker(scope, dispatcher),
    private val commandScheduler: CommandScheduler = CommandScheduler(scope, dispatcher)
) : WheelConnectionManagerPort {

    // ==================== BLE Capture Hook ====================

    /**
     * Optional callback invoked for every BLE packet sent or received.
     * Set by the UI layer to capture raw traffic for protocol debugging.
     * Runs in the WCM event loop (single-threaded) — zero overhead when null.
     */
    override var captureCallback: ((data: ByteArray, direction: BlePacketDirection, annotation: String) -> Unit)? = null

    /**
     * Optional callback invoked when the decoder encounters an unhandled frame.
     * Set by the UI layer to collect unrecognized protocol data for sharing.
     */
    override var unhandledCallback: ((reason: String, frameData: ByteArray) -> Unit)? = null

    /**
     * Optional callback invoked for connection error events (BLE errors, timeouts,
     * decode exceptions, unhandled frames, state transitions).
     * Set by the UI layer to write CSV error logs per connection session.
     * Runs in the WCM event loop — zero overhead when null.
     */
    override var errorLogCallback: ((ConnectionErrorEvent) -> Unit)? = null

    // ==================== Unified state + scan pipeline ====================

    private val _wcmState = MutableStateFlow(WcmState())

    private val events = Channel<WheelEvent>(Channel.UNLIMITED)

    private val eventLoopJob: Job = scope.launch(dispatcher) {
        events.receiveAsFlow()
            .scan(WcmTransition(WcmState())) { prev, event ->
                reduce(prev.state, event)
            }
            .collect { transition ->
                _wcmState.value = transition.state
                executeEffects(transition.effects)
            }
    }

    // ==================== Effect-layer state (not in WcmState) ====================

    /** Child job running bleManager.connect(), cancelled on disconnect. */
    private var bleConnectJob: Job? = null

    // ==================== Derived public flows ====================

    // Uses scope + dispatcher so stateIn collectors run on the same dispatcher
    // as the scan pipeline. This guarantees derived flows see state updates within
    // the same dispatch cycle, preventing timing issues where a collector on
    // Dispatchers.Main could observe stale derived state between the _wcmState
    // update and the derived flow emission.
    private val derivedScope = scope + dispatcher

    /** Current connection state. */
    override val connectionState: StateFlow<ConnectionState> = _wcmState
        .map { it.connectionState }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    /** Telemetry sub-state (speed, voltage, current, etc.). Null until first BLE data arrives. */
    override val telemetryState: StateFlow<TelemetryState?> = _wcmState
        .map { it.telemetry }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, null)

    /** Settings sub-state (pedals mode, light mode, etc.). Updated rarely. */
    override val settingsState: StateFlow<WheelSettings> = _wcmState
        .map { it.settings }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, WheelSettings.None)

    /** Identity sub-state (wheel type, model, serial, etc.). Set once per connection. */
    override val identityState: StateFlow<WheelIdentity> = _wcmState
        .map { it.identity }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, WheelIdentity())

    /** BMS sub-state (battery pack snapshots). Updated periodically. */
    override val bmsState: StateFlow<BmsState> = _wcmState
        .map { it.bms }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, BmsState())

    /** Count of consecutive decode errors. Resets to 0 on each successful decode. */
    val consecutiveDecodeErrors: StateFlow<Int> = _wcmState
        .map { it.consecutiveDecodeErrors }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, 0)

    /** Count of consecutive BLE errors. Resets to 0 on each successful decode. */
    val consecutiveBleErrors: StateFlow<Int> = _wcmState
        .map { it.consecutiveBleErrors }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, 0)

    /** Wheel capabilities. Resolved after model/firmware detection; monotonically expanding. */
    override val capabilities: StateFlow<CapabilitySet> = _wcmState
        .map { it.capabilities }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, CapabilitySet())

    /** Accumulated event log entries from the wheel (Veteran/Leaperkim). Sorted by index, deduplicated. */
    override val eventLogEntries: StateFlow<List<org.freewheel.core.domain.EventLogEntry>> = _wcmState
        .map { it.eventLogEntries }
        .distinctUntilChanged()
        .stateIn(derivedScope, SharingStarted.Eagerly, emptyList())

    /** Whether the keep-alive timer is running. */
    val isKeepAliveRunning: StateFlow<Boolean> = keepAliveTimer.isRunning

    // ==================== Public methods (emit events) ====================

    /**
     * Update decoder configuration.
     * Call this when user settings change.
     */
    override fun updateConfig(config: DecoderConfig) {
        events.trySend(WheelEvent.ConfigUpdated(config))
    }

    /**
     * Get the current decoder configuration.
     */
    override fun getConfig(): DecoderConfig = _wcmState.value.decoderConfig

    /**
     * Connect to a wheel at the given address.
     * Fire-and-forget — observe [connectionState] for the result.
     *
     * @param address BLE MAC address (Android) or peripheral identifier (iOS)
     * @param wheelType Optional wheel type hint; if null, will be auto-detected
     */
    override fun connect(address: String, wheelType: WheelType?) {
        events.trySend(WheelEvent.ConnectRequested(address, wheelType))
    }

    /**
     * Disconnect from the current wheel.
     * Fire-and-forget — observe [connectionState] for the result.
     */
    override fun disconnect() {
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
        bleManager.destroy()
    }

    /**
     * Send a command to the wheel.
     */
    override fun sendCommand(command: WheelCommand) {
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
     * Report a BLE characteristic update error.
     * Called by platform-specific BLE implementation on GATT/CoreBluetooth errors.
     * Consecutive errors past [MAX_BLE_ERRORS] trigger [ConnectionState.ConnectionLost].
     */
    fun onBleError() {
        events.trySend(WheelEvent.BleError)
    }

    /**
     * Handle BLE service discovery.
     * Called by platform-specific code after services are discovered.
     *
     * @param services The discovered BLE services
     * @param deviceName The device name for detection heuristics
     */
    fun onServicesDiscovered(services: DiscoveredServices, deviceName: String?) {
        events.trySend(WheelEvent.ServicesDiscovered(services, deviceName))
    }

    /**
     * Handle wheel type detection.
     * Called when wheel type is determined (either from services or auto-detect).
     */
    fun onWheelTypeDetected(wheelType: WheelType) {
        events.trySend(WheelEvent.WheelTypeDetected(wheelType))
    }

    /**
     * Get the connection info for the current wheel.
     * Returns null if not connected or wheel type unknown.
     */
    override fun getConnectionInfo(): WheelConnectionInfo? = _wcmState.value.connectionInfo

    /**
     * Get the current decoder.
     * Exposed for testing and advanced use cases.
     */
    fun getCurrentDecoder() = _wcmState.value.decoder

    // ==================== Convenience command methods ====================

    override fun wheelBeep() { sendCommand(WheelCommand.Beep) }
    override fun toggleLight(enabled: Boolean) { sendCommand(WheelCommand.SetLight(enabled)) }
    override fun setPedalsMode(mode: Int) { sendCommand(WheelCommand.SetPedalsMode(mode)) }
    fun setLightMode(mode: Int) { sendCommand(WheelCommand.SetLightMode(mode)) }
    fun setLed(enabled: Boolean) { sendCommand(WheelCommand.SetLed(enabled)) }
    fun setLedMode(mode: Int) { sendCommand(WheelCommand.SetLedMode(mode)) }
    fun setStrobeMode(mode: Int) { sendCommand(WheelCommand.SetStrobeMode(mode)) }
    fun setAlarmMode(mode: Int) { sendCommand(WheelCommand.SetAlarmMode(mode)) }
    fun calibrate() { sendCommand(WheelCommand.Calibrate) }
    fun powerOff() { sendCommand(WheelCommand.PowerOff) }
    fun setLock(locked: Boolean) { sendCommand(WheelCommand.SetLock(locked)) }
    fun setVeteranLock(locked: Boolean, password: String) { sendCommand(WheelCommand.SetVeteranLock(locked, password)) }
    override fun requestEventLog() { sendCommand(WheelCommand.RequestEventLog) }
    override fun clearEventLog() { events.trySend(WheelEvent.ClearEventLog) }
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
    override fun executeCommand(commandId: SettingsCommandId, intValue: Int, boolValue: Boolean) {
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
            // Veteran extended settings
            SettingsCommandId.HIGH_SPEED_MODE -> sendCommand(WheelCommand.SetHighSpeedMode(boolValue))
            SettingsCommandId.LOW_VOLTAGE_MODE -> sendCommand(WheelCommand.SetLowVoltageMode(boolValue))
            SettingsCommandId.KEY_TONE -> sendCommand(WheelCommand.SetKeyTone(intValue))
            SettingsCommandId.SCREEN_BACKLIGHT -> sendCommand(WheelCommand.SetScreenBacklight(intValue))
            SettingsCommandId.STOP_SPEED -> sendCommand(WheelCommand.SetStopSpeed(intValue))
            SettingsCommandId.VETERAN_PWM_LIMIT -> sendCommand(WheelCommand.SetVeteranPwmLimit(intValue))
            SettingsCommandId.VOLTAGE_CORRECTION -> sendCommand(WheelCommand.SetVoltageCorrection(intValue))
            SettingsCommandId.MAX_CHARGE_VOLTAGE -> sendCommand(WheelCommand.SetMaxChargeVoltage(intValue))
            SettingsCommandId.BRAKE_PRESSURE_ALARM -> sendCommand(WheelCommand.SetBrakePressureAlarm(intValue))
            SettingsCommandId.LATERAL_CUTOFF_ANGLE -> sendCommand(WheelCommand.SetLateralCutoffAngle(intValue))
            SettingsCommandId.DYNAMIC_ASSIST -> sendCommand(WheelCommand.SetDynamicAssist(intValue))
            SettingsCommandId.ACCELERATION_LIMIT -> sendCommand(WheelCommand.SetAccelerationLimit(intValue))
            SettingsCommandId.WHEEL_DISPLAY_UNIT -> sendCommand(WheelCommand.SetWheelDisplayUnit(intValue == 1))
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
            // InMotion P6 settings
            SettingsCommandId.SCREEN_AUTO_OFF -> sendCommand(WheelCommand.SetScreenAutoOff(boolValue))
            SettingsCommandId.BALANCE_ANGLE -> sendCommand(WheelCommand.SetBalanceAngle(intValue))
            SettingsCommandId.AUTO_LOCK -> sendCommand(WheelCommand.SetAutoLock(boolValue))
            SettingsCommandId.CHARGING_CURRENT -> sendCommand(WheelCommand.SetChargingCurrent(intValue, intValue))
            SettingsCommandId.IGNORE_TIRE_PRESSURE -> sendCommand(WheelCommand.SetIgnoreTirePressure(boolValue))
            SettingsCommandId.MIN_TIRE_PRESSURE -> sendCommand(WheelCommand.SetMinTirePressure(intValue and 0xFF, (intValue shr 8) and 0xFF))
            SettingsCommandId.RIDE_CONNECT_SWITCH -> sendCommand(WheelCommand.SetRideConnectSwitch(boolValue))
            SettingsCommandId.RIDE_CONNECT_LOW_BATTERY -> sendCommand(WheelCommand.SetRideConnectLowBattery(boolValue))
            SettingsCommandId.SPEED_TILTBACK_ENABLE -> sendCommand(WheelCommand.SetSpeedTiltbackEnable(boolValue))
        }
    }

    // ==================== Reducer (pure — no I/O, no var mutation) ====================

    private fun reduce(state: WcmState, event: WheelEvent): WcmTransition {
        return when (event) {
            is WheelEvent.ConnectRequested -> reduceConnect(state, event)
            is WheelEvent.DisconnectRequested -> reduceDisconnect(state)
            is WheelEvent.BleConnectResult -> reduceBleResult(state, event)
            is WheelEvent.ServicesDiscovered -> reduceServicesDiscovered(state, event)
            is WheelEvent.WheelTypeDetected -> reduceWheelTypeDetected(state, event)
            is WheelEvent.DataReceived -> reduceDataReceived(state, event)
            is WheelEvent.BleError -> reduceBleError(state)
            is WheelEvent.KeepAliveTick -> reduceKeepAliveTick(state)
            is WheelEvent.DataTimeout -> reduceDataTimeout(state, event)
            is WheelEvent.SendCommand -> reduceSendCommand(state, event)
            is WheelEvent.ConfigUpdated -> reduceConfigUpdated(state, event)
            is WheelEvent.ClearEventLog -> WcmTransition(state.copy(eventLogEntries = emptyList()))
        }
    }

    private fun reduceConnect(state: WcmState, event: WheelEvent.ConnectRequested): WcmTransition {
        // De-duplicate: if already connecting to the same address, no-op.
        // Prevents double-tap from causing unnecessary BLE teardown/setup.
        val cs = state.connectionState
        if (cs is ConnectionState.Connecting && cs.address == event.address) {
            return WcmTransition(state)
        }

        var newState = WcmState(
            decoderConfig = state.decoderConfig,
            connectionState = ConnectionState.Connecting(event.address)
        )

        // Decoder setup effects (if wheel type known)
        var decoderEffects = emptyList<WcmEffect>()
        event.wheelType?.let { type ->
            val (decoderState, effects) = setupDecoderTransition(newState, type)
            newState = decoderState
            decoderEffects = effects
        }

        // Emit cleanup effects based on what the current state requires.
        // Connecting → cancel the in-progress BLE job
        // Connected/DiscoveringServices → disconnect the established OS connection
        // Disconnected/Failed → nothing to clean up
        val effects = buildList {
            when (state.connectionState) {
                is ConnectionState.Connecting -> add(WcmEffect.CancelBleConnect)
                is ConnectionState.Connected,
                is ConnectionState.DiscoveringServices,
                is ConnectionState.ConnectionLost -> add(WcmEffect.BleDisconnect)
                is ConnectionState.Disconnected,
                is ConnectionState.Failed,
                is ConnectionState.Scanning -> { /* nothing to tear down */ }
            }
            add(WcmEffect.StopTimers)
            add(WcmEffect.CancelCommands)
            state.decoder?.let { add(WcmEffect.ResetDecoder(it)) }
            addAll(decoderEffects)
            add(WcmEffect.BleConnect(event.address))
        }
        return WcmTransition(newState, effects)
    }

    private fun reduceDisconnect(state: WcmState): WcmTransition {
        val effects = buildList {
            when (state.connectionState) {
                is ConnectionState.Connecting -> add(WcmEffect.CancelBleConnect)
                is ConnectionState.Connected,
                is ConnectionState.DiscoveringServices,
                is ConnectionState.ConnectionLost -> add(WcmEffect.BleDisconnect)
                is ConnectionState.Disconnected,
                is ConnectionState.Failed,
                is ConnectionState.Scanning -> { /* nothing to tear down */ }
            }
            add(WcmEffect.StopTimers)
            add(WcmEffect.CancelCommands)
            state.decoder?.let { add(WcmEffect.ResetDecoder(it)) }
            if (state.connectionState !is ConnectionState.Disconnected) {
                add(WcmEffect.LogConnectionError(ConnectionErrorEvent.StateTransition(
                    timestampMs = currentTimeMillis(),
                    from = state.connectionState.statusText,
                    to = "Disconnected",
                    reason = "User disconnect"
                )))
            }
        }
        // Atomic reset — impossible to forget a field
        return WcmTransition(
            state = WcmState(decoderConfig = state.decoderConfig),
            effects = effects
        )
    }

    private fun reduceBleResult(state: WcmState, event: WheelEvent.BleConnectResult): WcmTransition {
        // Ignore stale results: wrong state, or result for a different address
        // (e.g., rapid disconnect → reconnect produces a stale result from the first attempt)
        val connecting = state.connectionState as? ConnectionState.Connecting
            ?: return WcmTransition(state)
        if (connecting.address != event.address) {
            return WcmTransition(state)
        }

        if (event.success) {
            val keepAliveMs = state.decoder?.keepAliveIntervalMs ?: 0L
            val timeoutMs = maxOf(keepAliveMs * 40, 30_000L)
            return WcmTransition(
                state = state.copy(connectionState = ConnectionState.DiscoveringServices(event.address)),
                effects = listOf(WcmEffect.StartDataTimeout(event.address, timeoutMs))
            )
        } else {
            return transitionToFailed(
                state,
                error = event.error ?: "Connection failed",
                address = event.address
            )
        }
    }

    private fun reduceServicesDiscovered(state: WcmState, event: WheelEvent.ServicesDiscovered): WcmTransition {
        Logger.d(TAG, "onServicesDiscovered: deviceName=${event.deviceName}, services=${event.services.serviceUuids()}")
        var newState = state
        if (!event.deviceName.isNullOrBlank()) {
            newState = newState.copy(identity = newState.identity.copy(btName = event.deviceName))
        }

        val result = wheelTypeDetector.detect(event.services, event.deviceName)
        Logger.d(TAG, "Detection result: $result")

        return when (result) {
            is WheelTypeDetector.DetectionResult.Detected -> {
                Logger.d(TAG, "Detected: ${result.wheelType}, read=${result.readServiceUuid}/${result.readCharacteristicUuid}")
                val info = WheelConnectionInfo(
                    wheelType = result.wheelType,
                    readServiceUuid = result.readServiceUuid,
                    readCharacteristicUuid = result.readCharacteristicUuid,
                    writeServiceUuid = result.writeServiceUuid,
                    writeCharacteristicUuid = result.writeCharacteristicUuid
                )
                val (decoderState, decoderEffects) = setupDecoderTransition(newState, result.wheelType)
                WcmTransition(
                    decoderState.copy(connectionInfo = info),
                    decoderEffects + info.toConfigureBleEffect()
                )
            }
            is WheelTypeDetector.DetectionResult.Ambiguous -> {
                Logger.d(TAG, "Ambiguous: ${result.possibleTypes}, using GOTWAY_VIRTUAL")
                val info = WheelConnectionInfo.forType(WheelType.GOTWAY_VIRTUAL)
                val (decoderState, decoderEffects) = setupDecoderTransition(newState, WheelType.GOTWAY_VIRTUAL)
                WcmTransition(
                    decoderState.copy(connectionInfo = info),
                    decoderEffects + listOfNotNull(info?.toConfigureBleEffect())
                )
            }
            is WheelTypeDetector.DetectionResult.Unknown -> {
                Logger.w(TAG, "Unknown wheel: ${result.reason}")
                transitionToFailed(
                    newState,
                    error = "Unknown wheel type: ${result.reason}",
                    address = getCurrentAddress(newState)
                )
            }
        }
    }

    private fun reduceWheelTypeDetected(state: WcmState, event: WheelEvent.WheelTypeDetected): WcmTransition {
        val info = WheelConnectionInfo.forType(event.wheelType)
        val (decoderState, decoderEffects) = setupDecoderTransition(state, event.wheelType)
        val newState = decoderState.copy(connectionInfo = info ?: state.connectionInfo)
        // Only emit ConfigureBle if BLE hasn't been configured yet
        val effects = if (info != null && state.connectionInfo == null) {
            decoderEffects + info.toConfigureBleEffect()
        } else {
            decoderEffects
        }
        return WcmTransition(newState, effects)
    }

    private fun reduceDataReceived(state: WcmState, event: WheelEvent.DataReceived): WcmTransition {
        val decoder = state.decoder
        if (decoder == null) {
            Logger.w(TAG, "Data received (${event.data.size} bytes) but no decoder set")
            return WcmTransition(state, listOf(
                WcmEffect.CapturePacket(event.data, BlePacketDirection.RX)
            ))
        }

        val result = try {
            decoder.decode(event.data, state.decoderState, state.decoderConfig)
        } catch (e: Exception) {
            Logger.e(TAG, "decode() threw for ${event.data.size} bytes (decoder=${decoder.wheelType})", e)
            return WcmTransition(
                state.copy(consecutiveDecodeErrors = state.consecutiveDecodeErrors + 1),
                listOf(
                    WcmEffect.CapturePacket(event.data, BlePacketDirection.RX, "error"),
                    WcmEffect.LogConnectionError(ConnectionErrorEvent.DecodeException(
                        timestampMs = currentTimeMillis(),
                        message = "${e::class.simpleName}: ${e.message ?: "unknown"}"
                    ))
                )
            )
        }

        val annotation = when (result) {
            is DecodeResult.Success -> "success"
            is DecodeResult.Buffering -> "buffering"
            is DecodeResult.Unhandled -> "unhandled:${result.reason}"
        }
        val captureEffect = WcmEffect.CapturePacket(event.data, BlePacketDirection.RX, annotation)

        when (result) {
            is DecodeResult.Buffering -> {
                return WcmTransition(state, listOf(captureEffect))
            }
            is DecodeResult.Unhandled -> {
                Logger.d(TAG, "Unhandled frame: ${result.reason} (${result.frameData.size} bytes, decoder=${decoder.wheelType})")
                return WcmTransition(state, listOf(
                    captureEffect,
                    WcmEffect.NotifyUnhandled(result.reason.toString(), result.frameData),
                    WcmEffect.LogConnectionError(ConnectionErrorEvent.UnhandledFrame(
                        timestampMs = currentTimeMillis(),
                        errorClass = result.reason.errorClass.name,
                        detail = result.reason.detail
                    ))
                ))
            }
            is DecodeResult.Success -> {
                // continue below
            }
        }

        val decoded = result.data

        // Determine display name for Connected state
        val displayName = (decoded.identity ?: state.identity).displayName

        val newConnectionState = if (decoder.isReady() && state.connectionState !is ConnectionState.Connected) {
            val address = getCurrentAddress(state) ?: ""
            Logger.d(TAG, "Decoder ready, transitioning to Connected")
            ConnectionState.Connected(address = address, wheelName = displayName)
        } else {
            if (!decoder.isReady()) {
                Logger.d(TAG, "Decoded OK but isReady()=false (decoder=${decoder.wheelType})")
            }
            state.connectionState
        }

        val effects = buildList {
            add(captureEffect)
            if (decoded.commands.isNotEmpty()) {
                add(WcmEffect.DispatchCommands(decoded.commands))
            }
        }

        // Refresh capabilities (monotonic merge — never removes commands)
        val newCapabilities = decoder.getCapabilities()
        val mergedCapabilities = if (newCapabilities.isResolved) {
            state.capabilities.mergeWith(newCapabilities)
        } else {
            state.capabilities
        }

        // Accumulate event log entries (deduplicate by index)
        val newLogEntries = if (decoded.logEntries.isNotEmpty()) {
            val existing = state.eventLogEntries.associateBy { it.index }.toMutableMap()
            for (entry in decoded.logEntries) {
                existing[entry.index] = entry
            }
            existing.values.sortedBy { it.index }
        } else {
            state.eventLogEntries
        }

        return WcmTransition(
            state = state.copy(
                telemetry = decoded.telemetry ?: state.telemetry,
                identity = decoded.identity ?: state.identity,
                bms = decoded.bms ?: state.bms,
                settings = decoded.settings ?: state.settings,
                connectionState = newConnectionState,
                capabilities = mergedCapabilities,
                consecutiveDecodeErrors = 0,
                consecutiveBleErrors = 0,
                eventLogEntries = newLogEntries
            ),
            effects = effects
        )
    }

    private fun reduceBleError(state: WcmState): WcmTransition {
        val newCount = state.consecutiveBleErrors + 1
        val now = currentTimeMillis()
        Logger.w(TAG, "BLE error #$newCount")
        val bleErrorEffect = WcmEffect.LogConnectionError(
            ConnectionErrorEvent.BleError(timestampMs = now, consecutiveCount = newCount)
        )
        if (newCount >= MAX_BLE_ERRORS) {
            val address = getCurrentAddress(state)
            val lostState = ConnectionState.ConnectionLost(
                address = address ?: "",
                reason = "Too many BLE errors"
            )
            val transitionEffect = WcmEffect.LogConnectionError(
                ConnectionErrorEvent.StateTransition(
                    timestampMs = now,
                    from = state.connectionState.statusText,
                    to = lostState.statusText,
                    reason = "Too many BLE errors"
                )
            )
            return WcmTransition(
                state.copy(
                    consecutiveBleErrors = newCount,
                    connectionState = lostState
                ),
                listOf(bleErrorEffect, transitionEffect)
            )
        }
        return WcmTransition(
            state.copy(consecutiveBleErrors = newCount),
            listOf(bleErrorEffect)
        )
    }

    private fun reduceKeepAliveTick(state: WcmState): WcmTransition {
        val command = state.decoder?.getKeepAliveCommand()
            ?: return WcmTransition(state)
        return WcmTransition(state, listOf(WcmEffect.DispatchCommands(listOf(command))))
    }

    private fun reduceDataTimeout(state: WcmState, event: WheelEvent.DataTimeout): WcmTransition {
        // Don't stop timers — keep-alive must continue so polling wheels
        // can recover when signal returns. The timeout tracker continues
        // monitoring and will naturally reset via onDataReceived().
        val now = currentTimeMillis()
        val lostState = ConnectionState.ConnectionLost(
            address = event.address,
            reason = "No data received"
        )
        return WcmTransition(
            state.copy(connectionState = lostState),
            listOf(
                WcmEffect.LogConnectionError(ConnectionErrorEvent.DataTimeout(
                    timestampMs = now, address = event.address
                )),
                WcmEffect.LogConnectionError(ConnectionErrorEvent.StateTransition(
                    timestampMs = now,
                    from = state.connectionState.statusText,
                    to = lostState.statusText,
                    reason = "No data received"
                ))
            )
        )
    }

    private fun reduceSendCommand(state: WcmState, event: WheelEvent.SendCommand): WcmTransition {
        return WcmTransition(state, listOf(
            WcmEffect.DispatchCommands(
                listOf(event.command),
                decoder = state.decoder,
                decoderState = state.decoderState
            )
        ))
    }

    private fun reduceConfigUpdated(state: WcmState, event: WheelEvent.ConfigUpdated): WcmTransition {
        return WcmTransition(state.copy(decoderConfig = event.config))
    }

    // ==================== Reducer Helpers ====================

    /**
     * Compute new state + effects for setting up a decoder.
     * Shared by connect, services discovered, and wheel type detected reducers.
     */
    private fun setupDecoderTransition(state: WcmState, wheelType: WheelType): Pair<WcmState, List<WcmEffect>> {
        val effects = mutableListOf<WcmEffect>()
        state.decoder?.let { effects.add(WcmEffect.ResetDecoder(it)) }

        val decoder = decoderFactory.createDecoder(wheelType)
        val newState = state.copy(
            decoder = decoder,
            identity = state.identity.copy(wheelType = wheelType)
        )

        decoder?.getInitCommands()?.let { cmds ->
            if (cmds.isNotEmpty()) effects.add(WcmEffect.DispatchCommands(cmds))
        }

        val intervalMs = decoder?.keepAliveIntervalMs ?: 0L
        if (intervalMs > 0) {
            effects.add(WcmEffect.StartKeepAlive(intervalMs))
        }

        return newState to effects
    }

    private fun WheelConnectionInfo.toConfigureBleEffect() = WcmEffect.ConfigureBle(
        readServiceUuid = readServiceUuid,
        readCharUuid = readCharacteristicUuid,
        writeServiceUuid = writeServiceUuid,
        writeCharUuid = writeCharacteristicUuid
    )

    /**
     * Transition to Failed state with full cleanup.
     * Stops timers, cancels commands, and resets the decoder so that
     * Failed state is always clean — no leaked resources.
     */
    private fun transitionToFailed(state: WcmState, error: String, address: String?): WcmTransition {
        val failedState = ConnectionState.Failed(error = error, address = address)
        val effects = buildList {
            add(WcmEffect.StopTimers)
            add(WcmEffect.CancelCommands)
            state.decoder?.let { add(WcmEffect.ResetDecoder(it)) }
            add(WcmEffect.BleDisconnect)
            add(WcmEffect.LogConnectionError(ConnectionErrorEvent.StateTransition(
                timestampMs = currentTimeMillis(),
                from = state.connectionState.statusText,
                to = failedState.statusText,
                reason = error
            )))
        }
        return WcmTransition(
            state = WcmState(
                decoderConfig = state.decoderConfig,
                connectionState = failedState
            ),
            effects = effects
        )
    }

    private fun getCurrentAddress(state: WcmState): String? {
        return when (val cs = state.connectionState) {
            is ConnectionState.Connecting -> cs.address
            is ConnectionState.DiscoveringServices -> cs.address
            is ConnectionState.Connected -> cs.address
            is ConnectionState.ConnectionLost -> cs.address
            else -> null
        }
    }

    // ==================== Effect Executor ====================

    private suspend fun executeEffects(effects: List<WcmEffect>) {
        for (effect in effects) {
            when (effect) {
                is WcmEffect.BleConnect -> {
                    launchBleConnect(effect.address)
                }
                is WcmEffect.BleDisconnect -> {
                    bleManager.disconnect()
                }
                is WcmEffect.DispatchCommands -> {
                    val decoder = effect.decoder
                    val decoderState = effect.decoderState
                    commandScheduler.scheduleSequence {
                        effect.commands.forEach { cmd ->
                            dispatchCommand(cmd, decoder, decoderState)
                        }
                    }
                }
                is WcmEffect.StartKeepAlive -> {
                    keepAliveTimer.start(
                        intervalMs = effect.intervalMs,
                        initialDelayMs = effect.intervalMs
                    ) {
                        events.send(WheelEvent.KeepAliveTick)
                    }
                }
                is WcmEffect.StartDataTimeout -> {
                    dataTimeoutTracker.start(timeoutMs = effect.timeoutMs) {
                        events.send(WheelEvent.DataTimeout(effect.address))
                    }
                }
                is WcmEffect.StopTimers -> {
                    keepAliveTimer.stop()
                    dataTimeoutTracker.stop()
                }
                is WcmEffect.CancelBleConnect -> {
                    // Only emitted when state is Connecting, so there should
                    // be an active connect job to cancel.
                    bleConnectJob?.cancelAndJoin()
                    bleConnectJob = null
                    bleManager.disconnect()
                }
                is WcmEffect.CancelCommands -> {
                    commandScheduler.cancelAll()
                }
                is WcmEffect.CapturePacket -> {
                    captureCallback?.invoke(effect.data, effect.direction, effect.annotation)
                }
                is WcmEffect.NotifyUnhandled -> {
                    unhandledCallback?.invoke(effect.reason, effect.frameData)
                }
                is WcmEffect.ResetDecoder -> {
                    effect.decoder.reset()
                }
                is WcmEffect.ConfigureBle -> {
                    bleManager.configureForWheel(
                        effect.readServiceUuid, effect.readCharUuid,
                        effect.writeServiceUuid, effect.writeCharUuid
                    )
                }
                is WcmEffect.LogConnectionError -> {
                    errorLogCallback?.invoke(effect.event)
                }
            }
        }
    }

    // ==================== Effect Helpers ====================

    private fun launchBleConnect(address: String) {
        bleConnectJob = scope.launch(dispatcher) {
            try {
                val success = kotlinx.coroutines.withTimeoutOrNull(BLE_CONNECT_TIMEOUT_MS) {
                    bleManager.connect(address)
                }
                if (success == null) {
                    events.send(WheelEvent.BleConnectResult(
                        success = false,
                        address = address,
                        error = "Connection timed out"
                    ))
                } else {
                    events.send(WheelEvent.BleConnectResult(success, address))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by disconnect — don't send result
            } catch (e: Exception) {
                events.send(WheelEvent.BleConnectResult(
                    success = false,
                    address = address,
                    error = e.message ?: "Connection failed"
                ))
            }
        }
    }

    /**
     * Dispatch a command to the BLE layer.
     *
     * The [decoder] and [state] are captured by the reducer at effect creation
     * time, so buildCommand() uses an immutable state snapshot from when the
     * command was dispatched — not whatever mutable decoder state exists at
     * execution time. This eliminates the need for locks in decoders whose
     * buildCommand() only reads from [state].
     */
    private suspend fun dispatchCommand(command: WheelCommand, decoder: WheelDecoder?, state: DecoderState?) {
        when (command) {
            is WheelCommand.SendBytes -> sendBleData(command.data)
            is WheelCommand.SendDelayed -> sendBleData(command.data, command.delayMs)
            else -> {
                val rawCommands = decoder?.buildCommand(command, state) ?: return
                for (cmd in rawCommands) {
                    when (cmd) {
                        is WheelCommand.SendBytes -> sendBleData(cmd.data)
                        is WheelCommand.SendDelayed -> sendBleData(cmd.data, cmd.delayMs)
                        else -> {} // prevent recursion
                    }
                }
            }
        }
    }

    private suspend fun sendBleData(data: ByteArray, delayMs: Long = 0) {
        if (delayMs > 0) delay(delayMs)
        captureCallback?.invoke(data, BlePacketDirection.TX, "")
        bleManager.write(data)
    }

    companion object {
        private const val TAG = "WheelConnectionManager"
        /** Consecutive BLE errors before triggering ConnectionLost. */
        internal const val MAX_BLE_ERRORS = 50
        /** Timeout for BLE connect phase (30 seconds). */
        private const val BLE_CONNECT_TIMEOUT_MS = 30_000L
    }
}

/**
 * Platform-specific BLE manager.
 * Implements [BleManagerPort] via expect/actual for each platform.
 */
expect class BleManager : BleManagerPort {
    override val connectionState: StateFlow<ConnectionState>
    override val bluetoothState: StateFlow<BluetoothAdapterState>

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
