package org.freewheel.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.freewheel.AppConfig
import org.freewheel.core.domain.WheelState
import org.freewheel.core.utils.ByteUtils
import org.freewheel.core.utils.PlatformDateFormatter
import org.freewheel.core.utils.StringUtil
import org.freewheel.core.logging.GpsLocation
import org.freewheel.core.logging.RideLogger
import org.freewheel.core.telemetry.ChartTimeRange
import org.freewheel.core.telemetry.PlatformTelemetryFileIO
import org.freewheel.core.telemetry.TelemetryBuffer
import org.freewheel.core.telemetry.TelemetryHistory
import org.freewheel.core.telemetry.TelemetrySample
import org.freewheel.core.service.AutoConnectManager
import org.freewheel.core.service.BleDevice
import org.freewheel.core.service.BleManager
import org.freewheel.core.service.ConnectionState
import org.freewheel.core.service.DemoDataProvider
import org.freewheel.core.service.WheelConnectionManager
import org.freewheel.core.domain.AlarmAction
import org.freewheel.core.domain.AlarmType
import org.freewheel.core.domain.SettingsCommandId
import org.freewheel.core.domain.WheelProfile
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
import org.freewheel.data.TripDataDbEntry
import org.freewheel.data.TripDatabase
import org.freewheel.data.TripRepository
import android.content.Context
import androidx.preference.PreferenceManager
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class WheelViewModel(application: Application) : AndroidViewModel(application) {

    val appConfig: AppConfig = AppConfig(application)
    private val tripRepository: TripRepository

    private val demoDataProvider = DemoDataProvider()

    // Service references (set via attachService/detachService)
    private var wheelService: WheelService? = null
    private var connectionManager: WheelConnectionManager? = null
    private var bleManager: BleManager? = null
    private var stateCollectionJob: Job? = null
    private var connectionCollectionJob: Job? = null

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Demo mode
    private val _isDemo = MutableStateFlow(false)
    val isDemo: StateFlow<Boolean> = _isDemo.asStateFlow()

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

    // Real wheel state from WheelConnectionManager
    private val _realWheelState = MutableStateFlow(WheelState())

    // Wheel state — combines real and demo sources
    val wheelState: StateFlow<WheelState> = combine(
        _isDemo,
        _realWheelState,
        demoDataProvider.wheelState
    ) { demo, realState, demoState ->
        if (demo) demoState else realState
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WheelState())

    // Alarms
    private val _activeAlarms = MutableStateFlow<Set<AlarmType>>(emptySet())
    val activeAlarms: StateFlow<Set<AlarmType>> = _activeAlarms.asStateFlow()

    // Telemetry buffer for charts (shared KMP)
    val telemetryBuffer = TelemetryBuffer()

    // Persistent telemetry history (24h, downsampled)
    @Volatile private var telemetryHistory: TelemetryHistory? = null
    private val telemetryFileIO = PlatformTelemetryFileIO()

    // Chart time range selection
    private val _chartTimeRange = MutableStateFlow(ChartTimeRange.FIVE_MINUTES)
    val chartTimeRange: StateFlow<ChartTimeRange> = _chartTimeRange.asStateFlow()

    // GPS location (full location for ride logging, speed for display/telemetry)
    private var _lastGpsLocation: android.location.Location? = null
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

    // Dashboard layout (per-wheel)
    private val _dashboardLayout = MutableStateFlow(DashboardLayout.default())
    val dashboardLayout: StateFlow<DashboardLayout> = _dashboardLayout.asStateFlow()

    // Navigation config (global)
    private val _navigationConfig = MutableStateFlow(NavigationConfig())
    val navigationConfig: StateFlow<NavigationConfig> = _navigationConfig.asStateFlow()

    // Saved wheel profiles
    val profileStore = WheelProfileStore(
        PreferenceManager.getDefaultSharedPreferences(application)
    )
    private val _savedAddresses = MutableStateFlow(profileStore.getSavedAddresses())
    val savedAddresses: StateFlow<Set<String>> = _savedAddresses.asStateFlow()

    // --- DecoderConfig propagation ---

    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)

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
            autoVoltage = appConfig.autoVoltage
        )
    }

    private fun pushDecoderConfig() {
        connectionManager?.updateConfig(buildDecoderConfig())
    }

    // Alarm checking — must be declared before init{} because startAlarmMonitoring()
    // launches an immediate coroutine that accesses these properties.
    private val alarmChecker = AlarmChecker()
    private val alarmHandler = AlarmHandler(
        vibrate = { pattern ->
            val vibrator = getApplication<Application>().getSystemService(Vibrator::class.java)
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

    init {
        val db = TripDatabase.getDataBase(application)
        tripRepository = TripRepository(db.tripDao())
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        loadNavigationConfig()
        startTelemetryBuffering()
        startAlarmMonitoring()
    }

    override fun onCleared() {
        super.onCleared()
        wearOsManager?.stop()
        wearOsManager = null
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        if (rideLogger.isLogging) {
            stopLogging()
            telemetryHistory?.save()
        }
    }

    // --- Service binding ---

    fun attachService(service: WheelService, cm: WheelConnectionManager, ble: BleManager) {
        wheelService = service
        connectionManager = cm
        bleManager = ble

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
            wheelStateFlow = wheelState,
            activeAlarmsFlow = activeAlarms,
            appConfig = appConfig,
            onHornRequested = ::wheelBeep,
            onLightToggleRequested = ::toggleLight
        ).also { it.start(viewModelScope) }

        stateCollectionJob = viewModelScope.launch {
            cm.wheelState.collect { _realWheelState.value = it }
        }
        connectionCollectionJob = viewModelScope.launch {
            cm.connectionState.collect { state ->
                if (!_isDemo.value) {
                    _connectionState.value = state
                }
                // Auto-save profile when connected
                if (state is ConnectionState.Connected) {
                    autoSaveProfile(state.address)
                    initHistoryForWheel(state.address)
                    loadDashboardLayout()
                    wheelService?.startLocationTracking()
                }
                // Start reconnect-after-loss when connection drops
                if (state is ConnectionState.ConnectionLost && appConfig.useReconnect) {
                    autoConnectManager?.startReconnecting(state.address)
                }
                // Stop GPS when disconnected
                if (state is ConnectionState.ConnectionLost ||
                    state is ConnectionState.Disconnected ||
                    state is ConnectionState.Failed
                ) {
                    wheelService?.stopLocationTracking()
                }
            }
        }
    }

    fun detachService() {
        wearOsManager?.stop()
        wearOsManager = null
        stateCollectionJob?.cancel()
        connectionCollectionJob?.cancel()
        stateCollectionJob = null
        connectionCollectionJob = null
        autoConnectManager?.destroy()
        autoConnectManager = null
        wheelService?.onGpsLocationUpdate = null
        wheelService?.onLightToggleRequested = null
        wheelService?.onLogToggleRequested = null
        wheelService = null
        connectionManager = null
        bleManager = null
    }

    // --- Demo mode ---

    fun startDemo() {
        _isDemo.value = true
        _connectionState.value = ConnectionState.Connected("demo", "Demo Wheel")
        // Don't persist history for demo mode
        demoDataProvider.start(viewModelScope)
    }

    fun stopDemo() {
        if (rideLogger.isLogging) stopLogging()
        demoDataProvider.stop()
        _isDemo.value = false
        _connectionState.value = ConnectionState.Disconnected
        telemetryBuffer.clear()
        _telemetrySamples.value = emptyList()
    }

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
        viewModelScope.launch {
            bleManager?.stopScan()
            cm.connect(address)
        }
    }

    fun disconnect() {
        if (_isDemo.value) {
            stopDemo()
            return
        }
        wearOsManager?.stop()
        appConfig.lastMac = ""
        autoConnectManager?.stop()
        if (rideLogger.isLogging) stopLogging()
        wheelService?.stopLocationTracking()
        telemetryHistory?.save()
        viewModelScope.launch {
            connectionManager?.disconnect()
        }
        telemetryBuffer.clear()
        _telemetrySamples.value = emptyList()
    }

    fun onBluetoothOff() {
        _isScanning.value = false
        if (!_isDemo.value) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun shutdownService() {
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
        val state = _realWheelState.value
        val displayName = state.displayName.let {
            if (it == "Dashboard" || it.isEmpty()) {
                // Fall back to existing profile name or BLE name
                profileStore.getDisplayName(address) ?: ""
            } else it
        }
        profileStore.saveProfile(
            WheelProfile(
                address = address,
                displayName = displayName,
                wheelTypeName = state.wheelType.name,
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
        viewModelScope.launch {
            connectionManager?.toggleLight(newState)
        }
    }

    fun setPedalsMode(mode: Int) {
        viewModelScope.launch {
            connectionManager?.setPedalsMode(mode)
        }
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
        viewModelScope.launch {
            connectionManager?.executeCommand(commandId, intValue, boolValue)
        }
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
        prefs.edit().putString(key, DashboardLayoutSerializer.serialize(layout)).apply()
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
        prefs.edit().putString(PreferenceKeys.NAVIGATION_CONFIG, NavigationConfigSerializer.serialize(config)).apply()
    }

    fun getGlobalString(key: String, default: String?): String? =
        prefs.getString(key, default)

    fun setGlobalString(key: String, value: String) =
        prefs.edit().putString(key, value).apply()

    // --- Logging ---

    private val rideLogger = RideLogger()

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

    private fun stopLogging() {
        logSamplingJob?.cancel()
        logSamplingJob = null
        val state = wheelState.value
        val metadata = rideLogger.stop(System.currentTimeMillis(), state.totalDistance)
        _isLogging.value = false

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
            wheelState.collect { state ->
                if (rideLogger.isLogging) {
                    val gps = _lastGpsLocation?.let { loc ->
                        GpsLocation(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            speedKmh = ByteUtils.metersPerSecondToKmh(loc.speed.toDouble()),
                            altitude = loc.altitude,
                            bearing = loc.bearing.toDouble(),
                            cumulativeDistance = 0.0
                        )
                    }
                    rideLogger.writeSample(state, gps, System.currentTimeMillis())
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

    // --- Telemetry buffering ---

    private fun startTelemetryBuffering() {
        viewModelScope.launch {
            wheelState.collect { state ->
                if (state.speed != 0 || state.voltage != 0) {
                    val sample = TelemetrySample.fromWheelState(
                        state, System.currentTimeMillis(), _gpsSpeedKmh.value
                    )
                    if (telemetryBuffer.addSampleIfNeeded(sample)) {
                        _telemetrySamples.value = telemetryBuffer.samples
                    }
                    telemetryHistory?.addSample(sample)
                }
            }
        }
    }

    /** Call from Activity/Service when GPS location updates arrive. */
    fun updateGpsLocation(location: android.location.Location) {
        _lastGpsLocation = location
        _gpsSpeedKmh.value = ByteUtils.metersPerSecondToKmh(location.speed.toDouble())
    }

    fun setChartTimeRange(range: ChartTimeRange) {
        _chartTimeRange.value = range
        // Refresh chart samples for non-buffer ranges
        if (range != ChartTimeRange.FIVE_MINUTES) {
            _telemetrySamples.value = _telemetrySamples.value // trigger recompute
        }
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

    // --- Alarm monitoring ---

    private fun startAlarmMonitoring() {
        viewModelScope.launch {
            wheelState.collect { state ->
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
                val result = alarmChecker.check(state, config, now)
                _activeAlarms.value = result.triggeredAlarms.map { it.type }.toSet()
                alarmHandler.handleAlarmResult(result, AlarmAction.fromValue(appConfig.alarmAction))
            }
        }
    }
}
