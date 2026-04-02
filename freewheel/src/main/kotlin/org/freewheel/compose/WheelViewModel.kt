package org.freewheel.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.freewheel.AppConfig
import org.freewheel.core.utils.ByteUtils
import org.freewheel.core.utils.PlatformDateFormatter
import org.freewheel.core.utils.StringUtil
import org.freewheel.core.logging.BleCaptureLogger
import org.freewheel.core.logging.BleCaptureMetadata
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.logging.ConnectionErrorCsvFormatter
import org.freewheel.core.logging.DiagnosticSnapshotBuilder
import org.freewheel.core.logging.UnhandledFrameCollector
import org.freewheel.core.logging.UnhandledFrameFormatter
import org.freewheel.core.logging.GpsLocation
import org.freewheel.core.logging.RideCsvEditor
import org.freewheel.core.logging.RideLogger
import org.freewheel.core.telemetry.ChartTimeRange
import org.freewheel.core.telemetry.TelemetryBuffer
import org.freewheel.core.telemetry.TelemetryHistory
import org.freewheel.core.telemetry.TelemetrySample
import org.freewheel.core.service.AutoConnectManager
import org.freewheel.core.service.AutoTorchEngine
import org.freewheel.core.utils.RangeEstimator
import org.freewheel.core.service.BleDevice
import org.freewheel.core.service.BleManagerPort
import org.freewheel.core.service.BluetoothAdapterState
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.service.DemoDataProvider
import org.freewheel.core.service.WheelConnectionManagerPort
import org.freewheel.core.replay.BleCaptureReader
import org.freewheel.core.replay.ReplayEngine
import org.freewheel.core.replay.ReplayPosition
import org.freewheel.core.replay.ReplayState
import org.freewheel.core.ble.BleUuids
import org.freewheel.core.charger.ChargerConnectionManagerPort
import org.freewheel.core.charger.ChargerState
import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.AlarmAction
import org.freewheel.core.domain.AlarmType
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.ChargerProfile
import org.freewheel.core.domain.ChargerProfileStore
import org.freewheel.core.domain.WheelProfile
import org.freewheel.core.domain.WheelProfileStore
import org.freewheel.core.domain.PreferenceDefaults
import org.freewheel.core.domain.PreferenceKeys
import org.freewheel.core.domain.dashboard.DashboardLayout
import org.freewheel.core.domain.dashboard.DashboardLayoutSerializer
import org.freewheel.core.domain.dashboard.DashboardPreset
import org.freewheel.core.domain.dashboard.NavigationConfig
import org.freewheel.core.domain.dashboard.NavigationConfigSerializer
import org.freewheel.core.protocol.DecoderConfig
import android.content.SharedPreferences
import org.freewheel.core.alarm.AlarmChecker
import org.freewheel.core.alarm.AlarmConfig
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import org.freewheel.core.telemetry.TelemetryFileIO
import org.freewheel.data.TripDataDbEntry
import org.freewheel.data.TripRepository
import android.content.Context
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.core.content.edit
import org.freewheel.compose.service.AlarmHandler
import org.freewheel.compose.service.WheelServiceContract

