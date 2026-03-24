package org.freewheel.core.service

import org.freewheel.core.alarm.AlarmChecker
import org.freewheel.core.alarm.AlarmConfig
import org.freewheel.core.alarm.AlarmResult
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.replay.BleCaptureReader
import org.freewheel.core.replay.ReplayEngine
import org.freewheel.core.replay.ReplayState
import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.DefaultWheelDecoderFactory
import org.freewheel.core.domain.SettingsCommandId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
// All callers reach demoScope via @MainActor (Swift), so access is main-thread-serialized.
private var _demoScope: CoroutineScope? = null
private val demoScope: CoroutineScope
    get() {
        val scope = _demoScope
        if (scope == null || !scope.isActive) {
            _demoScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        return _demoScope!!
    }

/**
 * Handle for cancelling a Flow observation started from Swift.
 * Call [close] to stop collecting and free resources.
 */
class FlowObservation(private val scope: CoroutineScope) {
    fun close() { scope.cancel() }
}

/**
 * iOS helper for WheelConnectionManager.
 * Handles CoroutineScope creation and provides Swift-friendly accessors.
 */
object WheelConnectionManagerHelper {

    /**
     * Cancel the shared demoScope.
     * It is lazily recreated on next use.
     * Call this when the app is backgrounded or the manager is torn down.
     */
    fun destroy() {
        _demoScope?.cancel()
        _demoScope = null
    }

    /**
     * Create a WheelConnectionManager with default configuration.
     * The scope is created internally and tied to the main dispatcher.
     */
    fun create(bleManager: BleManager): WheelConnectionManager {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        return WheelConnectionManager(
            bleManager = bleManager,
            decoderFactory = DefaultWheelDecoderFactory(),
            scope = scope
        )
    }

    /**
     * Update decoder config. Swift constructs the full DecoderConfig so that
     * adding a new required field causes a compile error on both platforms.
     */
    fun updateDecoderConfig(manager: WheelConnectionManager, config: DecoderConfig) {
        manager.updateConfig(config)
    }

    /**
     * Get current connection state from a WheelConnectionManager.
     * Swift-friendly accessor that avoids StateFlow.value access.
     */
    fun getConnectionState(manager: WheelConnectionManager): ConnectionState {
        return manager.connectionState.value
    }

    /**
     * Check if the manager is currently connected.
     */
    fun isConnected(manager: WheelConnectionManager): Boolean {
        return manager.connectionState.value.isConnected
    }

    // MARK: - Granular Sub-state Getters

    fun getTelemetryState(manager: WheelConnectionManager): TelemetryState? {
        return manager.telemetryState.value
    }

    fun getSettingsState(manager: WheelConnectionManager): WheelSettings {
        return manager.settingsState.value
    }

    fun getIdentityState(manager: WheelConnectionManager): WheelIdentity {
        return manager.identityState.value
    }

    fun getBmsState(manager: WheelConnectionManager): BmsState {
        return manager.bmsState.value
    }

    fun sendBeep(manager: WheelConnectionManager) {
        manager.wheelBeep()
    }

    fun sendToggleLight(manager: WheelConnectionManager, enabled: Boolean) {
        manager.toggleLight(enabled)
    }

    fun sendSetPedalsMode(manager: WheelConnectionManager, mode: Int) {
        manager.setPedalsMode(mode)
    }

    // MARK: - Lighting

    fun sendSetLightMode(manager: WheelConnectionManager, mode: Int) {
        manager.setLightMode(mode)
    }

    fun sendSetLed(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setLed(enabled)
    }

    fun sendSetLedMode(manager: WheelConnectionManager, mode: Int) {
        manager.setLedMode(mode)
    }

    fun sendSetStrobeMode(manager: WheelConnectionManager, mode: Int) {
        manager.setStrobeMode(mode)
    }

    fun sendSetTailLight(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setTailLight(enabled)
    }

    fun sendSetDrl(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setDrl(enabled)
    }

    fun sendSetLedColor(manager: WheelConnectionManager, value: Int, ledNum: Int) {
        manager.setLedColor(value, ledNum)
    }

    fun sendSetLightBrightness(manager: WheelConnectionManager, value: Int) {
        manager.setLightBrightness(value)
    }

    // MARK: - Speed & Alarms

    fun sendSetMaxSpeed(manager: WheelConnectionManager, speed: Int) {
        manager.setMaxSpeed(speed)
    }

    fun sendSetAlarmSpeed(manager: WheelConnectionManager, speed: Int, num: Int) {
        manager.setAlarmSpeed(speed, num)
    }

    fun sendSetAlarmEnabled(manager: WheelConnectionManager, enabled: Boolean, num: Int) {
        manager.setAlarmEnabled(enabled, num)
    }

    fun sendSetLimitedMode(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setLimitedMode(enabled)
    }

    fun sendSetLimitedSpeed(manager: WheelConnectionManager, speed: Int) {
        manager.setLimitedSpeed(speed)
    }

    fun sendSetAlarmMode(manager: WheelConnectionManager, mode: Int) {
        manager.setAlarmMode(mode)
    }

    fun sendSetKingsongAlarms(manager: WheelConnectionManager, a1: Int, a2: Int, a3: Int, max: Int) {
        manager.setKingsongAlarms(a1, a2, a3, max)
    }

    fun sendRequestAlarmSettings(manager: WheelConnectionManager) {
        manager.requestAlarmSettings()
    }

    // MARK: - Ride Modes

    fun sendSetHandleButton(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setHandleButton(enabled)
    }

    fun sendSetBrakeAssist(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setBrakeAssist(enabled)
    }

    fun sendSetTransportMode(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setTransportMode(enabled)
    }

    fun sendSetRideMode(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setRideMode(enabled)
    }

    fun sendSetGoHomeMode(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setGoHomeMode(enabled)
    }

    fun sendSetFancierMode(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setFancierMode(enabled)
    }

    fun sendSetRollAngleMode(manager: WheelConnectionManager, mode: Int) {
        manager.setRollAngleMode(mode)
    }

    // MARK: - Audio

    fun sendSetMute(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setMute(enabled)
    }

    fun sendSetSpeakerVolume(manager: WheelConnectionManager, volume: Int) {
        manager.setSpeakerVolume(volume)
    }

    fun sendSetBeeperVolume(manager: WheelConnectionManager, volume: Int) {
        manager.setBeeperVolume(volume)
    }

    // MARK: - Thermal

    fun sendSetFanQuiet(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setFanQuiet(enabled)
    }

    fun sendSetFan(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setFan(enabled)
    }

    // MARK: - Pedal Tuning

    fun sendSetPedalTilt(manager: WheelConnectionManager, angle: Int) {
        manager.setPedalTilt(angle)
    }

    fun sendSetPedalSensitivity(manager: WheelConnectionManager, sensitivity: Int) {
        manager.setPedalSensitivity(sensitivity)
    }

    // MARK: - System

    fun sendCalibrate(manager: WheelConnectionManager) {
        manager.calibrate()
    }

    fun sendPowerOff(manager: WheelConnectionManager) {
        manager.powerOff()
    }

    fun sendSetLock(manager: WheelConnectionManager, locked: Boolean) {
        manager.setLock(locked)
    }

    fun sendSetVeteranLock(manager: WheelConnectionManager, locked: Boolean, password: String) {
        manager.setVeteranLock(locked, password)
    }

    fun sendResetTrip(manager: WheelConnectionManager) {
        manager.resetTrip()
    }

    fun sendSetMilesMode(manager: WheelConnectionManager, enabled: Boolean) {
        manager.setMilesMode(enabled)
    }

    // MARK: - Generic Command Dispatch

    fun executeCommand(manager: WheelConnectionManager, commandId: SettingsCommandId, intValue: Int = 0, boolValue: Boolean = false) {
        manager.executeCommand(commandId, intValue, boolValue)
    }

    // MARK: - BLE Capture Callback

    /**
     * Set a capture callback on the WheelConnectionManager.
     * Swift-friendly: passes direction as a String ("RX" or "TX") and decode annotation.
     */
    fun setCaptureCallback(manager: WheelConnectionManager, callback: ((ByteArray, String, String) -> Unit)?) {
        if (callback == null) {
            manager.captureCallback = null
        } else {
            manager.captureCallback = { data, direction, annotation ->
                callback(data, direction.name, annotation)
            }
        }
    }

    // MARK: - Unhandled Frame Callback

    /**
     * Set an unhandled frame callback on the WheelConnectionManager.
     * Swift-friendly: passes reason as String and frameData as ByteArray.
     */
    fun setUnhandledCallback(manager: WheelConnectionManager, callback: ((String, ByteArray) -> Unit)?) {
        if (callback == null) {
            manager.unhandledCallback = null
        } else {
            manager.unhandledCallback = { reason, frameData ->
                callback(reason, frameData)
            }
        }
    }

    // MARK: - Error Log Callback

    /**
     * Set an error log callback on the WheelConnectionManager.
     * Swift-friendly: passes the pre-formatted CSV row string.
     * The sessionStartMs is used to compute elapsed time in each row.
     */
    fun setErrorLogCallback(
        manager: WheelConnectionManager,
        sessionStartMs: Long,
        callback: ((String) -> Unit)?
    ) {
        if (callback == null) {
            manager.errorLogCallback = null
        } else {
            manager.errorLogCallback = { event ->
                val csvRow = org.freewheel.core.logging.ConnectionErrorCsvFormatter.formatEvent(event, sessionStartMs)
                callback(csvRow)
            }
        }
    }

    /**
     * Format an error log header comment for the given session.
     */
    fun formatErrorLogHeader(wheelType: String, wheelName: String, address: String, connectTimeMs: Long): String {
        return org.freewheel.core.logging.ConnectionErrorCsvFormatter.headerComment(wheelType, wheelName, address, connectTimeMs)
    }

    /**
     * Format an error log footer comment for the given session.
     */
    fun formatErrorLogFooter(disconnectTimeMs: Long, disconnectReason: String, totalFramesDecoded: Int): String {
        return org.freewheel.core.logging.ConnectionErrorCsvFormatter.footerComment(disconnectTimeMs, disconnectReason, totalFramesDecoded, null)
    }

    // MARK: - Auto-Connect Manager

    /**
     * Create an AutoConnectManager wired to the given WheelConnectionManager.
     * The connect lambda and scope are set up internally — Swift never touches them.
     */
    fun createAutoConnectManager(manager: WheelConnectionManager): AutoConnectManager {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        return AutoConnectManager(
            connectionState = manager.connectionState,
            connect = { address -> manager.connect(address) },
            scope = scope,
            dispatcher = Dispatchers.Main
        )
    }

    /**
     * Swift-friendly wrapper: attempt startup connect with default timeout.
     */
    fun attemptStartupConnect(manager: AutoConnectManager, address: String, timeoutMs: Long = 10_000) {
        manager.attemptStartupConnect(address, timeoutMs)
    }

    /**
     * Swift-friendly wrapper: start reconnecting with default backoff.
     * Kotlin default parameters don't export to Swift.
     */
    fun startReconnecting(manager: AutoConnectManager, address: String) {
        manager.startReconnecting(address)
    }

    /**
     * Swift-friendly wrapper: stop all auto-connect activity.
     */
    fun stopAutoConnect(manager: AutoConnectManager) {
        manager.stop()
    }

    /**
     * Swift-friendly wrapper: destroy and clean up.
     */
    fun destroyAutoConnect(manager: AutoConnectManager) {
        manager.destroy()
    }

    fun getIsAutoConnecting(manager: AutoConnectManager): Boolean {
        return manager.isAutoConnecting.value
    }

    fun getReconnectState(manager: AutoConnectManager): AutoConnectManager.ReconnectState {
        return manager.reconnectState.value
    }

    fun isReconnectIdle(state: AutoConnectManager.ReconnectState): Boolean {
        return state is AutoConnectManager.ReconnectState.Idle
    }

    fun isReconnectWaiting(state: AutoConnectManager.ReconnectState): Boolean {
        return state is AutoConnectManager.ReconnectState.Waiting
    }

    fun isReconnectAttempting(state: AutoConnectManager.ReconnectState): Boolean {
        return state is AutoConnectManager.ReconnectState.Attempting
    }

    fun reconnectAttemptNumber(state: AutoConnectManager.ReconnectState): Int {
        return when (state) {
            is AutoConnectManager.ReconnectState.Waiting -> state.attempt
            is AutoConnectManager.ReconnectState.Attempting -> state.attempt
            else -> 0
        }
    }

    fun reconnectNextRetryMs(state: AutoConnectManager.ReconnectState): Long {
        return when (state) {
            is AutoConnectManager.ReconnectState.Waiting -> state.nextRetryMs
            else -> 0
        }
    }

    // MARK: - Bluetooth State

    /**
     * Get the current Bluetooth adapter state.
     */
    fun getBluetoothState(bleManager: BleManager): BluetoothAdapterState {
        return bleManager.bluetoothState.value
    }

    /**
     * Observe Bluetooth adapter state changes.
     */
    fun observeBluetoothState(bleManager: BleManager, onChange: (BluetoothAdapterState) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { bleManager.bluetoothState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    // MARK: - Flow Observers
    //
    // These methods launch coroutines that collect Kotlin StateFlows and invoke
    // Swift callbacks on the main thread (Dispatchers.Main = main dispatch queue).
    // Return a FlowObservation handle — call close() to stop collecting.

    fun observeConnectionState(manager: WheelConnectionManager, onChange: (ConnectionState) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.connectionState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeTelemetryState(manager: WheelConnectionManager, onChange: (TelemetryState?) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.telemetryState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeSettingsState(manager: WheelConnectionManager, onChange: (WheelSettings) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.settingsState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeIdentityState(manager: WheelConnectionManager, onChange: (WheelIdentity) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.identityState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeBmsState(manager: WheelConnectionManager, onChange: (BmsState) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.bmsState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeCapabilities(manager: WheelConnectionManager, onChange: (CapabilitySet) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.capabilities.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeEventLogEntries(manager: WheelConnectionManager, onChange: (List<org.freewheel.core.domain.EventLogEntry>) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.eventLogEntries.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun sendRequestEventLog(manager: WheelConnectionManager) {
        manager.clearEventLog()
        manager.requestEventLog()
    }

    fun sendClearEventLog(manager: WheelConnectionManager) {
        manager.clearEventLog()
    }

    fun observeAutoConnecting(manager: AutoConnectManager, onChange: (Boolean) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.isAutoConnecting.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeReconnectState(manager: AutoConnectManager, onChange: (AutoConnectManager.ReconnectState) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { manager.reconnectState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeDemoTelemetry(provider: DemoDataProvider, onChange: (TelemetryState) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { provider.telemetryState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeDemoIdentity(provider: DemoDataProvider, onChange: (WheelIdentity) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { provider.identityState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeDemoBms(provider: DemoDataProvider, onChange: (BmsState) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { provider.bmsState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeDemoSettings(provider: DemoDataProvider, onChange: (WheelSettings) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { provider.settingsState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    // MARK: - Demo Data Provider

    fun createDemoProvider(): DemoDataProvider {
        return DemoDataProvider()
    }

    fun startDemo(provider: DemoDataProvider) {
        provider.start(demoScope)
    }

    fun stopDemo(provider: DemoDataProvider) {
        provider.stop()
    }

    fun getDemoTelemetry(provider: DemoDataProvider): TelemetryState {
        return provider.telemetryState.value
    }

    fun getDemoIdentity(provider: DemoDataProvider): WheelIdentity {
        return provider.identityState.value
    }

    fun getDemoBms(provider: DemoDataProvider): BmsState {
        return provider.bmsState.value
    }

    // MARK: - Alarm Checker

    fun createAlarmChecker(): AlarmChecker {
        return AlarmChecker()
    }

    fun checkAlarms(
        checker: AlarmChecker,
        telemetry: TelemetryState?,
        config: AlarmConfig,
        currentTimeMs: Long
    ): AlarmResult? {
        return telemetry?.let { checker.check(it, config, currentTimeMs) }
    }

    fun resetAlarmChecker(checker: AlarmChecker) {
        checker.reset()
    }

    // MARK: - Replay Engine

    fun createReplayEngine(): ReplayEngine {
        return ReplayEngine()
    }

    fun loadCapture(engine: ReplayEngine, csvContent: String): Boolean {
        val reader = BleCaptureReader()
        val capture = reader.parse(csvContent) ?: return false
        return engine.load(capture)
    }

    fun startReplay(engine: ReplayEngine) {
        engine.start(demoScope)
    }

    fun pauseReplay(engine: ReplayEngine) {
        engine.pause()
    }

    fun resumeReplay(engine: ReplayEngine) {
        engine.resume(demoScope)
    }

    fun stopReplay(engine: ReplayEngine) {
        engine.stop()
    }

    fun seekReplay(engine: ReplayEngine, progress: Float) {
        engine.seekTo(progress, demoScope)
    }

    fun setReplaySpeed(engine: ReplayEngine, speed: Float) {
        engine.setSpeed(speed)
    }

    fun getReplayStateName(engine: ReplayEngine): String {
        return engine.replayState.value.name
    }

    fun getReplayProgress(engine: ReplayEngine): Float {
        return engine.position.value.progress
    }

    fun getReplayCurrentTimeMs(engine: ReplayEngine): Long {
        return engine.position.value.currentTimeMs
    }

    fun getReplayTotalDurationMs(engine: ReplayEngine): Long {
        return engine.position.value.totalDurationMs
    }

    fun getReplayPacketIndex(engine: ReplayEngine): Int {
        return engine.position.value.packetIndex
    }

    fun getReplayTotalPackets(engine: ReplayEngine): Int {
        return engine.position.value.totalPackets
    }

    fun getReplaySpeed(engine: ReplayEngine): Float {
        return engine.speed.value
    }

    fun getReplayWheelTypeName(engine: ReplayEngine): String {
        return engine.captureHeader.value?.wheelTypeName ?: ""
    }

    fun getReplayWheelName(engine: ReplayEngine): String {
        return engine.captureHeader.value?.wheelName ?: ""
    }

    fun observeReplayTelemetry(engine: ReplayEngine, onChange: (TelemetryState) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { engine.telemetryState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeReplayIdentity(engine: ReplayEngine, onChange: (WheelIdentity) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { engine.identityState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeReplayBms(engine: ReplayEngine, onChange: (BmsState) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { engine.bmsState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeReplaySettings(engine: ReplayEngine, onChange: (WheelSettings) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { engine.settingsState.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    fun observeReplayState(engine: ReplayEngine, onChange: (String) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { engine.replayState.collect { onChange(it.name) } }
        return FlowObservation(scope)
    }

    fun observeReplayPosition(engine: ReplayEngine, onChange: (Float, Long, Long, Int, Int) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            engine.position.collect { pos ->
                onChange(pos.progress, pos.currentTimeMs, pos.totalDurationMs, pos.packetIndex, pos.totalPackets)
            }
        }
        return FlowObservation(scope)
    }

    fun observeReplaySpeed(engine: ReplayEngine, onChange: (Float) -> Unit): FlowObservation {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch { engine.speed.collect { onChange(it) } }
        return FlowObservation(scope)
    }

    /**
     * Swift-callable factory for AlarmConfig.
     * Kotlin data class default params don't export to Swift, so all 18 params are explicit.
     */
    fun createAlarmConfig(
        pwmBasedAlarms: Boolean,
        alarmFactor1: Int,
        alarmFactor2: Int,
        warningPwm: Int,
        warningSpeed: Int,
        warningSpeedPeriod: Int,
        alarm1Speed: Int,
        alarm1Battery: Int,
        alarm2Speed: Int,
        alarm2Battery: Int,
        alarm3Speed: Int,
        alarm3Battery: Int,
        alarmCurrent: Int,
        alarmPhaseCurrent: Int,
        alarmTemperature: Int,
        alarmMotorTemperature: Int,
        alarmBattery: Int,
        alarmWheel: Boolean
    ): AlarmConfig {
        return AlarmConfig(
            pwmBasedAlarms = pwmBasedAlarms,
            alarmFactor1 = alarmFactor1,
            alarmFactor2 = alarmFactor2,
            warningPwm = warningPwm,
            warningSpeed = warningSpeed,
            warningSpeedPeriod = warningSpeedPeriod,
            alarm1Speed = alarm1Speed,
            alarm1Battery = alarm1Battery,
            alarm2Speed = alarm2Speed,
            alarm2Battery = alarm2Battery,
            alarm3Speed = alarm3Speed,
            alarm3Battery = alarm3Battery,
            alarmCurrent = alarmCurrent,
            alarmPhaseCurrent = alarmPhaseCurrent,
            alarmTemperature = alarmTemperature,
            alarmMotorTemperature = alarmMotorTemperature,
            alarmBattery = alarmBattery,
            alarmWheel = alarmWheel
        )
    }
}