@OptIn(ExperimentalCoroutinesApi::class)
class WheelViewModel(
    application: Application,
    val appConfig: AppConfig,
    private val prefs: SharedPreferences,
    private val vibrator: Vibrator?,
    private val tripRepository: TripRepository,
    private val rideLogger: RideLogger,
    private val captureLogger: BleCaptureLogger,
    private val telemetryFileIO: TelemetryFileIO,
    val profileStore: WheelProfileStore,
    val chargerProfileStore: ChargerProfileStore,
    private val demoDataProvider: DemoDataProvider,
    private val alarmChecker: AlarmChecker = AlarmChecker(),
    val telemetryBuffer: TelemetryBuffer = TelemetryBuffer()
) : AndroidViewModel(application) {

    private companion object {
        const val RIDE_PAUSE_TIMEOUT_MS = 3_600_000L // 1 hour
    }

    // Service references (set via attachService/detachService)
    private var wheelService: WheelServiceContract? = null
    private var connectionManager: WheelConnectionManagerPort? = null
    private var bleManager: BleManagerPort? = null
    private var stateCollectionJob: Job? = null
    private var connectionCollectionJob: Job? = null
    private var capabilitiesCollectionJob: Job? = null

    // Charger service references
    private var chargerConnectionManager: ChargerConnectionManagerPort? = null
    private var chargerBleManager: BleManagerPort? = null
    private var chargerStateCollectionJob: Job? = null
    private var chargerConnectionCollectionJob: Job? = null

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Charger state
    private val _chargerState = MutableStateFlow(ChargerState())
    val chargerState: StateFlow<ChargerState> = _chargerState.asStateFlow()

    private val _chargerConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val chargerConnectionState: StateFlow<ConnectionState> = _chargerConnectionState.asStateFlow()

    // Bluetooth adapter state (set by ComposeActivity before and after service binding)
    private val _bluetoothState = MutableStateFlow(BluetoothAdapterState.UNKNOWN)
    val bluetoothState: StateFlow<BluetoothAdapterState> = _bluetoothState.asStateFlow()

    // Track the connect coroutine so we can cancel the scan-stop + connect call
    private var connectJob: Job? = null

    // Data source: LIVE (real wheel), DEMO, or REPLAY
    enum class WheelDataSource { LIVE, DEMO, REPLAY }
    private val _dataSource = MutableStateFlow(WheelDataSource.LIVE)

    // Backward-compatible isDemo: true for both DEMO and REPLAY (hides wheel controls)
    val isDemo: StateFlow<Boolean> = _dataSource.map { it != WheelDataSource.LIVE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val dataSource: StateFlow<WheelDataSource> = _dataSource.asStateFlow()

    // Replay engine
    val replayEngine = ReplayEngine()
    private val captureReader = BleCaptureReader()

    // Scanning
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Discovered devices
    data class DiscoveredDevice(
        val name: String,
        val address: String,
        val rssi: Int
    )
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    // Real sub-states from WheelConnectionManager (null = no data from wheel yet)
    private val _realTelemetry = MutableStateFlow<TelemetryState?>(null)
    private val _realIdentity = MutableStateFlow(WheelIdentity())
    private val _realBms = MutableStateFlow(BmsState())
    private val _realSettings = MutableStateFlow<WheelSettings>(WheelSettings.None)

    // Capabilities from WheelConnectionManager
    private val _capabilities = MutableStateFlow(CapabilitySet())
    val capabilities: StateFlow<CapabilitySet> = _capabilities.asStateFlow()

    // Granular sub-state flows — combine real, demo, and replay sources.
    // UI components observe only the sub-flow they need, so telemetry updates
    // (10+ Hz) don't trigger recomposition in settings/identity/BMS screens.

    val telemetryState: StateFlow<TelemetryState> = combine(
        _dataSource,
        _realTelemetry,
        demoDataProvider.telemetryState,
        replayEngine.telemetryState
    ) { source, real, demo, replay ->
        when (source) {
            WheelDataSource.LIVE -> real ?: TelemetryState()
            WheelDataSource.DEMO -> demo
            WheelDataSource.REPLAY -> replay
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TelemetryState())

    /**
     * Telemetry for side-effects (alarms, logging, buffering, auto-torch).
     * Returns null when LIVE and no data received yet; non-null for DEMO/REPLAY.
     * Side-effect collectors use filterNotNull() to skip the "no data" state.
     */
    private val activeTelemetryOrNull = _dataSource.flatMapLatest { source ->
        when (source) {
            WheelDataSource.LIVE -> _realTelemetry
            WheelDataSource.DEMO -> demoDataProvider.telemetryState
            WheelDataSource.REPLAY -> replayEngine.telemetryState
        }
    }

    val identityState: StateFlow<WheelIdentity> = combine(
        _dataSource,
        _realIdentity,
        demoDataProvider.identityState,
        replayEngine.identityState
    ) { source, real, demo, replay ->
        when (source) {
            WheelDataSource.LIVE -> real
            WheelDataSource.DEMO -> demo
            WheelDataSource.REPLAY -> replay
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WheelIdentity())

    val bmsState: StateFlow<BmsState> = combine(
        _dataSource,
        _realBms,
        demoDataProvider.bmsState,
        replayEngine.bmsState
    ) { source, real, demo, replay ->
        when (source) {
            WheelDataSource.LIVE -> real
            WheelDataSource.DEMO -> demo
            WheelDataSource.REPLAY -> replay
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BmsState())

    val settingsState: StateFlow<WheelSettings> = combine(
        _dataSource,
        _realSettings,
        demoDataProvider.settingsState,
        replayEngine.settingsState
    ) { source, real, demo, replay ->
        when (source) {
            WheelDataSource.LIVE -> real
            WheelDataSource.DEMO -> demo
            WheelDataSource.REPLAY -> replay
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WheelSettings.None)

    // Alarms
    private val _activeAlarms = MutableStateFlow<Set<AlarmType>>(emptySet())
    val activeAlarms: StateFlow<Set<AlarmType>> = _activeAlarms.asStateFlow()

    // Telemetry buffer for charts (shared KMP) — injected via constructor

    // Persistent telemetry history (24h, downsampled)
    @Volatile private var telemetryHistory: TelemetryHistory? = null

    // Chart time range selection
    private val _chartTimeRange = MutableStateFlow(ChartTimeRange.FIVE_MINUTES)
    val chartTimeRange: StateFlow<ChartTimeRange> = _chartTimeRange.asStateFlow()

    // GPS location (full location for ride logging, speed for display/telemetry)
    private val _lastGpsLocation = MutableStateFlow<android.location.Location?>(null)
    private val _gpsSpeedKmh = MutableStateFlow(0.0)
    val gpsSpeedKmh: StateFlow<Double> = _gpsSpeedKmh.asStateFlow()

    // Expose samples as StateFlow — merges buffer (5m) or history (1h/24h)
    private val _telemetrySamples = MutableStateFlow<List<TelemetrySample>>(emptyList())
    val telemetrySamples: StateFlow<List<TelemetrySample>> = _telemetrySamples.asStateFlow()

    // Combined chart samples: buffer for 5m, history for longer ranges
    val chartSamples: StateFlow<List<TelemetrySample>> = combine(
        _chartTimeRange,
        _telemetrySamples
    ) { range, bufferSamples ->
        when (range) {
            ChartTimeRange.FIVE_MINUTES -> bufferSamples
            else -> telemetryHistory?.samplesForRange(range) ?: emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Logging
    private val _isLogging = MutableStateFlow(false)
    val isLogging: StateFlow<Boolean> = _isLogging.asStateFlow()
    private val _liveRideStats = MutableStateFlow<org.freewheel.core.logging.LiveRideStats?>(null)
    val liveRideStats: StateFlow<org.freewheel.core.logging.LiveRideStats?> = _liveRideStats.asStateFlow()

    // Ride pause (keep file open on ConnectionLost, resume on reconnect within 1 hour)
    private val _isRidePaused = MutableStateFlow(false)
    val isRidePaused: StateFlow<Boolean> = _isRidePaused.asStateFlow()
    private var ridePauseTimeoutJob: Job? = null
    private var pausedRideAddress: String? = null

    // BLE Capture
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    data class CaptureStats(val rxCount: Int = 0, val txCount: Int = 0, val markerCount: Int = 0, val startTimeMs: Long = 0)
    private val _captureStats = MutableStateFlow(CaptureStats())
    val captureStats: StateFlow<CaptureStats> = _captureStats.asStateFlow()

    // Unhandled frame collection
    private val unhandledCollector = UnhandledFrameCollector()
    private val _unhandledCount = MutableStateFlow(0)
    val unhandledCount: StateFlow<Int> = _unhandledCount.asStateFlow()

    // Connection error log — always-on CSV file per session
    private var errorLogWriter: java.io.BufferedWriter? = null
    private var errorLogSessionStartMs: Long = 0L
    private var errorLogFrameCount: Int = 0

    // WearOS manager (created when service is attached)
    private var wearOsManager: WearOsManager? = null

    // Auto-connect manager (created when service is attached)
    private var autoConnectManager: AutoConnectManager? = null
    val isAutoConnecting: StateFlow<Boolean> get() = autoConnectManager?.isAutoConnecting ?: MutableStateFlow(false)
    val reconnectState: StateFlow<AutoConnectManager.ReconnectState>
        get() = autoConnectManager?.reconnectState ?: MutableStateFlow(AutoConnectManager.ReconnectState.Idle)

    // Light state
    private val _isLightOn = MutableStateFlow(false)
    val isLightOn: StateFlow<Boolean> = _isLightOn.asStateFlow()

    // Range estimate
    private var startBattery: Int = -1
    private val _rangeEstimateKm = MutableStateFlow<Double?>(null)
    val rangeEstimateKm: StateFlow<Double?> = _rangeEstimateKm.asStateFlow()

    // Dashboard layout (per-wheel)
    private val _dashboardLayout = MutableStateFlow(DashboardLayout.default())
    val dashboardLayout: StateFlow<DashboardLayout> = _dashboardLayout.asStateFlow()

    // Navigation config (global)
    private val _navigationConfig = MutableStateFlow(NavigationConfig())
    val navigationConfig: StateFlow<NavigationConfig> = _navigationConfig.asStateFlow()

    // Custom tab layouts (global, keyed by custom tab ID)
    private val _customTabLayouts = MutableStateFlow<Map<String, DashboardLayout>>(emptyMap())
    val customTabLayouts: StateFlow<Map<String, DashboardLayout>> = _customTabLayouts.asStateFlow()

    // Saved wheel profiles
    private val _savedAddresses = MutableStateFlow(profileStore.getSavedAddresses())
    val savedAddresses: StateFlow<Set<String>> = _savedAddresses.asStateFlow()

    // --- DecoderConfig propagation ---

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        pushDecoderConfig()
    }

    private fun buildDecoderConfig(): DecoderConfig {
        return DecoderConfig(
            useMph = appConfig.useMph,
            useFahrenheit = appConfig.useFahrenheit,
            useCustomPercents = appConfig.customPercents,
            cellVoltageTiltback = appConfig.cellVoltageTiltback,
            rotationSpeed = appConfig.rotationSpeed,
            rotationVoltage = appConfig.rotationVoltage,
            powerFactor = appConfig.powerFactor,
            batteryCapacity = appConfig.batteryCapacity,
            wheelPassword = appConfig.passwordForWheel,
            gotwayNegative = appConfig.gotwayNegative.toIntOrNull() ?: 0,
            useRatio = appConfig.useRatio,
            gotwayVoltage = appConfig.gotwayVoltage.toIntOrNull() ?: 0,
            hwPwmEnabled = appConfig.hwPwm,
            autoVoltage = appConfig.autoVoltage,
            ks18LScaler = appConfig.ks18LScaler
        )
    }

    private fun pushDecoderConfig() {
        connectionManager?.updateConfig(buildDecoderConfig())
    }

    // Alarm checking — alarmChecker injected via constructor; must be declared before init{}
    // because startAlarmMonitoring() launches an immediate coroutine that accesses these properties.
    private val alarmHandler = AlarmHandler(
        vibrate = { pattern ->
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        },
        playTone = { type, duration ->
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            try {
                tg.startTone(type, duration)
            } finally {
                tg.release()
            }
        },
        onWheelBeep = ::wheelBeep
    )

    // Auto-torch: tracks whether engine requested light on (to avoid spamming commands)
    private var autoTorchLightRequested = false
    // When true, auto-torch backs off until next reconnect (user manually toggled light)
    private var autoTorchManualOverride = false

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        loadNavigationConfig()
        loadCustomTabLayouts()
        startTelemetryBuffering()
        startAlarmMonitoring()
        startAutoTorchMonitoring()
    }

    override fun onCleared() {
        super.onCleared()
        endErrorLogSession(null)
        wearOsManager?.stop()
        wearOsManager = null
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        if (captureLogger.isCapturing) stopCapture()
        if (rideLogger.isLogging) {
            stopLogging()
            telemetryHistory?.save()
        }
    }

    // --- Service binding ---

    fun attachService(service: WheelServiceContract, cm: WheelConnectionManagerPort, ble: BleManagerPort) {
        wheelService = service
        connectionManager = cm
        bleManager = ble
        chargerConnectionManager = service.chargerConnectionManager
        chargerBleManager = service.chargerBleManager

        // Wire capture callback if capture was started before connection
        if (captureLogger.isCapturing) wireCaptureCallback(cm)

        // Wire unhandled frame callback
        wireUnhandledCallback(cm)

        // Wire connection error log callback (always on)
        wireErrorLogCallback(cm)

        pushDecoderConfig()

        // Create shared auto-connect manager
        autoConnectManager?.destroy()
        autoConnectManager = AutoConnectManager(
            connectionState = cm.connectionState,
            connect = { address -> cm.connect(address) },
            scope = viewModelScope
        )

        // Refresh saved addresses (profiles may have been added before attach)
        _savedAddresses.value = profileStore.getSavedAddresses()

        service.onLightToggleRequested = ::toggleLight
        service.onLogToggleRequested = ::toggleLogging
        service.onGpsLocationUpdate = ::updateGpsLocation

        wearOsManager = WearOsManager(
            context = getApplication(),
            telemetryFlow = telemetryState,
            activeAlarmsFlow = activeAlarms,
            appConfig = appConfig,
            onHornRequested = ::wheelBeep,
            onLightToggleRequested = ::toggleLight
        ).also { it.start(viewModelScope) }

        stateCollectionJob = viewModelScope.launch {
            launch { cm.telemetryState.collect { _realTelemetry.value = it } }
            launch { cm.identityState.collect { _realIdentity.value = it } }
            launch { cm.bmsState.collect { _realBms.value = it } }
            launch { cm.settingsState.collect { _realSettings.value = it } }
            launch { cm.eventLogEntries.collect { _eventLogEntries.value = it } }
        }
        capabilitiesCollectionJob = viewModelScope.launch {
            cm.capabilities.collect { _capabilities.value = it }
        }
        connectionCollectionJob = viewModelScope.launch {
            cm.connectionState.collect { state ->
                if (_dataSource.value == WheelDataSource.LIVE) {
                    _connectionState.value = state
                }
                // Auto-save profile when connected
                if (state is ConnectionState.Connected) {
                    autoSaveProfile(state.address)
                    initHistoryForWheel(state.address)
                    loadDashboardLayout()
                    wheelService?.startLocationTracking()
                    startErrorLogSession(state.address)

                    // Resume paused ride if reconnecting to same wheel
                    if (_isRidePaused.value) {
                        if (state.address == pausedRideAddress) {
                            clearPauseState()
                            rideLogger.resume(System.currentTimeMillis())
                        } else {
                            clearPauseState()
                            stopLogging()
                        }
                    }
                }

                // Connection lost — pause ride instead of ending it.
                // OS-level auto-reconnect handles mid-ride reconnection
                // (Android: autoConnectPeripheral). Don't use AutoConnectManager
                // here — it would cancel the OS reconnect.
                if (state is ConnectionState.ConnectionLost) {
                    if (captureLogger.isCapturing) stopCapture()
                    if (rideLogger.isLogging && !_isRidePaused.value) {
                        rideLogger.pause(System.currentTimeMillis())
                        _isRidePaused.value = true
                        pausedRideAddress = state.address
                        ridePauseTimeoutJob?.cancel()
                        ridePauseTimeoutJob = viewModelScope.launch {
                            delay(RIDE_PAUSE_TIMEOUT_MS)
                            stopLogging()
                        }
                    }
                    wheelService?.stopLocationTracking()
                    endErrorLogSession(state)
                }

                // User-initiated disconnect or failure — always end ride
                if (state is ConnectionState.Disconnected ||
                    state is ConnectionState.Failed
                ) {
                    if (captureLogger.isCapturing) stopCapture()
                    if (rideLogger.isLogging) stopLogging()
                    wheelService?.stopLocationTracking()
                    endErrorLogSession(state)
                }
            }
        }

        // Charger state collection
        val ccm = service.chargerConnectionManager
        chargerStateCollectionJob = viewModelScope.launch {
            ccm.chargerState.collect { _chargerState.value = it }
        }
        chargerConnectionCollectionJob = viewModelScope.launch {
            ccm.connectionState.collect { state ->
                _chargerConnectionState.value = state
                if (state is ConnectionState.Connected) {
                    autoSaveChargerProfile(state.address)
                }
            }
        }
    }

    fun detachService() {
        wearOsManager?.stop()
        wearOsManager = null
        stateCollectionJob?.cancel()
        connectionCollectionJob?.cancel()
        capabilitiesCollectionJob?.cancel()
        chargerStateCollectionJob?.cancel()
        chargerConnectionCollectionJob?.cancel()
        stateCollectionJob = null
        connectionCollectionJob = null
        capabilitiesCollectionJob = null
        chargerStateCollectionJob = null
        chargerConnectionCollectionJob = null
        autoConnectManager?.destroy()
        autoConnectManager = null
        wheelService?.onGpsLocationUpdate = null
        wheelService?.onLightToggleRequested = null
        wheelService?.onLogToggleRequested = null
        connectionManager?.unhandledCallback = null
        connectionManager?.errorLogCallback = null
        endErrorLogSession(ConnectionState.Disconnected)
        wheelService = null
        connectionManager = null
        bleManager = null
        chargerConnectionManager = null
        chargerBleManager = null
    }

    // --- Demo mode ---

    fun startDemo() {
        _dataSource.value = WheelDataSource.DEMO
        _connectionState.value = ConnectionState.Connected("demo", "Demo Wheel")
        // Don't persist history for demo mode
        demoDataProvider.start(viewModelScope)
    }

    fun stopDemo() {
        if (captureLogger.isCapturing) stopCapture()
        if (rideLogger.isLogging) stopLogging()
        demoDataProvider.stop()
        _dataSource.value = WheelDataSource.LIVE
        _connectionState.value = ConnectionState.Disconnected
        telemetryBuffer.clear()
        _telemetrySamples.value = emptyList()
        alarmChecker.reset()
        _activeAlarms.value = emptySet()
    }

    // --- Replay mode ---

    fun startReplay(file: File) {
        val csvContent = file.readText()
        val capture = captureReader.parse(csvContent) ?: return
        if (!replayEngine.load(capture)) return

        _dataSource.value = WheelDataSource.REPLAY
        val wheelName = capture.header.wheelName.ifBlank { capture.header.wheelTypeName }
        _connectionState.value = ConnectionState.Connected("replay", wheelName)
        replayEngine.start(viewModelScope)
    }

    fun pauseReplay() = replayEngine.pause()

    fun resumeReplay() = replayEngine.resume(viewModelScope)

    fun stopReplay() {
        replayEngine.stop()
        _dataSource.value = WheelDataSource.LIVE
        _connectionState.value = ConnectionState.Disconnected
        telemetryBuffer.clear()
        _telemetrySamples.value = emptyList()
    }

    fun setReplaySpeed(multiplier: Float) = replayEngine.setSpeed(multiplier)

    fun seekReplay(progress: Float) = replayEngine.seekTo(progress, viewModelScope)

    // --- BLE operations ---

    fun startScan() {
        val ble = bleManager ?: return
        _isScanning.value = true
        _discoveredDevices.value = emptyList()
        viewModelScope.launch {
            ble.startScan { device ->
                addDiscoveredDevice(device)
            }
        }
    }

    fun stopScan() {
        _isScanning.value = false
        viewModelScope.launch {
            bleManager?.stopScan()
        }
    }

    private fun addDiscoveredDevice(device: BleDevice) {
        if (!appConfig.showUnknownDevices && device.name.isNullOrEmpty()) {
            return
        }
        val current = _discoveredDevices.value.toMutableList()
        val existing = current.indexOfFirst { it.address == device.address }
        val discovered = DiscoveredDevice(
            name = device.name ?: "Unknown",
            address = device.address,
            rssi = device.rssi
        )
        if (existing >= 0) {
            current[existing] = discovered
        } else {
            current.add(discovered)
        }
        _discoveredDevices.value = current.sortedByDescending { it.rssi }
    }

    fun connect(address: String) {
        val cm = connectionManager ?: return
        _isScanning.value = false
        appConfig.lastMac = address
        // Clear unhandled frames from previous session
        unhandledCollector.clear()
        _unhandledCount.value = 0
        connectJob = viewModelScope.launch {
            bleManager?.stopScan()
            cm.connect(address)
        }
    }

    fun disconnect() {
        if (_dataSource.value == WheelDataSource.REPLAY) {
            stopReplay()
            return
        }
        if (_dataSource.value == WheelDataSource.DEMO) {
            stopDemo()
            return
        }
        connectJob?.cancel()
        connectJob = null
        wearOsManager?.stop()
        appConfig.lastMac = ""
        autoConnectManager?.stop()
        if (captureLogger.isCapturing) stopCapture()
        if (rideLogger.isLogging) stopLogging()
        wheelService?.stopLocationTracking()
        telemetryHistory?.save()
        connectionManager?.disconnect()
        telemetryBuffer.clear()
        _telemetrySamples.value = emptyList()
        alarmChecker.reset()
        _activeAlarms.value = emptySet()
        startBattery = -1
        _rangeEstimateKm.value = null
        autoTorchManualOverride = false
    }

    fun setBluetoothAdapterState(state: BluetoothAdapterState) {
        _bluetoothState.value = state
        bleManager?.setBluetoothAdapterState(state)

        if (state == BluetoothAdapterState.POWERED_OFF) {
            _isScanning.value = false
            if (_dataSource.value == WheelDataSource.LIVE) {
                _connectionState.value = ConnectionState.Disconnected
            }
        }
    }

    fun shutdownService() {
        if (captureLogger.isCapturing) stopCapture()
        if (rideLogger.isLogging) stopLogging()
        telemetryHistory?.save()
        // shutdown() calls stopSelf() which is synchronous.
        // BLE disconnect is handled by WheelService.onDestroy().
        // Must not be in a coroutine — viewModelScope may be cancelled
        // before it runs if finishAffinity() is called immediately after.
        wheelService?.shutdown()
    }

    fun attemptStartupAutoConnect() {
        val lastMac = appConfig.lastMac
        if (lastMac.isEmpty()) return
        if (!appConfig.useReconnect) return
        val acm = autoConnectManager ?: return

        acm.attemptStartupConnect(lastMac)
    }

    // --- Startup scan (replaces blind auto-connect) ---

    private var startupScanJob: Job? = null

    fun startupScan() {
        val lastMac = appConfig.lastMac
        if (lastMac.isEmpty()) return
        val ble = bleManager ?: return

        _isScanning.value = true
        _discoveredDevices.value = emptyList()
        viewModelScope.launch {
            ble.startScan { device ->
                addDiscoveredDevice(device)
            }
        }

        // Watch for the last-connected wheel to appear in scan results
        startupScanJob = viewModelScope.launch {
            _discoveredDevices.collect { devices ->
                if (devices.any { it.address == lastMac }) {
                    startupScanJob?.cancel()
                    startupScanJob = null
                    connect(lastMac)
                }
            }
        }
    }

    // --- Saved wheel profiles ---

    fun getSavedDisplayName(address: String): String? {
        return profileStore.getDisplayName(address)
    }

    fun forgetProfile(address: String) {
        profileStore.deleteProfile(address)
        _savedAddresses.value = profileStore.getSavedAddresses()
    }

    private fun autoSaveProfile(address: String) {
        val identity = _realIdentity.value
        val displayName = identity.displayName.let {
            if (it == "Dashboard" || it.isEmpty()) {
                // Fall back to existing profile name or BLE name
                profileStore.getDisplayName(address) ?: ""
            } else it
        }
        profileStore.saveProfile(
            WheelProfile(
                address = address,
                displayName = displayName,
                wheelTypeName = identity.wheelType.name,
                lastConnectedMs = System.currentTimeMillis()
            )
        )
        _savedAddresses.value = profileStore.getSavedAddresses()
    }

    // --- Wheel commands ---

    fun wheelBeep() {
        viewModelScope.launch {
            connectionManager?.wheelBeep()
        }
    }

    fun toggleLight() {
        val newState = !_isLightOn.value
        _isLightOn.value = newState
        // If auto-torch is active, latch manual override so it stops controlling the light
        if (getGlobalBool(PreferenceKeys.AUTO_TORCH_ENABLED, PreferenceDefaults.AUTO_TORCH_ENABLED)) {
            autoTorchManualOverride = true
        }
        viewModelScope.launch {
            connectionManager?.toggleLight(newState)
        }
    }

    fun setPedalsMode(mode: Int) {
        viewModelScope.launch {
            connectionManager?.setPedalsMode(mode)
        }
    }

    // --- Event log (Veteran/Leaperkim) ---

    private val _eventLogEntries = MutableStateFlow<List<org.freewheel.core.domain.EventLogEntry>>(emptyList())
    val eventLogEntries: StateFlow<List<org.freewheel.core.domain.EventLogEntry>> = _eventLogEntries.asStateFlow()

    fun requestEventLog() {
        connectionManager?.clearEventLog()
        connectionManager?.requestEventLog()
    }

    fun clearEventLog() {
        connectionManager?.clearEventLog()
    }

    // --- Slider persistence for write-only commands ---

    fun saveSliderValue(commandId: SettingsCommandId, value: Int) {
        prefs.edit().putInt("${PreferenceKeys.WHEEL_SLIDER_PREFIX}${commandId.name}", value).apply()
    }

    fun loadSliderValue(commandId: SettingsCommandId): Int? {
        val key = "${PreferenceKeys.WHEEL_SLIDER_PREFIX}${commandId.name}"
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    // --- SharedPreferences helpers (PreferenceKeys-based, bypasses AppConfig) ---

    private val macPrefix: String
        get() = prefs.getString("last_mac", "") ?: ""

    fun getGlobalBool(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    fun setGlobalBool(key: String, value: Boolean) =
        prefs.edit().putBoolean(key, value).apply()

    fun getGlobalInt(key: String, default: Int): Int =
        prefs.getInt(key, default)

    fun setGlobalInt(key: String, value: Int) =
        prefs.edit().putInt(key, value).apply()

    fun getPerWheelBool(key: String, default: Boolean): Boolean =
        prefs.getBoolean("${macPrefix}_$key", default)

    fun setPerWheelBool(key: String, value: Boolean) =
        prefs.edit().putBoolean("${macPrefix}_$key", value).apply()

    fun getPerWheelInt(key: String, default: Int): Int =
        prefs.getInt("${macPrefix}_$key", default)

    fun setPerWheelInt(key: String, value: Int) =
        prefs.edit().putInt("${macPrefix}_$key", value).apply()

    fun executeWheelCommand(commandId: SettingsCommandId, intValue: Int = 0, boolValue: Boolean = false) {
        connectionManager?.executeCommand(commandId, intValue, boolValue)
    }

    // --- Charger scanning ---

    private val _isChargerScanning = MutableStateFlow(false)
    val isChargerScanning: StateFlow<Boolean> = _isChargerScanning.asStateFlow()

    private val _discoveredChargers = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredChargers: StateFlow<List<DiscoveredDevice>> = _discoveredChargers.asStateFlow()

    fun scanForChargers() {
        val ble = chargerBleManager ?: return
        _isChargerScanning.value = true
        _discoveredChargers.value = emptyList()
        viewModelScope.launch {
            ble.startScanForService(BleUuids.HwCharger.SERVICE) { device ->
                addDiscoveredCharger(device)
            }
        }
    }

    fun stopChargerScan() {
        _isChargerScanning.value = false
        viewModelScope.launch {
            chargerBleManager?.stopScan()
        }
    }

    private fun addDiscoveredCharger(device: BleDevice) {
        val current = _discoveredChargers.value.toMutableList()
        val existing = current.indexOfFirst { it.address == device.address }
        val discovered = DiscoveredDevice(
            name = device.name ?: "HW Charger",
            address = device.address,
            rssi = device.rssi
        )
        if (existing >= 0) {
            current[existing] = discovered
        } else {
            current.add(discovered)
        }
        _discoveredChargers.value = current.sortedByDescending { it.rssi }
    }

    // --- Charger operations ---

    fun connectCharger(address: String, password: String) {
        stopChargerScan()
        chargerConnectionManager?.connect(address, password)
    }

    fun disconnectCharger() {
        chargerConnectionManager?.disconnect()
    }

    fun setChargerVoltage(voltage: Float) {
        chargerConnectionManager?.setOutputVoltage(voltage)
    }

    fun setChargerCurrent(current: Float) {
        chargerConnectionManager?.setOutputCurrent(current)
    }

    fun toggleChargerOutput(enable: Boolean) {
        chargerConnectionManager?.toggleOutput(enable)
    }

    fun setChargerPowerLimit(watts: Int) {
        chargerConnectionManager?.setPowerLimit(watts)
    }

    fun setChargerAutoStop(enabled: Boolean) {
        chargerConnectionManager?.setAutoStop(enabled)
    }

    fun setChargerTwoStageCharging(enabled: Boolean) {
        chargerConnectionManager?.setTwoStageCharging(enabled)
    }

    fun setChargerEndOfChargeCurrent(current: Float) {
        chargerConnectionManager?.setEndOfChargeCurrent(current)
    }

    // --- Charger profiles ---

    private val _savedChargerAddresses = MutableStateFlow(chargerProfileStore.getSavedAddresses())
    val savedChargerAddresses: StateFlow<Set<String>> = _savedChargerAddresses.asStateFlow()

    fun getSavedChargerProfiles(): List<ChargerProfile> = chargerProfileStore.getSavedProfiles()

    fun getChargerProfile(address: String): ChargerProfile? = chargerProfileStore.getProfile(address)

    fun saveChargerProfile(profile: ChargerProfile) {
        chargerProfileStore.saveProfile(profile)
        _savedChargerAddresses.value = chargerProfileStore.getSavedAddresses()
    }

    fun forgetChargerProfile(address: String) {
        chargerProfileStore.deleteProfile(address)
        _savedChargerAddresses.value = chargerProfileStore.getSavedAddresses()
    }

    private fun autoSaveChargerProfile(address: String) {
        val existing = chargerProfileStore.getProfile(address)
        chargerProfileStore.saveProfile(
            ChargerProfile(
                address = address,
                displayName = existing?.displayName ?: "HW Charger",
                password = existing?.password ?: "",
                lastConnectedMs = System.currentTimeMillis()
            )
        )
        _savedChargerAddresses.value = chargerProfileStore.getSavedAddresses()
    }

    // --- Dashboard & Navigation config ---

    fun loadDashboardLayout() {
        val key = "${macPrefix}_${PreferenceKeys.DASHBOARD_LAYOUT}"
        val raw = prefs.getString(key, null)
        _dashboardLayout.value = if (raw != null) {
            DashboardLayoutSerializer.deserialize(raw) ?: DashboardLayout.default()
        } else {
            DashboardLayout.default()
        }
    }

    fun saveDashboardLayout(layout: DashboardLayout) {
        _dashboardLayout.value = layout
        val key = "${macPrefix}_${PreferenceKeys.DASHBOARD_LAYOUT}"
        prefs.edit { putString(key, DashboardLayoutSerializer.serialize(layout)) }
    }

    fun applyPreset(preset: DashboardPreset) {
        saveDashboardLayout(preset.layout)
    }

    private fun loadNavigationConfig() {
        val raw = prefs.getString(PreferenceKeys.NAVIGATION_CONFIG, null)
        _navigationConfig.value = if (raw != null) {
            NavigationConfigSerializer.deserialize(raw) ?: NavigationConfig()
        } else {
            NavigationConfig()
        }
    }

    fun saveNavigationConfig(config: NavigationConfig) {
        if (!config.isValid()) return
        _navigationConfig.value = config
        prefs.edit {
            putString(
                PreferenceKeys.NAVIGATION_CONFIG,
                NavigationConfigSerializer.serialize(config)
            )
        }
        loadCustomTabLayouts()
    }

    private fun loadCustomTabLayouts() {
        val config = _navigationConfig.value
        val layouts = mutableMapOf<String, DashboardLayout>()
        for (tab in config.customTabs) {
            val key = "custom_tab_${tab.id}_layout"
            val raw = prefs.getString(key, null)
            layouts[tab.id] = raw?.let { DashboardLayoutSerializer.deserialize(it) }
                ?: DashboardLayout.default()
        }
        _customTabLayouts.value = layouts
    }

    fun saveCustomTabLayout(tabId: String, layout: DashboardLayout) {
        val key = "custom_tab_${tabId}_layout"
        prefs.edit().putString(key, DashboardLayoutSerializer.serialize(layout)).apply()
        _customTabLayouts.value = _customTabLayouts.value + (tabId to layout)
    }

    fun deleteCustomTabLayout(tabId: String) {
        val key = "custom_tab_${tabId}_layout"
        prefs.edit().remove(key).apply()
        _customTabLayouts.value = _customTabLayouts.value - tabId
    }

    fun getGlobalString(key: String, default: String?): String? =
        prefs.getString(key, default)

    fun setGlobalString(key: String, value: String) =
        prefs.edit().putString(key, value).apply()

    // --- Logging ---

    fun toggleLogging() {
        if (rideLogger.isLogging) {
            stopLogging()
        } else {
            startLogging()
        }
    }

    private fun startLogging() {
        val app = getApplication<Application>()
        val ridesDir = File(app.getExternalFilesDir(null), "rides")
        ridesDir.mkdirs()
        val fileName = "${PlatformDateFormatter.formatRideFilename(System.currentTimeMillis())}.csv"
        val filePath = File(ridesDir, fileName).absolutePath
        val includeGps = getGlobalBool(PreferenceKeys.LOG_LOCATION_DATA, false)

        val now = System.currentTimeMillis()
        if (rideLogger.start(filePath, includeGps, now)) {
            _isLogging.value = true
            startLogSampling()
        }
    }

    private fun clearPauseState() {
        ridePauseTimeoutJob?.cancel()
        ridePauseTimeoutJob = null
        _isRidePaused.value = false
        pausedRideAddress = null
    }

    private fun stopLogging() {
        clearPauseState()
        logSamplingJob?.cancel()
        logSamplingJob = null
        val metadata = rideLogger.stop(System.currentTimeMillis(), telemetryState.value.totalDistance)
        _isLogging.value = false
        _liveRideStats.value = null

        if (metadata != null) {
            // Must use runBlocking — callers (disconnect, shutdownService, onCleared)
            // need the INSERT to complete before the scope is cancelled or the app exits.
            runBlocking(Dispatchers.IO) {
                tripRepository.insertNewData(
                    TripDataDbEntry(
                        fileName = metadata.fileName,
                        start = (metadata.startTimeMillis / 1000).toInt(),
                        duration = (metadata.durationSeconds / 60).toInt(),
                        maxSpeed = metadata.maxSpeedKmh.toFloat(),
                        avgSpeed = metadata.avgSpeedKmh.toFloat(),
                        maxCurrent = metadata.maxCurrentA.toFloat(),
                        maxPower = metadata.maxPowerW.toFloat(),
                        maxPwm = metadata.maxPwmPercent.toFloat(),
                        distance = metadata.distanceMeters.toInt(),
                        consumptionTotal = metadata.consumptionWh.toFloat(),
                        consumptionByKm = metadata.consumptionWhPerKm.toFloat()
                    )
                )
            }
        }
    }

    private var logSamplingJob: Job? = null

    private fun startLogSampling() {
        logSamplingJob = viewModelScope.launch {
            activeTelemetryOrNull.filterNotNull().collect { telemetry ->
                if (rideLogger.isLogging) {
                    val gps = _lastGpsLocation.value?.let { loc ->
                        GpsLocation(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            speedKmh = ByteUtils.metersPerSecondToKmh(loc.speed.toDouble()),
                            altitude = loc.altitude,
                            bearing = loc.bearing.toDouble(),
                            cumulativeDistance = 0.0
                        )
                    }
                    rideLogger.writeSample(telemetry, identityState.value.modeStr, gps, System.currentTimeMillis())
                    _liveRideStats.value = rideLogger.liveStats(
                        System.currentTimeMillis(), telemetry.totalDistance
                    )
                }
            }
        }
    }

    suspend fun loadTrips(): List<TripDataDbEntry> {
        return tripRepository.getAllData().sortedByDescending { it.start }
    }

    suspend fun loadTripByFileName(fileName: String): TripDataDbEntry? {
        return tripRepository.getTripByFileName(fileName)
    }

    fun deleteTrip(trip: TripDataDbEntry, context: Context) {
        viewModelScope.launch {
            tripRepository.removeDataById(trip.id.toLong())
            val ridesDir = File(context.getExternalFilesDir(null), "rides")
            val csvFile = File(ridesDir, trip.fileName)
            if (csvFile.exists()) csvFile.delete()
        }
    }

    suspend fun stitchRides(trips: List<TripDataDbEntry>, context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            val ridesDir = File(context.getExternalFilesDir(null), "rides")
            val csvContents = trips.mapNotNull { trip ->
                val file = File(ridesDir, trip.fileName)
                if (file.exists()) file.readText() else null
            }
            if (csvContents.size < 2) return@withContext false

            val earliestTrip = trips.minBy { it.start }
            val mergedFileName = earliestTrip.fileName
            val result = RideCsvEditor.stitch(csvContents, mergedFileName)

            // Write merged CSV
            File(ridesDir, mergedFileName).writeText(result.mergedCsv)

            // Delete original files (except the one we're reusing as the merged name)
            for (trip in trips) {
                tripRepository.removeDataById(trip.id.toLong())
                if (trip.fileName != mergedFileName) {
                    File(ridesDir, trip.fileName).delete()
                }
            }

            // Insert merged entry
            val meta = result.metadata
            tripRepository.insertNewData(
                TripDataDbEntry(
                    fileName = meta.fileName,
                    start = (meta.startTimeMillis / 1000).toInt(),
                    duration = (meta.durationSeconds / 60).toInt(),
                    maxSpeed = meta.maxSpeedKmh.toFloat(),
                    avgSpeed = meta.avgSpeedKmh.toFloat(),
                    maxCurrent = meta.maxCurrentA.toFloat(),
                    maxPower = meta.maxPowerW.toFloat(),
                    maxPwm = meta.maxPwmPercent.toFloat(),
                    distance = meta.distanceMeters.toInt(),
                    consumptionTotal = meta.consumptionWh.toFloat(),
                    consumptionByKm = meta.consumptionWhPerKm.toFloat()
                )
            )
            true
        }
    }

    suspend fun splitRide(trip: TripDataDbEntry, splitTimestampMs: Long, context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            val ridesDir = File(context.getExternalFilesDir(null), "rides")
            val csvFile = File(ridesDir, trip.fileName)
            if (!csvFile.exists()) return@withContext false

            val csvContent = csvFile.readText()
            val secondFileName = "${PlatformDateFormatter.formatRideFilename(splitTimestampMs)}.csv"
            val result = RideCsvEditor.split(csvContent, splitTimestampMs, trip.fileName, secondFileName)

            // Write both files
            File(ridesDir, trip.fileName).writeText(result.firstCsv)
            File(ridesDir, secondFileName).writeText(result.secondCsv)

            // Delete original DB entry, insert two new ones
            tripRepository.removeDataById(trip.id.toLong())

            suspend fun insertFromMeta(meta: org.freewheel.core.logging.RideMetadata) {
                tripRepository.insertNewData(
                    TripDataDbEntry(
                        fileName = meta.fileName,
                        start = (meta.startTimeMillis / 1000).toInt(),
                        duration = (meta.durationSeconds / 60).toInt(),
                        maxSpeed = meta.maxSpeedKmh.toFloat(),
                        avgSpeed = meta.avgSpeedKmh.toFloat(),
                        maxCurrent = meta.maxCurrentA.toFloat(),
                        maxPower = meta.maxPowerW.toFloat(),
                        maxPwm = meta.maxPwmPercent.toFloat(),
                        distance = meta.distanceMeters.toInt(),
                        consumptionTotal = meta.consumptionWh.toFloat(),
                        consumptionByKm = meta.consumptionWhPerKm.toFloat()
                    )
                )
            }
            insertFromMeta(result.firstMetadata)
            insertFromMeta(result.secondMetadata)
            true
        }
    }

    // --- BLE Capture ---

    fun startCapture() {
        val app = getApplication<Application>()
        val capturesDir = File(app.getExternalFilesDir(null), "captures")
        capturesDir.mkdirs()
        val now = System.currentTimeMillis()
        val fileName = "capture_${PlatformDateFormatter.formatRideFilename(now)}.csv"
        val filePath = File(capturesDir, fileName).absolutePath

        val identity = identityState.value
        val wheelTypeName = identity.wheelType.name
        val wheelName = identity.displayName
        val firmware = identity.version
        val appVersion = try {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }

        if (captureLogger.start(filePath, wheelTypeName, wheelName, firmware, appVersion, now)) {
            connectionManager?.let { wireCaptureCallback(it) }
            _captureStats.value = CaptureStats(startTimeMs = now)
            _isCapturing.value = true
        }
    }

    private fun wireCaptureCallback(cm: WheelConnectionManagerPort) {
        cm.captureCallback = { data, direction, annotation ->
            captureLogger.logPacket(data, direction, System.currentTimeMillis(), annotation)
            val stats = _captureStats.value
            _captureStats.value = when (direction) {
                BlePacketDirection.RX -> stats.copy(rxCount = stats.rxCount + 1)
                BlePacketDirection.TX -> stats.copy(txCount = stats.txCount + 1)
            }
        }
    }

    private fun wireUnhandledCallback(cm: WheelConnectionManagerPort) {
        cm.unhandledCallback = { reason, frameData ->
            unhandledCollector.record(reason, frameData, System.currentTimeMillis())
            _unhandledCount.value = unhandledCollector.count()
        }
    }

    // --- Connection Error Log ---

    private fun wireErrorLogCallback(cm: WheelConnectionManagerPort) {
        cm.errorLogCallback = callback@{ event ->
            val writer = errorLogWriter ?: return@callback
            try {
                writer.write(ConnectionErrorCsvFormatter.formatEvent(event, errorLogSessionStartMs))
                writer.newLine()
                writer.flush()
                errorLogFrameCount++
            } catch (_: Exception) {
                // Best effort — don't crash on write failure
            }
        }
    }

    private fun startErrorLogSession(address: String) {
        // Close any stale session first
        endErrorLogSession(null)
        val app = getApplication<Application>()
        val dir = File(app.getExternalFilesDir(null), "error_logs")
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val identity = identityState.value
        val wheelTypeName = identity.wheelType.name.lowercase()
        val wheelName = identity.displayName.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val fileName = "${PlatformDateFormatter.formatRideFilename(now)}_${wheelTypeName}.csv"
        try {
            val file = File(dir, fileName)
            val writer = file.bufferedWriter()
            writer.write(ConnectionErrorCsvFormatter.headerComment(
                wheelType = wheelTypeName,
                wheelName = identity.displayName,
                address = address,
                connectTimeMs = now
            ))
            writer.newLine()
            writer.flush()
            errorLogWriter = writer
            errorLogSessionStartMs = now
            errorLogFrameCount = 0
        } catch (_: Exception) {
            // Best effort
        }
    }

    private fun endErrorLogSession(state: ConnectionState?) {
        val writer = errorLogWriter ?: return
        errorLogWriter = null
        try {
            val reason = when (state) {
                is ConnectionState.ConnectionLost -> state.reason
                is ConnectionState.Failed -> state.error
                is ConnectionState.Disconnected -> "User disconnect"
                else -> "Unknown"
            }
            val cm = connectionManager
            val unpackerStats = cm?.let {
                // Access unpacker stats from the decoder via the WCM state flow
                // The decoder exposes getUnpackerStats() but we need it from the port;
                // for now, write footer without unpacker stats (they're in the events)
                null
            }
            writer.write(ConnectionErrorCsvFormatter.footerComment(
                disconnectTimeMs = System.currentTimeMillis(),
                disconnectReason = reason,
                totalFramesDecoded = errorLogFrameCount,
                unpackerStats = unpackerStats
            ))
            writer.newLine()
            writer.close()
        } catch (_: Exception) {
            try { writer.close() } catch (_: Exception) {}
        }
    }

    fun loadErrorLogs(): List<File> {
        val app = getApplication<Application>()
        val dir = File(app.getExternalFilesDir(null), "error_logs")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun deleteErrorLog(file: File) {
        file.delete()
    }

    fun clearErrorLogs() {
        val app = getApplication<Application>()
        val dir = File(app.getExternalFilesDir(null), "error_logs")
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Build shareable text of unhandled frames from the current session.
     * Returns null if no unhandled frames have been recorded.
     */
    fun buildUnhandledFramesText(): String? {
        val identity = identityState.value
        val caps = _capabilities.value
        return UnhandledFrameFormatter.format(
            entries = unhandledCollector.getEntries(),
            wheelType = identity.wheelType.name,
            model = caps.detectedModel.ifEmpty { identity.model },
            firmware = caps.firmwareVersion.ifEmpty { identity.version },
            platform = "android"
        )
    }

    fun stopCapture(): BleCaptureMetadata? {
        val cm = connectionManager
        cm?.captureCallback = null

        val footer = cm?.let { buildDiagnosticFooter(it) }
        val metadata = captureLogger.stop(System.currentTimeMillis(), footer)
        _isCapturing.value = false
        return metadata
    }

    /**
     * Build a diagnostic text snapshot for clipboard sharing.
     * Returns null if not connected.
     */
    fun buildDiagnosticText(): String? {
        val cm = connectionManager ?: return null
        val snapshot = DiagnosticSnapshotBuilder.buildSnapshot(
            identity = identityState.value,
            capabilities = _capabilities.value,
            connectionInfo = cm.getConnectionInfo(),
            decoderConfig = cm.getConfig(),
            platform = "android",
            appVersion = appVersion()
        )
        return DiagnosticSnapshotBuilder.formatAsText(snapshot)
    }

    private fun buildDiagnosticFooter(cm: WheelConnectionManagerPort): String {
        val snapshot = DiagnosticSnapshotBuilder.buildSnapshot(
            identity = identityState.value,
            capabilities = _capabilities.value,
            connectionInfo = cm.getConnectionInfo(),
            decoderConfig = cm.getConfig(),
            platform = "android",
            appVersion = appVersion()
        )
        return DiagnosticSnapshotBuilder.formatAsCommentBlock(snapshot)
    }

    private fun appVersion(): String =
        try {
            val app = getApplication<Application>()
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }

    fun insertCaptureMarker(label: String) {
        captureLogger.insertMarker(label, System.currentTimeMillis())
        if (captureLogger.isCapturing) {
            _captureStats.value = _captureStats.value.copy(
                markerCount = _captureStats.value.markerCount + 1
            )
        }
    }

    fun getCapturesDir(): File {
        val app = getApplication<Application>()
        val dir = File(app.getExternalFilesDir(null), "captures")
        dir.mkdirs()
        return dir
    }

    fun deleteCaptureFile(fileName: String) {
        val file = File(getCapturesDir(), fileName)
        if (file.exists()) file.delete()
    }

    // --- Telemetry buffering ---

    private fun startTelemetryBuffering() {
        viewModelScope.launch {
            activeTelemetryOrNull.filterNotNull().collect { telemetry ->
                val sample = TelemetrySample.fromTelemetry(
                    telemetry, System.currentTimeMillis(), _gpsSpeedKmh.value
                )
                if (telemetryBuffer.addSampleIfNeeded(sample)) {
                    _telemetrySamples.value = telemetryBuffer.samples
                }
                telemetryHistory?.addSample(sample)

                // Range estimation: capture start battery on first valid reading
                if (startBattery < 0 && telemetry.batteryLevel > 0) {
                    startBattery = telemetry.batteryLevel
                }
                if (startBattery > 0) {
                    _rangeEstimateKm.value = RangeEstimator.estimate(
                        currentBattery = telemetry.batteryLevel,
                        tripDistanceKm = telemetry.wheelDistanceKm,
                        startBattery = startBattery
                    )
                }
            }
        }
    }

    /** Call from Activity/Service when GPS location updates arrive. */
    fun updateGpsLocation(location: android.location.Location) {
        _lastGpsLocation.value = location
        _gpsSpeedKmh.value = ByteUtils.metersPerSecondToKmh(location.speed.toDouble())
    }

    fun setChartTimeRange(range: ChartTimeRange) {
        _chartTimeRange.value = range
    }

    fun saveHistoryToDisk() {
        telemetryHistory?.save()
    }

    private fun initHistoryForWheel(address: String) {
        // Don't persist for demo mode
        if (address == "demo") return
        // Save current history if switching wheels
        telemetryHistory?.save()
        val sanitized = StringUtil.sanitizeAddress(address)
        val dir = File(getApplication<Application>().filesDir, "telemetry")
        val path = File(dir, "$sanitized.csv").absolutePath
        val history = TelemetryHistory(telemetryFileIO)
        history.loadForWheel(path, System.currentTimeMillis())
        telemetryHistory = history
    }

    // --- Auto-torch monitoring ---

    private fun startAutoTorchMonitoring() {
        viewModelScope.launch {
            activeTelemetryOrNull.filterNotNull().collect { telemetry ->
                val enabled = getGlobalBool(PreferenceKeys.AUTO_TORCH_ENABLED, PreferenceDefaults.AUTO_TORCH_ENABLED)
                if (!enabled) {
                    if (autoTorchLightRequested) {
                        // Auto-torch was on but user disabled the feature — turn off
                        autoTorchLightRequested = false
                        _isLightOn.value = false
                        connectionManager?.toggleLight(false)
                    }
                    return@collect
                }

                val speedThreshold = getGlobalInt(
                    PreferenceKeys.AUTO_TORCH_SPEED_THRESHOLD,
                    PreferenceDefaults.AUTO_TORCH_SPEED_THRESHOLD
                )
                val useSunset = getGlobalBool(
                    PreferenceKeys.AUTO_TORCH_USE_SUNSET,
                    PreferenceDefaults.AUTO_TORCH_USE_SUNSET
                )
                val gpsLocation = _lastGpsLocation.value

                // User manually toggled light — back off until reconnect
                if (autoTorchManualOverride) return@collect

                val result = AutoTorchEngine.shouldLightBeOn(
                    speedKmh = telemetry.speedKmh,
                    speedThresholdKmh = speedThreshold,
                    useSunset = useSunset,
                    latitudeDeg = gpsLocation?.latitude ?: 0.0,
                    longitudeDeg = gpsLocation?.longitude ?: 0.0
                )

                if (result.shouldBeOn && !autoTorchLightRequested) {
                    autoTorchLightRequested = true
                    _isLightOn.value = true
                    connectionManager?.toggleLight(true)
                } else if (!result.shouldBeOn && autoTorchLightRequested) {
                    autoTorchLightRequested = false
                    _isLightOn.value = false
                    connectionManager?.toggleLight(false)
                }
            }
        }
    }

    // --- Alarm monitoring ---

    private fun startAlarmMonitoring() {
        viewModelScope.launch {
            activeTelemetryOrNull.filterNotNull().collect { telemetry ->
                if (!appConfig.alarmsEnabled) {
                    if (_activeAlarms.value.isNotEmpty()) {
                        _activeAlarms.value = emptySet()
                    }
                    return@collect
                }

                val config = AlarmConfig(
                    pwmBasedAlarms = appConfig.pwmBasedAlarms,
                    alarmFactor1 = appConfig.alarmFactor1,
                    alarmFactor2 = appConfig.alarmFactor2,
                    warningPwm = appConfig.warningPwm,
                    warningSpeed = appConfig.warningSpeed,
                    warningSpeedPeriod = appConfig.warningSpeedPeriod,
                    alarm1Speed = appConfig.alarm1Speed,
                    alarm1Battery = appConfig.alarm1Battery,
                    alarm2Speed = appConfig.alarm2Speed,
                    alarm2Battery = appConfig.alarm2Battery,
                    alarm3Speed = appConfig.alarm3Speed,
                    alarm3Battery = appConfig.alarm3Battery,
                    alarmCurrent = appConfig.alarmCurrent,
                    alarmPhaseCurrent = appConfig.alarmPhaseCurrent,
                    alarmTemperature = appConfig.alarmTemperature,
                    alarmMotorTemperature = appConfig.alarmMotorTemperature,
                    alarmBattery = appConfig.alarmBattery,
                    alarmWheel = appConfig.alarmWheel
                )

                val now = System.currentTimeMillis()
                val result = alarmChecker.check(telemetry, config, now)
                _activeAlarms.value = result.triggeredAlarms.map { it.type }.toSet()
                alarmHandler.handleAlarmResult(result, AlarmAction.fromValue(appConfig.alarmAction))
            }
        }
    }
}
