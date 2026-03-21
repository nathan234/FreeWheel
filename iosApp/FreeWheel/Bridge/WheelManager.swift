import Foundation
import Combine
import FreeWheelCore

/// Swift wrapper for KMP wheel management APIs.
/// Provides an ObservableObject interface for SwiftUI integration.
@MainActor
class WheelManager: ObservableObject {
    // MARK: - Published State

    @Published private(set) var telemetry: TelemetryState?
    @Published private(set) var identity: WheelIdentity = WheelIdentity.companion.empty()
    @Published private(set) var bmsState: BmsState = BmsState.companion.empty()
    @Published private(set) var wheelSettings: WheelSettings = WheelSettings.None.shared
    @Published private(set) var capabilities: CapabilitySet = CapabilitySet.companion.empty()
    @Published private(set) var connectionState: ConnectionStateWrapper = .disconnected
    @Published private(set) var discoveredDevices: [DiscoveredDevice] = []
    @Published private(set) var isScanning: Bool = false
    @Published private(set) var bluetoothState: BluetoothAdapterState = .unknown
    @Published var isMockMode: Bool = false
    @Published var isTestMode: Bool = false

    // Unit preferences (persisted to UserDefaults, keys from shared KMP PreferenceKeys)
    @Published var useMph: Bool = UserDefaults.standard.bool(forKey: PreferenceKeys.shared.USE_MPH) {
        didSet {
            UserDefaults.standard.set(useMph, forKey: PreferenceKeys.shared.USE_MPH)
            pushDecoderConfig()
        }
    }
    @Published var useFahrenheit: Bool = UserDefaults.standard.bool(forKey: PreferenceKeys.shared.USE_FAHRENHEIT) {
        didSet {
            UserDefaults.standard.set(useFahrenheit, forKey: PreferenceKeys.shared.USE_FAHRENHEIT)
            pushDecoderConfig()
        }
    }
    @Published var isLightOn: Bool = false

    @Published var speedDisplayMode: SpeedDisplayMode = SpeedDisplayMode.fromRawValue(UserDefaults.standard.integer(forKey: PreferenceKeys.shared.SPEED_DISPLAY_MODE)) ?? .wheel {
        didSet { UserDefaults.standard.set(speedDisplayMode.rawValue, forKey: PreferenceKeys.shared.SPEED_DISPLAY_MODE) }
    }

    // Alarm settings (persisted to UserDefaults, stored in km/h and °C internally)
    @Published var alarmsEnabled: Bool = UserDefaults.standard.bool(forKey: PreferenceKeys.shared.ALARMS_ENABLED) {
        didSet { UserDefaults.standard.set(alarmsEnabled, forKey: PreferenceKeys.shared.ALARMS_ENABLED) }
    }
    @Published var alarm1Speed: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_1_SPEED) {
        didSet { UserDefaults.standard.set(alarm1Speed, forKey: PreferenceKeys.shared.ALARM_1_SPEED) }
    }
    @Published var alarm2Speed: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_2_SPEED) {
        didSet { UserDefaults.standard.set(alarm2Speed, forKey: PreferenceKeys.shared.ALARM_2_SPEED) }
    }
    @Published var alarm3Speed: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_3_SPEED) {
        didSet { UserDefaults.standard.set(alarm3Speed, forKey: PreferenceKeys.shared.ALARM_3_SPEED) }
    }
    @Published var alarmCurrent: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_CURRENT) {
        didSet { UserDefaults.standard.set(alarmCurrent, forKey: PreferenceKeys.shared.ALARM_CURRENT) }
    }
    @Published var alarmTemperature: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_TEMPERATURE) {
        didSet { UserDefaults.standard.set(alarmTemperature, forKey: PreferenceKeys.shared.ALARM_TEMPERATURE) }
    }
    @Published var alarmBattery: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_BATTERY) {
        didSet { UserDefaults.standard.set(alarmBattery, forKey: PreferenceKeys.shared.ALARM_BATTERY) }
    }

    // Alarm action (Feature 1)
    @Published var alarmAction: FreeWheelCore.AlarmAction = FreeWheelCore.AlarmAction.companion.fromValue(value: Int32(UserDefaults.standard.integer(forKey: PreferenceKeys.shared.ALARM_ACTION))) {
        didSet { UserDefaults.standard.set(alarmAction.value, forKey: PreferenceKeys.shared.ALARM_ACTION) }
    }
    @Published private(set) var activeAlarms: Set<AlarmType> = []

    // PWM-based alarm settings
    @Published var pwmBasedAlarms: Bool = UserDefaults.standard.bool(forKey: PreferenceKeys.shared.ALTERED_ALARMS) {
        didSet { UserDefaults.standard.set(pwmBasedAlarms, forKey: PreferenceKeys.shared.ALTERED_ALARMS) }
    }
    @Published var alarmFactor1: Double = {
        let v = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_FACTOR_1)
        return v == 0 ? Double(PreferenceDefaults.shared.ALARM_FACTOR_1) : v
    }() {
        didSet { UserDefaults.standard.set(alarmFactor1, forKey: PreferenceKeys.shared.ALARM_FACTOR_1) }
    }
    @Published var alarmFactor2: Double = {
        let v = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_FACTOR_2)
        return v == 0 ? Double(PreferenceDefaults.shared.ALARM_FACTOR_2) : v
    }() {
        didSet { UserDefaults.standard.set(alarmFactor2, forKey: PreferenceKeys.shared.ALARM_FACTOR_2) }
    }

    // Pre-warning settings
    @Published var warningPwm: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.WARNING_PWM) {
        didSet { UserDefaults.standard.set(warningPwm, forKey: PreferenceKeys.shared.WARNING_PWM) }
    }
    @Published var warningSpeed: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.WARNING_SPEED) {
        didSet { UserDefaults.standard.set(warningSpeed, forKey: PreferenceKeys.shared.WARNING_SPEED) }
    }
    @Published var warningSpeedPeriod: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.WARNING_SPEED_PERIOD) {
        didSet { UserDefaults.standard.set(warningSpeedPeriod, forKey: PreferenceKeys.shared.WARNING_SPEED_PERIOD) }
    }

    // Battery thresholds per speed alarm
    @Published var alarm1Battery: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_1_BATTERY) {
        didSet { UserDefaults.standard.set(alarm1Battery, forKey: PreferenceKeys.shared.ALARM_1_BATTERY) }
    }
    @Published var alarm2Battery: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_2_BATTERY) {
        didSet { UserDefaults.standard.set(alarm2Battery, forKey: PreferenceKeys.shared.ALARM_2_BATTERY) }
    }
    @Published var alarm3Battery: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_3_BATTERY) {
        didSet { UserDefaults.standard.set(alarm3Battery, forKey: PreferenceKeys.shared.ALARM_3_BATTERY) }
    }

    // New alarm types
    @Published var alarmPhaseCurrent: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_PHASE_CURRENT) {
        didSet { UserDefaults.standard.set(alarmPhaseCurrent, forKey: PreferenceKeys.shared.ALARM_PHASE_CURRENT) }
    }
    @Published var alarmMotorTemperature: Double = UserDefaults.standard.double(forKey: PreferenceKeys.shared.ALARM_MOTOR_TEMPERATURE) {
        didSet { UserDefaults.standard.set(alarmMotorTemperature, forKey: PreferenceKeys.shared.ALARM_MOTOR_TEMPERATURE) }
    }
    @Published var alarmWheel: Bool = UserDefaults.standard.bool(forKey: PreferenceKeys.shared.ALARM_WHEEL) {
        didSet { UserDefaults.standard.set(alarmWheel, forKey: PreferenceKeys.shared.ALARM_WHEEL) }
    }

    // Connection settings (persisted to UserDefaults)
    @Published var autoReconnect: Bool = UserDefaults.standard.bool(forKey: PreferenceKeys.shared.USE_RECONNECT) {
        didSet { UserDefaults.standard.set(autoReconnect, forKey: PreferenceKeys.shared.USE_RECONNECT) }
    }
    @Published var showUnknownDevices: Bool = UserDefaults.standard.bool(forKey: PreferenceKeys.shared.SHOW_UNKNOWN_DEVICES) {
        didSet { UserDefaults.standard.set(showUnknownDevices, forKey: PreferenceKeys.shared.SHOW_UNKNOWN_DEVICES) }
    }

    // Auto-reconnect state (Feature 2) — from shared KMP AutoConnectManager
    enum ReconnectDisplayState: Equatable {
        case idle
        case waiting(attempt: Int, nextRetryMs: Int64)
        case attempting(attempt: Int)
    }
    @Published private(set) var reconnectState: ReconnectDisplayState = .idle

    // Startup auto-connect state — from shared KMP AutoConnectManager
    @Published private(set) var isAutoConnecting: Bool = false

    // Logging settings (Feature 3)
    @Published var autoStartLogging: Bool = UserDefaults.standard.bool(forKey: PreferenceKeys.shared.AUTO_LOG) {
        didSet { UserDefaults.standard.set(autoStartLogging, forKey: PreferenceKeys.shared.AUTO_LOG) }
    }
    @Published var logGPS: Bool = UserDefaults.standard.bool(forKey: PreferenceKeys.shared.LOG_LOCATION_DATA) {
        didSet { UserDefaults.standard.set(logGPS, forKey: PreferenceKeys.shared.LOG_LOCATION_DATA) }
    }
    @Published private(set) var isLogging: Bool = false

    // Auto-torch settings (persisted to UserDefaults)
    @Published var autoTorchEnabled: Bool = UserDefaults.standard.bool(forKey: PreferenceKeys.shared.AUTO_TORCH_ENABLED) {
        didSet { UserDefaults.standard.set(autoTorchEnabled, forKey: PreferenceKeys.shared.AUTO_TORCH_ENABLED) }
    }
    @Published var autoTorchSpeedThreshold: Double = {
        let ud = UserDefaults.standard
        return ud.object(forKey: PreferenceKeys.shared.AUTO_TORCH_SPEED_THRESHOLD) != nil
            ? Double(ud.integer(forKey: PreferenceKeys.shared.AUTO_TORCH_SPEED_THRESHOLD))
            : Double(PreferenceDefaults.shared.AUTO_TORCH_SPEED_THRESHOLD)
    }() {
        didSet { UserDefaults.standard.set(Int(autoTorchSpeedThreshold), forKey: PreferenceKeys.shared.AUTO_TORCH_SPEED_THRESHOLD) }
    }
    @Published var autoTorchUseSunset: Bool = {
        let ud = UserDefaults.standard
        return ud.object(forKey: PreferenceKeys.shared.AUTO_TORCH_USE_SUNSET) != nil
            ? ud.bool(forKey: PreferenceKeys.shared.AUTO_TORCH_USE_SUNSET)
            : PreferenceDefaults.shared.AUTO_TORCH_USE_SUNSET
    }() {
        didSet { UserDefaults.standard.set(autoTorchUseSunset, forKey: PreferenceKeys.shared.AUTO_TORCH_USE_SUNSET) }
    }
    private var autoTorchLightRequested: Bool = false
    private var autoTorchManualOverride: Bool = false

    // Range estimate
    private var startBattery: Int = -1
    @Published var rangeEstimateKm: Double? = nil

    // BLE Capture
    @Published private(set) var isCapturing: Bool = false
    @Published private(set) var captureRxCount: Int = 0
    @Published private(set) var captureTxCount: Int = 0
    @Published private(set) var captureMarkerCount: Int = 0
    @Published private(set) var captureStartTime: Date? = nil

    // Unhandled frame collection
    private let unhandledCollector = UnhandledFrameCollector()
    @Published private(set) var unhandledCount: Int = 0

    // Connection error log — always-on CSV per session
    private var errorLogFileHandle: FileHandle?
    private var errorLogSessionStartMs: Int64 = 0
    private var errorLogFrameCount: Int = 0

    // BLE Replay
    private let replayEngine: ReplayEngine = WheelConnectionManagerHelper.shared.createReplayEngine()
    @Published private(set) var isReplayMode: Bool = false
    @Published private(set) var replayStateName: String = "IDLE"
    @Published private(set) var replayProgress: Float = 0
    @Published private(set) var replayCurrentTimeMs: Int64 = 0
    @Published private(set) var replayTotalDurationMs: Int64 = 0
    @Published private(set) var replayPacketIndex: Int32 = 0
    @Published private(set) var replayTotalPackets: Int32 = 0
    @Published private(set) var replaySpeed: Float = 1.0
    private var replayTelemetryObserver: FlowObservation?
    private var replayStateObserver: FlowObservation?
    private var replayPositionObserver: FlowObservation?
    private var replaySpeedObserver: FlowObservation?

    // MARK: - Dashboard & Navigation Config

    @Published var dashboardLayout: DashboardLayout = DashboardLayout.companion.default() {
        didSet {
            let serialized = DashboardLayoutSerializer.shared.serialize(layout: dashboardLayout)
            UserDefaults.standard.set(serialized, forKey: PreferenceKeys.shared.DASHBOARD_LAYOUT)
        }
    }

    @Published var navigationConfig: NavigationConfig = NavigationConfig.companion.default() {
        didSet {
            let serialized = NavigationConfigSerializer.shared.serialize(config: navigationConfig)
            UserDefaults.standard.set(serialized, forKey: PreferenceKeys.shared.NAVIGATION_CONFIG)
            loadCustomTabLayouts()
        }
    }

    func loadDashboardLayout() {
        if let raw = UserDefaults.standard.string(forKey: PreferenceKeys.shared.DASHBOARD_LAYOUT),
           let layout = DashboardLayoutSerializer.shared.deserialize(input: raw) {
            dashboardLayout = layout
        } else {
            dashboardLayout = DashboardLayout.companion.default()
        }
    }

    func loadNavigationConfig() {
        if let raw = UserDefaults.standard.string(forKey: PreferenceKeys.shared.NAVIGATION_CONFIG),
           let config = NavigationConfigSerializer.shared.deserialize(input: raw) {
            navigationConfig = config
        } else {
            navigationConfig = NavigationConfig.companion.default()
        }
    }

    func applyPreset(_ preset: DashboardPreset) {
        dashboardLayout = preset.layout
    }

    // MARK: - Custom Tab Layouts

    @Published var customTabLayouts: [String: DashboardLayout] = [:]

    func loadCustomTabLayouts() {
        var layouts: [String: DashboardLayout] = [:]
        for tab in Array(navigationConfig.customTabs) {
            let key = "custom_tab_\(tab.id)_layout"
            if let raw = UserDefaults.standard.string(forKey: key),
               let layout = DashboardLayoutSerializer.shared.deserialize(input: raw) {
                layouts[tab.id] = layout
            } else {
                layouts[tab.id] = DashboardLayout.companion.default()
            }
        }
        customTabLayouts = layouts
    }

    func saveCustomTabLayout(tabId: String, layout: DashboardLayout) {
        let key = "custom_tab_\(tabId)_layout"
        let serialized = DashboardLayoutSerializer.shared.serialize(layout: layout)
        UserDefaults.standard.set(serialized, forKey: key)
        customTabLayouts[tabId] = layout
    }

    func deleteCustomTabLayout(tabId: String) {
        let key = "custom_tab_\(tabId)_layout"
        UserDefaults.standard.removeObject(forKey: key)
        customTabLayouts.removeValue(forKey: tabId)
    }

    // MARK: - Saved Wheel Profiles (KMP-backed)

    private let wheelProfileStore = WheelProfileStore(store: UserDefaultsKeyValueStore(defaults: .standard))

    @Published private(set) var savedAddresses: Set<String> = Set()

    private func refreshSavedAddresses() {
        savedAddresses = wheelProfileStore.getSavedAddresses()
    }

    func getSavedDisplayName(address: String) -> String? {
        wheelProfileStore.getDisplayName(address: address)
    }

    func saveProfile(address: String, displayName: String, wheelTypeName: String) {
        wheelProfileStore.saveProfile(profile: WheelProfile(
            address: address,
            displayName: displayName,
            wheelTypeName: wheelTypeName,
            lastConnectedMs: Int64(Date().timeIntervalSince1970 * 1000)
        ))
        refreshSavedAddresses()
    }

    func forgetProfile(address: String) {
        wheelProfileStore.deleteProfile(address: address)
        refreshSavedAddresses()
    }

    // MARK: - KMP Components

    private var bleManager: BleManager?
    private var connectionManager: WheelConnectionManager?

    // MARK: - Demo Data Provider (KMP)

    private let demoProvider = WheelConnectionManagerHelper.shared.createDemoProvider()

    // MARK: - Feature Managers

    let alarmManager: AlarmManager
    private var autoConnectManager: AutoConnectManager?
    let rideLogger: RideLogger
    let rideStore: RideStore
    private let captureLogger: FreeWheelCore.BleCaptureLogger
    let locationManager: LocationManager
    let backgroundManager: BackgroundManager
    let telemetryBuffer: TelemetryBuffer
    let telemetryHistory: TelemetryHistoryBridge

    // MARK: - Connection Tracking

    private var lastConnectedAddress: String?
    private var previousConnectionState: ConnectionStateWrapper = .disconnected

    // MARK: - Flow Observers

    private var telemetryObserver: FlowObservation?
    private var identityObserver: FlowObservation?
    private var bmsObserver: FlowObservation?
    private var settingsObserver: FlowObservation?
    private var connectionStateObserver: FlowObservation?
    private var capabilitiesObserver: FlowObservation?
    private var autoConnectingObserver: FlowObservation?
    private var reconnectStateObserver: FlowObservation?
    private var bluetoothStateObserver: FlowObservation?
    private var demoTelemetryObserver: FlowObservation?
    private var demoIdentityObserver: FlowObservation?
    private var demoBmsObserver: FlowObservation?

    // MARK: - Initialization

    // One-time migration of iOS preference keys to canonical (Android-matching) names.
    // Runs synchronously before any UserDefaults reads in property initializers.
    private static let _migrationOnce: Void = {
        let defaults = UserDefaults.standard
        guard defaults.object(forKey: "PreferenceKeysMigrated_v1") == nil else { return }
        let migrations: [(old: String, new: String)] = [
            ("auto_start_logging", PreferenceKeys.shared.AUTO_LOG),
            ("log_gps", PreferenceKeys.shared.LOG_LOCATION_DATA),
            ("pwm_based_alarms", PreferenceKeys.shared.ALTERED_ALARMS),
            ("FreeWheelSavedAddresses", PreferenceKeys.shared.SAVED_WHEEL_ADDRESSES),
        ]
        for (old, new) in migrations {
            if defaults.object(forKey: old) != nil && defaults.object(forKey: new) == nil {
                defaults.set(defaults.object(forKey: old), forKey: new)
            }
        }
        defaults.set(true, forKey: "PreferenceKeysMigrated_v1")
    }()

    /// Designated init — nonisolated so it can be called from any context (e.g. tests).
    /// Pass pre-created sub-managers for dependency injection.
    nonisolated init(
        alarmManager: AlarmManager,
        rideLogger: RideLogger,
        rideStore: RideStore,
        captureLogger: FreeWheelCore.BleCaptureLogger,
        locationManager: LocationManager,
        backgroundManager: BackgroundManager,
        telemetryBuffer: TelemetryBuffer,
        telemetryHistory: TelemetryHistoryBridge
    ) {
        self.alarmManager = alarmManager
        self.rideLogger = rideLogger
        self.rideStore = rideStore
        self.captureLogger = captureLogger
        self.locationManager = locationManager
        self.backgroundManager = backgroundManager
        self.telemetryBuffer = telemetryBuffer
        self.telemetryHistory = telemetryHistory
        // Trigger one-time key migration before anything else
        _ = Self._migrationOnce
        // Setup happens in Task
        Task { @MainActor in
            self.setupKmpComponents()
            self.setupAlarmCallbacks()
            self.startObserving()
            self.loadNavigationConfig()
            self.loadCustomTabLayouts()
            self.rideStore.initialize()
            self.backgroundManager.requestNotificationPermission()

            self.refreshSavedAddresses()
            self.startupScan()

            // Auto-enable mock mode on simulator
            #if targetEnvironment(simulator)
            self.isMockMode = true
            #endif
        }
    }

    /// Convenience init — creates default sub-managers. Used by production code.
    @MainActor convenience init() {
        self.init(
            alarmManager: AlarmManager(),
            rideLogger: RideLogger(),
            rideStore: RideStore(),
            captureLogger: FreeWheelCore.BleCaptureLogger(fileWriter: FileWriter()),
            locationManager: LocationManager(),
            backgroundManager: BackgroundManager(),
            telemetryBuffer: TelemetryBuffer(),
            telemetryHistory: TelemetryHistoryBridge()
        )
    }

    private var startupScanTarget: String?

    private func startupScan() {
        guard let storedUUID = UserDefaults.standard.string(forKey: "FreeWheelLastPeripheralUUID"),
              !storedUUID.isEmpty else {
            return
        }
        guard bleManager != nil else { return }

        startupScanTarget = storedUUID

        // CBCentralManager needs time to reach poweredOn on cold launch
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_000_000_000) // 1 second
            self.startScan()
        }
    }

    deinit {
        telemetryObserver?.close()
        identityObserver?.close()
        bmsObserver?.close()
        settingsObserver?.close()
        connectionStateObserver?.close()
        capabilitiesObserver?.close()
        autoConnectingObserver?.close()
        reconnectStateObserver?.close()
        demoTelemetryObserver?.close()
        demoIdentityObserver?.close()
        demoBmsObserver?.close()
        replayTelemetryObserver?.close()
        replayStateObserver?.close()
        replayPositionObserver?.close()
        replaySpeedObserver?.close()
        WheelConnectionManagerHelper.shared.stopDemo(provider: demoProvider)
        WheelConnectionManagerHelper.shared.stopReplay(engine: replayEngine)

        // Finalize ride recording if still active
        if Thread.isMainThread {
            MainActor.assumeIsolated {
                if isCapturing { stopCapture() }
                if isLogging {
                    if let metadata = rideLogger.stopLogging(currentDistance: telemetry?.totalDistanceKm ?? 0) {
                        rideStore.addRide(metadata)
                    }
                    isLogging = false
                }
            }
        }
    }

    private func setupKmpComponents() {
        // Initialize KMP BLE manager
        bleManager = BleManager()
        bleManager?.initialize(restoreIdentifier: "FreeWheelBLE")

        // Create WheelConnectionManager using iOS factory
        guard let ble = bleManager else { return }
        connectionManager = WheelConnectionManagerHelper.shared.create(bleManager: ble)

        // Wire capture callback if capture was started before connection
        if isCapturing, let cm = connectionManager {
            wireCaptureCallback(cm)
        }

        // Wire unhandled frame callback
        if let cm = connectionManager {
            wireUnhandledCallback(cm)
        }

        // Wire BLE data to connection manager
        bleManager?.setDataReceivedCallback { [weak self] data in
            self?.connectionManager?.onDataReceived(data: data)
        }

        // Create shared auto-connect manager
        guard let cm = connectionManager else { return }
        autoConnectManager = WheelConnectionManagerHelper.shared.createAutoConnectManager(manager: cm)

        // Wire service discovery to connection manager
        bleManager?.setServicesDiscoveredCallback { [weak self] services, deviceName in
            self?.connectionManager?.onServicesDiscovered(services: services, deviceName: deviceName)
        }

        // Wire BLE errors to connection manager
        bleManager?.setBleErrorCallback { [weak self] in
            self?.connectionManager?.onBleError()
        }

        // Wire OS-level disconnects to connection manager
        bleManager?.setBleDisconnectedCallback { [weak self] address, reason in
            self?.connectionManager?.onBleDisconnected(address: address, reason: reason)
        }
    }

    private func setupAlarmCallbacks() {
        alarmManager.sendWheelBeep = { [weak self] in
            self?.wheelBeep()
        }

        alarmManager.onAlarmFired = { [weak self] displayType, message in
            guard let self = self else { return }
            if self.backgroundManager.isInBackground {
                self.backgroundManager.postAlarmNotification(type: displayType, value: message)
            }
        }
    }

    // MARK: - Mock Mode

    func startMockMode() {
        isMockMode = true
        connectionState = .connecting(address: AppConstants.shared.DEMO_DEVICE_ADDRESS)

        // Brief delay to simulate connection
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self = self else { return }
            WheelConnectionManagerHelper.shared.startDemo(provider: self.demoProvider)
            self.connectionState = .connected(address: AppConstants.shared.DEMO_DEVICE_ADDRESS, wheelName: AppConstants.shared.DEMO_WHEEL_NAME)
            self.startDemoObserving()
        }
    }

    func stopMockMode() {
        demoTelemetryObserver?.close()
        demoTelemetryObserver = nil
        demoIdentityObserver?.close()
        demoIdentityObserver = nil
        demoBmsObserver?.close()
        demoBmsObserver = nil
        WheelConnectionManagerHelper.shared.stopDemo(provider: demoProvider)
        if isCapturing { stopCapture() }
        if isLogging { stopLogging() }
        telemetryBuffer.clear()
        alarmManager.reset()
        activeAlarms = []
        isMockMode = false
        connectionState = .disconnected
        telemetry = nil
        identity = WheelIdentity.companion.empty()
        bmsState = BmsState.companion.empty()
        wheelSettings = WheelSettings.None.shared
        capabilities = CapabilitySet.companion.empty()
    }

    private func startDemoObserving() {
        let helper = WheelConnectionManagerHelper.shared
        demoTelemetryObserver = helper.observeDemoTelemetry(provider: demoProvider) { [weak self] tel in
            Task { @MainActor in
                self?.handleDemoTelemetryUpdate(tel)
            }
        }
        demoIdentityObserver = helper.observeDemoIdentity(provider: demoProvider) { [weak self] id in
            Task { @MainActor in
                self?.identity = id
            }
        }
        demoBmsObserver = helper.observeDemoBms(provider: demoProvider) { [weak self] bms in
            Task { @MainActor in
                self?.bmsState = bms
            }
        }
    }

    private func handleDemoTelemetryUpdate(_ newTelemetry: TelemetryState) {
        telemetry = newTelemetry

        // Feed telemetry buffer and history for chart view
        let gpsSpeed = ByteUtils.shared.metersPerSecondToKmh(speedMs: max(0, locationManager.currentLocation?.speed ?? 0))
        let sample = FreeWheelCore.TelemetrySample.companion.fromTelemetry(
            telemetry: newTelemetry,
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            gpsSpeedKmh: gpsSpeed
        )
        telemetryBuffer.addSampleIfNeeded(sample: sample)
        telemetryHistory.addSample(sample)

        // Check alarms via KMP
        let alarmConfig = buildAlarmConfig()
        alarmManager.checkTelemetry(telemetry: newTelemetry, config: alarmConfig, enabled: alarmsEnabled, action: alarmAction)
        activeAlarms = alarmManager.activeAlarms
    }

    // MARK: - Test Data Injection

    /// Inject raw BLE packet for testing the KMP decoder pipeline.
    /// Use this to test with recorded wheel data on simulator.
    func injectTestData(_ hexString: String) {
        guard let cm = connectionManager else {
            print("Connection manager not initialized")
            return
        }

        // Convert hex string to KotlinByteArray
        let bytes = hexStringToBytes(hexString)
        let kotlinBytes = KotlinByteArray(size: Int32(bytes.count))
        for (index, byte) in bytes.enumerated() {
            kotlinBytes.set(index: Int32(index), value: byte)
        }

        cm.onDataReceived(data: kotlinBytes)
    }

    /// Start a test session with a specific wheel type.
    /// This simulates connecting without actual BLE.
    func startTestSession(wheelType: WheelType) {
        isTestMode = true
        connectionManager?.onWheelTypeDetected(wheelType: wheelType)
        connectionState = .connected(address: "TEST-DEVICE", wheelName: wheelType.name)
    }

    func stopTestMode() {
        isTestMode = false
        connectionState = .disconnected
        telemetry = nil
        identity = WheelIdentity.companion.empty()
        bmsState = BmsState.companion.empty()
        wheelSettings = WheelSettings.None.shared
        capabilities = CapabilitySet.companion.empty()
        telemetryBuffer.clear()
    }

    private func hexStringToBytes(_ hex: String) -> [Int8] {
        let cleanHex = hex.replacingOccurrences(of: " ", with: "")
        var bytes: [Int8] = []
        var index = cleanHex.startIndex
        while index < cleanHex.endIndex {
            let nextIndex = cleanHex.index(index, offsetBy: 2)
            if let byte = UInt8(cleanHex[index..<nextIndex], radix: 16) {
                bytes.append(Int8(bitPattern: byte))
            }
            index = nextIndex
        }
        return bytes
    }

    // MARK: - Flow Observation

    private func startObserving() {
        guard let cm = connectionManager else { return }
        let helper = WheelConnectionManagerHelper.shared

        // Observe granular sub-states from WheelConnectionManager
        telemetryObserver = helper.observeTelemetryState(manager: cm) { [weak self] tel in
            Task { @MainActor in
                guard let self = self, !self.isMockMode else { return }
                self.handleTelemetryUpdate(tel)
            }
        }
        identityObserver = helper.observeIdentityState(manager: cm) { [weak self] id in
            Task { @MainActor in
                guard let self = self, !self.isMockMode else { return }
                self.identity = id
            }
        }
        bmsObserver = helper.observeBmsState(manager: cm) { [weak self] bms in
            Task { @MainActor in
                guard let self = self, !self.isMockMode else { return }
                self.bmsState = bms
            }
        }
        settingsObserver = helper.observeSettingsState(manager: cm) { [weak self] settings in
            Task { @MainActor in
                guard let self = self, !self.isMockMode else { return }
                // Auto-set useMph based on wheel's reported miles setting
                if settings.inMiles != self.wheelSettings.inMiles {
                    self.useMph = settings.inMiles
                }
                self.wheelSettings = settings
            }
        }

        // Observe capabilities — drives settings UI filtering
        capabilitiesObserver = helper.observeCapabilities(manager: cm) { [weak self] caps in
            Task { @MainActor in
                self?.capabilities = caps
            }
        }

        // Observe event log entries (Veteran/Leaperkim)
        eventLogObserver = helper.observeEventLogEntries(manager: cm) { [weak self] entries in
            Task { @MainActor in
                self?.eventLogEntries = entries
            }
        }

        // Observe connection state — handles state transitions, scanning flag
        connectionStateObserver = helper.observeConnectionState(manager: cm) { [weak self] kmpState in
            Task { @MainActor in
                guard let self = self, !self.isMockMode, !self.isTestMode else { return }
                let newState = ConnectionStateWrapper(from: kmpState)
                guard newState != self.connectionState else { return }
                self.handleConnectionStateChange(from: self.connectionState, to: newState)
                self.connectionState = newState

                // Update scanning state
                if case .scanning = newState {
                    self.isScanning = true
                } else if self.isScanning {
                    self.isScanning = false
                }
            }
        }

        // Observe Bluetooth adapter state — drives permission/power UI in ScanView
        if let ble = bleManager {
            bluetoothStateObserver = helper.observeBluetoothState(bleManager: ble) { [weak self] state in
                Task { @MainActor in
                    self?.bluetoothState = state
                }
            }
        }

        // Observe auto-connect manager state
        if let acm = autoConnectManager {
            autoConnectingObserver = helper.observeAutoConnecting(manager: acm) { [weak self] value in
                Task { @MainActor in
                    self?.isAutoConnecting = value.boolValue
                }
            }

            reconnectStateObserver = helper.observeReconnectState(manager: acm) { [weak self] kmpState in
                Task { @MainActor in
                    guard let self = self else { return }
                    let helper = WheelConnectionManagerHelper.shared
                    if helper.isReconnectIdle(state: kmpState) {
                        self.reconnectState = .idle
                    } else if helper.isReconnectWaiting(state: kmpState) {
                        self.reconnectState = .waiting(
                            attempt: Int(helper.reconnectAttemptNumber(state: kmpState)),
                            nextRetryMs: helper.reconnectNextRetryMs(state: kmpState)
                        )
                    } else if helper.isReconnectAttempting(state: kmpState) {
                        self.reconnectState = .attempting(
                            attempt: Int(helper.reconnectAttemptNumber(state: kmpState))
                        )
                    }
                }
            }
        }
    }

    /// Handle a new TelemetryState emission from the KMP flow.
    /// Runs all telemetry side-effects: alarms, logging, telemetry buffer.
    private func handleTelemetryUpdate(_ newTelemetry: TelemetryState?) {
        telemetry = newTelemetry
        guard let newTelemetry = newTelemetry else { return }

        // Check alarms when connected
        if connectionState.isConnected {
            let alarmConfig = buildAlarmConfig()
            alarmManager.checkTelemetry(telemetry: newTelemetry, config: alarmConfig, enabled: alarmsEnabled, action: alarmAction)
            activeAlarms = alarmManager.activeAlarms

            // Auto-torch
            checkAutoTorch(telemetry: newTelemetry)
        }

        // Write ride log sample
        if isLogging {
            rideLogger.writeTelemetrySample(
                telemetry: newTelemetry,
                modeStr: identity.modeStr,
                location: locationManager.currentLocation,
                includeGPS: logGPS
            )
        }

        // Range estimate
        if startBattery < 0 && newTelemetry.batteryLevel > 0 {
            startBattery = Int(newTelemetry.batteryLevel)
        }
        if startBattery > 0 {
            let estimate = RangeEstimator.shared.estimate(
                currentBattery: newTelemetry.batteryLevel,
                tripDistanceKm: newTelemetry.wheelDistanceKm,
                startBattery: Int32(startBattery)
            )
            rangeEstimateKm = estimate?.doubleValue
        }

        // Telemetry buffer + history
        if connectionState.isConnected {
            let gpsSpeed = ByteUtils.shared.metersPerSecondToKmh(speedMs: max(0, locationManager.currentLocation?.speed ?? 0))
            let sample = FreeWheelCore.TelemetrySample.companion.fromTelemetry(
                telemetry: newTelemetry,
                timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
                gpsSpeedKmh: gpsSpeed
            )
            telemetryBuffer.addSampleIfNeeded(sample: sample)
            telemetryHistory.addSample(sample)
        }
    }

    // MARK: - Auto-Torch

    private func checkAutoTorch(telemetry: TelemetryState) {
        guard autoTorchEnabled else {
            if autoTorchLightRequested {
                autoTorchLightRequested = false
                isLightOn = false
                if let cm = connectionManager {
                    WheelConnectionManagerHelper.shared.sendToggleLight(manager: cm, enabled: false)
                }
            }
            return
        }

        // User manually toggled light — back off until reconnect
        if autoTorchManualOverride { return }

        let location = locationManager.currentLocation
        let result = AutoTorchEngine.shared.shouldLightBeOn(
            speedKmh: telemetry.speedKmh,
            speedThresholdKmh: Int32(autoTorchSpeedThreshold),
            useSunset: autoTorchUseSunset,
            latitudeDeg: location?.coordinate.latitude ?? 0.0,
            longitudeDeg: location?.coordinate.longitude ?? 0.0,
            epochMillis: Int64(Date().timeIntervalSince1970 * 1000)
        )

        if result.shouldBeOn && !autoTorchLightRequested {
            autoTorchLightRequested = true
            isLightOn = true
            if let cm = connectionManager {
                WheelConnectionManagerHelper.shared.sendToggleLight(manager: cm, enabled: true)
            }
        } else if !result.shouldBeOn && autoTorchLightRequested {
            autoTorchLightRequested = false
            isLightOn = false
            if let cm = connectionManager {
                WheelConnectionManagerHelper.shared.sendToggleLight(manager: cm, enabled: false)
            }
        }
    }

    // MARK: - Alarm Config Builder

    private func buildAlarmConfig() -> AlarmConfig {
        return WheelConnectionManagerHelper.shared.createAlarmConfig(
            pwmBasedAlarms: pwmBasedAlarms,
            alarmFactor1: Int32(alarmFactor1),
            alarmFactor2: Int32(alarmFactor2),
            warningPwm: Int32(warningPwm),
            warningSpeed: Int32(warningSpeed),
            warningSpeedPeriod: Int32(warningSpeedPeriod),
            alarm1Speed: Int32(alarm1Speed),
            alarm1Battery: Int32(alarm1Battery),
            alarm2Speed: Int32(alarm2Speed),
            alarm2Battery: Int32(alarm2Battery),
            alarm3Speed: Int32(alarm3Speed),
            alarm3Battery: Int32(alarm3Battery),
            alarmCurrent: Int32(alarmCurrent),
            alarmPhaseCurrent: Int32(alarmPhaseCurrent),
            alarmTemperature: Int32(alarmTemperature),
            alarmMotorTemperature: Int32(alarmMotorTemperature),
            alarmBattery: Int32(alarmBattery),
            alarmWheel: alarmWheel
        )
    }

    // MARK: - Connection State Changes

    private func handleConnectionStateChange(from oldState: ConnectionStateWrapper, to newState: ConnectionStateWrapper) {
        // Track connected address
        if case .connected(let address, _) = newState {
            lastConnectedAddress = address
            UserDefaults.standard.set(address, forKey: "FreeWheelLastPeripheralUUID")
            // Auto-connect flags are cleared automatically by the shared AutoConnectManager
            // via its connection state observer

            // Push current unit preferences to decoder
            pushDecoderConfig()

            // Auto-save wheel profile
            let displayName = identity.displayName == "Dashboard" ? "" : identity.displayName
            saveProfile(address: address, displayName: displayName, wheelTypeName: identity.wheelType.name)

            // Load telemetry history for this wheel
            telemetryHistory.loadForWheel(address: address)

            // Load per-wheel dashboard layout
            loadDashboardLayout()

            // Start GPS tracking for speed tile / telemetry
            locationManager.startTracking()

            // Auto-start logging if enabled
            if autoStartLogging && !isLogging {
                startLogging()
            }

            // Start error log session
            startErrorLogSession(address: address)
        }

        // Connection lost — OS-level auto-reconnect handles mid-ride reconnection
        // (centralManager.connect in onPeripheralDisconnected). Don't use
        // AutoConnectManager here — it would cancel the OS reconnect by calling
        // WCM.connect() which tears down + rebuilds. AutoConnectManager is only
        // for startup auto-connect.
        if case .connectionLost(_, let reason) = newState {
            endErrorLogSession(reason: reason)
            if backgroundManager.isInBackground {
                let wheelName = identity.displayName
                backgroundManager.postConnectionLostNotification(wheelName: wheelName)
            }

            locationManager.stopTracking()
        }

        // Also handle explicit disconnected state
        if case .disconnected = newState, oldState.isConnected {
            endErrorLogSession(reason: "User disconnect")
            if isCapturing { stopCapture() }
            if isLogging {
                stopLogging()
            }
            locationManager.stopTracking()
            telemetryHistory.save()
            telemetryBuffer.clear()
            alarmManager.reset()
            activeAlarms = []
        }
    }

    // MARK: - DecoderConfig Propagation

    private func pushDecoderConfig() {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.updateDecoderConfig(manager: cm, useMph: useMph, useFahrenheit: useFahrenheit)
    }

    // MARK: - Unit Sync (handled by settings observer)

    // MARK: - Wheel Commands

    func wheelBeep() {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendBeep(manager: cm)
    }

    func toggleLight() {
        guard let cm = connectionManager else { return }
        isLightOn.toggle()
        // If auto-torch is active, latch manual override so it stops controlling the light
        if autoTorchEnabled {
            autoTorchManualOverride = true
        }
        WheelConnectionManagerHelper.shared.sendToggleLight(manager: cm, enabled: isLightOn)
    }

    func setPedalsMode(_ mode: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetPedalsMode(manager: cm, mode: Int32(mode))
    }

    // MARK: - Lighting Commands

    func setLightMode(_ mode: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetLightMode(manager: cm, mode: Int32(mode))
    }

    func setLed(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetLed(manager: cm, enabled: enabled)
    }

    func setLedMode(_ mode: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetLedMode(manager: cm, mode: Int32(mode))
    }

    func setStrobeMode(_ mode: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetStrobeMode(manager: cm, mode: Int32(mode))
    }

    func setTailLight(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetTailLight(manager: cm, enabled: enabled)
    }

    func setDrl(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetDrl(manager: cm, enabled: enabled)
    }

    func setLedColor(_ value: Int, ledNum: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetLedColor(manager: cm, value: Int32(value), ledNum: Int32(ledNum))
    }

    func setLightBrightness(_ value: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetLightBrightness(manager: cm, value: Int32(value))
    }

    // MARK: - Speed & Alarm Commands

    func setMaxSpeed(_ speed: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetMaxSpeed(manager: cm, speed: Int32(speed))
    }

    func setAlarmSpeed(_ speed: Int, num: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetAlarmSpeed(manager: cm, speed: Int32(speed), num: Int32(num))
    }

    func setAlarmEnabled(_ enabled: Bool, num: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetAlarmEnabled(manager: cm, enabled: enabled, num: Int32(num))
    }

    func setLimitedMode(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetLimitedMode(manager: cm, enabled: enabled)
    }

    func setLimitedSpeed(_ speed: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetLimitedSpeed(manager: cm, speed: Int32(speed))
    }

    func setAlarmMode(_ mode: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetAlarmMode(manager: cm, mode: Int32(mode))
    }

    func setKingsongAlarms(a1: Int, a2: Int, a3: Int, max: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetKingsongAlarms(manager: cm, a1: Int32(a1), a2: Int32(a2), a3: Int32(a3), max: Int32(max))
    }

    func requestAlarmSettings() {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendRequestAlarmSettings(manager: cm)
    }

    // MARK: - Ride Mode Commands

    func setHandleButton(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetHandleButton(manager: cm, enabled: enabled)
    }

    func setBrakeAssist(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetBrakeAssist(manager: cm, enabled: enabled)
    }

    func setTransportMode(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetTransportMode(manager: cm, enabled: enabled)
    }

    func setRideMode(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetRideMode(manager: cm, enabled: enabled)
    }

    func setGoHomeMode(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetGoHomeMode(manager: cm, enabled: enabled)
    }

    func setFancierMode(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetFancierMode(manager: cm, enabled: enabled)
    }

    func setRollAngleMode(_ mode: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetRollAngleMode(manager: cm, mode: Int32(mode))
    }

    // MARK: - Audio Commands

    func setMute(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetMute(manager: cm, enabled: enabled)
    }

    func setSpeakerVolume(_ volume: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetSpeakerVolume(manager: cm, volume: Int32(volume))
    }

    func setBeeperVolume(_ volume: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetBeeperVolume(manager: cm, volume: Int32(volume))
    }

    // MARK: - Thermal Commands

    func setFanQuiet(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetFanQuiet(manager: cm, enabled: enabled)
    }

    func setFan(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetFan(manager: cm, enabled: enabled)
    }

    // MARK: - Pedal Tuning Commands

    func setPedalTilt(_ angle: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetPedalTilt(manager: cm, angle: Int32(angle))
    }

    func setPedalSensitivity(_ sensitivity: Int) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetPedalSensitivity(manager: cm, sensitivity: Int32(sensitivity))
    }

    // MARK: - System Commands

    func calibrate() {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendCalibrate(manager: cm)
    }

    func powerOff() {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendPowerOff(manager: cm)
    }

    func setLock(_ locked: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetLock(manager: cm, locked: locked)
    }

    func resetTrip() {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendResetTrip(manager: cm)
    }

    // MARK: - Event Log (Veteran/Leaperkim)

    @Published private(set) var eventLogEntries: [EventLogEntry] = []
    private var eventLogObserver: FlowObservation?

    func requestEventLog() {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendRequestEventLog(manager: cm)
    }

    func clearEventLog() {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendClearEventLog(manager: cm)
    }

    // MARK: - Generic Command Dispatch

    func executeCommand(_ commandId: SettingsCommandId, intValue: Int32 = 0, boolValue: Bool = false) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.executeCommand(
            manager: cm,
            commandId: commandId,
            intValue: intValue,
            boolValue: boolValue
        )
    }

    func setMilesMode(_ enabled: Bool) {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendSetMilesMode(manager: cm, enabled: enabled)
    }

    // MARK: - Ride Logging (Feature 3)

    func startLogging() {
        if rideLogger.startLogging(includeGPS: logGPS) {
            isLogging = true
        }
    }

    func stopLogging() {
        if let metadata = rideLogger.stopLogging(currentDistance: telemetry?.totalDistanceKm ?? 0) {
            rideStore.addRide(metadata)
        }
        isLogging = false
    }

    // MARK: - BLE Capture

    func startCapture() {
        let capturesDir = Self.capturesDirectory()
        do {
            try FileManager.default.createDirectory(at: capturesDir, withIntermediateDirectories: true)
        } catch {
            print("Failed to create captures directory: \(error)")
            return
        }

        let now = Date()
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy_MM_dd_HH_mm_ss"
        let fileName = "capture_\(formatter.string(from: now)).csv"
        let filePath = capturesDir.appendingPathComponent(fileName).path

        let wheelTypeName = identity.wheelType.name
        let wheelName = identity.displayName
        let firmware = identity.version
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""

        guard captureLogger.start(
            filePath: filePath,
            wheelTypeName: wheelTypeName,
            wheelName: wheelName,
            firmware: firmware,
            appVersion: appVersion,
            currentTimeMs: nowMs
        ) else { return }

        if let cm = connectionManager {
            wireCaptureCallback(cm)
        }

        captureRxCount = 0
        captureTxCount = 0
        captureMarkerCount = 0
        captureStartTime = now
        isCapturing = true
    }

    private func wireCaptureCallback(_ cm: WheelConnectionManager) {
        WheelConnectionManagerHelper.shared.setCaptureCallback(manager: cm) { [weak self] data, directionStr, annotation in
            Task { @MainActor in
                guard let self = self else { return }
                let direction: FreeWheelCore.BlePacketDirection = directionStr == "TX" ? .tx : .rx
                let currentMs = Int64(Date().timeIntervalSince1970 * 1000)
                self.captureLogger.logPacket(data: data, direction: direction, currentTimeMs: currentMs, decodeAnnotation: annotation)
                if directionStr == "TX" {
                    self.captureTxCount += 1
                } else {
                    self.captureRxCount += 1
                }
            }
        }
    }

    private func wireUnhandledCallback(_ cm: WheelConnectionManager) {
        WheelConnectionManagerHelper.shared.setUnhandledCallback(manager: cm) { [weak self] reason, frameData in
            Task { @MainActor in
                guard let self = self else { return }
                let currentMs = Int64(Date().timeIntervalSince1970 * 1000)
                self.unhandledCollector.record(reason: reason, frameData: frameData, currentTimeMs: currentMs)
                self.unhandledCount = Int(self.unhandledCollector.count())
            }
        }
    }

    func stopCapture() {
        if let cm = connectionManager {
            WheelConnectionManagerHelper.shared.setCaptureCallback(manager: cm, callback: nil)
        }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let footer = buildDiagnosticFooter()
        _ = captureLogger.stop(currentTimeMs: nowMs, diagnosticFooter: footer)
        isCapturing = false
        captureStartTime = nil
    }

    /// Build shareable text of unhandled frames. Returns nil if none recorded.
    func buildUnhandledFramesText() -> String? {
        let caps = capabilities
        return UnhandledFrameFormatter.shared.format(
            entries: unhandledCollector.getEntries(),
            wheelType: identity.wheelType.name,
            model: caps.detectedModel.isEmpty ? identity.model : caps.detectedModel,
            firmware: caps.firmwareVersion.isEmpty ? identity.version : caps.firmwareVersion,
            platform: "ios"
        )
    }

    /// Build diagnostic text for clipboard sharing. Returns nil if not connected.
    func buildDiagnosticText() -> String? {
        guard let cm = connectionManager else { return nil }
        let snapshot = DiagnosticSnapshotBuilder.shared.buildSnapshot(
            identity: identity,
            capabilities: capabilities,
            connectionInfo: cm.getConnectionInfo(),
            decoderConfig: cm.getConfig(),
            platform: "ios",
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        )
        return DiagnosticSnapshotBuilder.shared.formatAsText(snapshot: snapshot)
    }

    private func buildDiagnosticFooter() -> String? {
        guard let cm = connectionManager else { return nil }
        let snapshot = DiagnosticSnapshotBuilder.shared.buildSnapshot(
            identity: identity,
            capabilities: capabilities,
            connectionInfo: cm.getConnectionInfo(),
            decoderConfig: cm.getConfig(),
            platform: "ios",
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        )
        return DiagnosticSnapshotBuilder.shared.formatAsCommentBlock(snapshot: snapshot)
    }

    func insertCaptureMarker(_ label: String) {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        captureLogger.insertMarker(label: label, currentTimeMs: nowMs)
        if isCapturing {
            captureMarkerCount += 1
        }
    }

    static func capturesDirectory() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("captures")
    }

    // MARK: - Connection Error Log

    private func startErrorLogSession(address: String) {
        endErrorLogSession(reason: nil)
        let dir = Self.errorLogsDirectory()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let now = Date()
        let nowMs = Int64(now.timeIntervalSince1970 * 1000)
        let wheelTypeName = identity.wheelType.name.lowercased()
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy_MM_dd_HH_mm_ss"
        let fileName = "\(formatter.string(from: now))_\(wheelTypeName).csv"
        let fileURL = dir.appendingPathComponent(fileName)
        FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        guard let handle = try? FileHandle(forWritingTo: fileURL) else { return }

        let header = WheelConnectionManagerHelper.shared.formatErrorLogHeader(
            wheelType: wheelTypeName,
            wheelName: identity.displayName,
            address: address,
            connectTimeMs: nowMs
        )
        if let data = (header + "\n").data(using: .utf8) { handle.write(data) }
        errorLogFileHandle = handle
        errorLogSessionStartMs = nowMs
        errorLogFrameCount = 0

        if let cm = connectionManager {
            WheelConnectionManagerHelper.shared.setErrorLogCallback(
                manager: cm,
                sessionStartMs: nowMs
            ) { [weak self] csvRow in
                Task { @MainActor in
                    guard let self = self, let handle = self.errorLogFileHandle else { return }
                    if let data = (csvRow + "\n").data(using: .utf8) { handle.write(data) }
                    self.errorLogFrameCount += 1
                }
            }
        }
    }

    private func endErrorLogSession(reason: String?) {
        guard let handle = errorLogFileHandle else { return }
        errorLogFileHandle = nil
        if let cm = connectionManager {
            WheelConnectionManagerHelper.shared.setErrorLogCallback(manager: cm, sessionStartMs: 0, callback: nil)
        }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let footer = WheelConnectionManagerHelper.shared.formatErrorLogFooter(
            disconnectTimeMs: nowMs,
            disconnectReason: reason ?? "Unknown",
            totalFramesDecoded: Int32(errorLogFrameCount)
        )
        if let data = (footer + "\n").data(using: .utf8) { handle.write(data) }
        handle.closeFile()
    }

    func errorLogFiles() -> [URL] {
        let dir = Self.errorLogsDirectory()
        guard let files = try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: [.contentModificationDateKey], options: .skipsHiddenFiles
        ) else { return [] }
        return files
            .filter { $0.pathExtension == "csv" }
            .sorted {
                let d1 = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let d2 = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                return d1 > d2
            }
    }

    func deleteErrorLog(at url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    func clearErrorLogs() {
        let dir = Self.errorLogsDirectory()
        if let files = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
            for file in files { try? FileManager.default.removeItem(at: file) }
        }
    }

    static func errorLogsDirectory() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("error_logs")
    }

    // MARK: - BLE Replay

    func startReplay(csvContent: String) {
        let helper = WheelConnectionManagerHelper.shared
        guard helper.loadCapture(engine: replayEngine, csvContent: csvContent) else { return }

        isReplayMode = true
        let wheelName = helper.getReplayWheelName(engine: replayEngine)
        let typeName = helper.getReplayWheelTypeName(engine: replayEngine)
        let displayName = wheelName.isEmpty ? typeName : wheelName
        connectionState = .connected(address: "replay", wheelName: displayName)

        startReplayObserving()
        helper.startReplay(engine: replayEngine)
    }

    func pauseReplay() {
        WheelConnectionManagerHelper.shared.pauseReplay(engine: replayEngine)
    }

    func resumeReplay() {
        WheelConnectionManagerHelper.shared.resumeReplay(engine: replayEngine)
    }

    func stopReplay() {
        stopReplayObserving()
        WheelConnectionManagerHelper.shared.stopReplay(engine: replayEngine)
        isReplayMode = false
        connectionState = .disconnected
        telemetry = nil
        identity = WheelIdentity.companion.empty()
        bmsState = BmsState.companion.empty()
        wheelSettings = WheelSettings.None.shared
        replayStateName = "IDLE"
        replayProgress = 0
        replayCurrentTimeMs = 0
        replayTotalDurationMs = 0
        replayPacketIndex = 0
        replayTotalPackets = 0
        replaySpeed = 1.0
        telemetryBuffer.clear()
    }

    func seekReplay(progress: Float) {
        WheelConnectionManagerHelper.shared.seekReplay(engine: replayEngine, progress: progress)
    }

    func setReplaySpeed(_ speed: Float) {
        WheelConnectionManagerHelper.shared.setReplaySpeed(engine: replayEngine, speed: speed)
    }

    private func startReplayObserving() {
        let helper = WheelConnectionManagerHelper.shared

        replayTelemetryObserver = helper.observeReplayTelemetry(engine: replayEngine) { [weak self] tel in
            Task { @MainActor in
                guard let self = self, self.isReplayMode else { return }
                self.telemetry = tel
            }
        }
        // Also observe identity/bms/settings for replay
        _ = helper.observeReplayIdentity(engine: replayEngine) { [weak self] id in
            Task { @MainActor in
                guard let self = self, self.isReplayMode else { return }
                self.identity = id
            }
        }
        _ = helper.observeReplayBms(engine: replayEngine) { [weak self] bms in
            Task { @MainActor in
                guard let self = self, self.isReplayMode else { return }
                self.bmsState = bms
            }
        }
        _ = helper.observeReplaySettings(engine: replayEngine) { [weak self] settings in
            Task { @MainActor in
                guard let self = self, self.isReplayMode else { return }
                self.wheelSettings = settings
            }
        }

        replayStateObserver = helper.observeReplayState(engine: replayEngine) { [weak self] stateName in
            Task { @MainActor in
                self?.replayStateName = stateName
            }
        }

        replayPositionObserver = helper.observeReplayPosition(engine: replayEngine) { [weak self] progress, currentMs, totalMs, packetIdx, totalPkts in
            Task { @MainActor in
                self?.replayProgress = progress.floatValue
                self?.replayCurrentTimeMs = currentMs.int64Value
                self?.replayTotalDurationMs = totalMs.int64Value
                self?.replayPacketIndex = packetIdx.int32Value
                self?.replayTotalPackets = totalPkts.int32Value
            }
        }

        replaySpeedObserver = helper.observeReplaySpeed(engine: replayEngine) { [weak self] speed in
            Task { @MainActor in
                self?.replaySpeed = speed.floatValue
            }
        }
    }

    private func stopReplayObserving() {
        replayTelemetryObserver?.close()
        replayTelemetryObserver = nil
        replayStateObserver?.close()
        replayStateObserver = nil
        replayPositionObserver?.close()
        replayPositionObserver = nil
        replaySpeedObserver?.close()
        replaySpeedObserver = nil
    }

    // MARK: - Background Mode (Feature 4)

    func onEnterBackground() {
        telemetryHistory.save()
        if connectionState.isConnected {
            backgroundManager.beginBackgroundTask()
        }
    }

    func onEnterForeground() {
        backgroundManager.endBackgroundTask()
    }

    // MARK: - Scanning

    func startScan() {
        guard let bleManager = bleManager else { return }

        isScanning = true
        discoveredDevices = []

        bleManager.startScan(onDeviceFound: { [weak self] device in
            Task { @MainActor in
                self?.onDeviceDiscovered(device)
            }
        }) { error in
            if let error = error {
                print("Scan error: \(error.localizedDescription)")
            }
        }
    }

    func stopScan() {
        guard let bleManager = bleManager else { return }

        bleManager.stopScan { [weak self] error in
            Task { @MainActor in
                self?.isScanning = false
                if let error = error {
                    print("Stop scan error: \(error.localizedDescription)")
                }
            }
        }
    }

    private func onDeviceDiscovered(_ device: BleDevice) {
        if !showUnknownDevices && (device.name == nil || device.name?.isEmpty == true) {
            return
        }
        let discovered = DiscoveredDevice(
            address: device.address,
            name: device.name ?? "Unknown",
            rssi: Int(device.rssi)
        )

        // Update or add device
        if let index = discoveredDevices.firstIndex(where: { $0.address == discovered.address }) {
            discoveredDevices[index] = discovered
        } else {
            discoveredDevices.append(discovered)
        }

        // Sort by RSSI (strongest first)
        discoveredDevices.sort { $0.rssi > $1.rssi }

        // Auto-connect if this is the startup scan target
        if let target = startupScanTarget, discovered.address == target {
            startupScanTarget = nil
            stopScan()
            connect(address: discovered.address)
        }
    }

    // MARK: - Connection

    func connect(address: String) {
        guard let connectionManager = connectionManager else { return }

        // Clear unhandled frames from previous session
        unhandledCollector.clear()
        unhandledCount = 0

        connectionState = .connecting(address: address)

        // Fire-and-forget — connection state updates come through StateFlow polling
        connectionManager.connect(address: address, wheelType: nil)
    }

    func stopReconnecting() {
        if let acm = autoConnectManager {
            WheelConnectionManagerHelper.shared.stopAutoConnect(manager: acm)
        }
    }

    func disconnect() {
        if isReplayMode {
            stopReplay()
            return
        }
        guard let connectionManager = connectionManager else { return }

        // Explicit disconnect — stop reconnection and clear saved address
        if let acm = autoConnectManager {
            WheelConnectionManagerHelper.shared.stopAutoConnect(manager: acm)
        }
        UserDefaults.standard.removeObject(forKey: "FreeWheelLastPeripheralUUID")

        // Reset range estimate and auto-torch override
        startBattery = -1
        rangeEstimateKm = nil
        autoTorchManualOverride = false

        // Fire-and-forget — cleanup happens in handleConnectionStateChange when
        // state transitions to .disconnected via StateFlow polling.
        connectionManager.disconnect()
    }
}

// MARK: - Swift Wrappers for KMP Types

/// Swift wrapper for KMP ConnectionState
enum ConnectionStateWrapper: Equatable {
    case disconnected
    case scanning
    case connecting(address: String)
    case discoveringServices(address: String)
    case connected(address: String, wheelName: String)
    case connectionLost(address: String, reason: String)
    case failed(address: String?, error: String)

    var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }

    var isConnecting: Bool {
        switch self {
        case .connecting, .discoveringServices:
            return true
        default:
            return false
        }
    }

    var isDisconnected: Bool {
        switch self {
        case .disconnected, .failed, .connectionLost:
            return true
        default:
            return false
        }
    }

    var connectingAddress: String? {
        switch self {
        case .connecting(let address), .discoveringServices(let address):
            return address
        default:
            return nil
        }
    }

    var failedAddress: String? {
        if case .failed(let address, _) = self { return address }
        return nil
    }

    var statusText: String {
        switch self {
        case .disconnected: return "Disconnected"
        case .scanning: return "Scanning..."
        case .connecting: return "Connecting..."
        case .discoveringServices: return "Discovering services..."
        case .connected(_, let name): return "Connected to \(name)"
        case .connectionLost(_, let reason): return "Connection lost: \(reason)"
        case .failed(_, let error): return "Failed: \(error)"
        }
    }

    init(from kmpState: ConnectionState) {
        switch kmpState {
        case is ConnectionState.Disconnected:
            self = .disconnected
        case is ConnectionState.Scanning:
            self = .scanning
        case let connecting as ConnectionState.Connecting:
            self = .connecting(address: connecting.address)
        case let discovering as ConnectionState.DiscoveringServices:
            self = .discoveringServices(address: discovering.address)
        case let connected as ConnectionState.Connected:
            self = .connected(address: connected.address, wheelName: connected.wheelName)
        case let lost as ConnectionState.ConnectionLost:
            self = .connectionLost(address: lost.address, reason: lost.reason)
        case let failed as ConnectionState.Failed:
            self = .failed(address: failed.address, error: failed.error)
        default:
            self = .disconnected
        }
    }
}

/// Swift representation of a discovered BLE device
struct DiscoveredDevice: Identifiable, Equatable {
    let id: String
    let address: String
    let name: String
    let rssi: Int

    init(address: String, name: String, rssi: Int) {
        self.id = address
        self.address = address
        self.name = name
        self.rssi = rssi
    }

    var rssiDescription: String {
        DisplayUtils.shared.signalDescription(rssi: Int32(rssi))
    }
}

// MARK: - Preview Support

#if DEBUG
extension WheelManager {
    /// Create a preview instance with mock data
    static func preview(
        connectionState: ConnectionStateWrapper = .disconnected,
        devices: [DiscoveredDevice] = []
    ) -> WheelManager {
        let manager = WheelManager()
        // Note: Would need to expose setters for preview, this is a placeholder
        return manager
    }
}
#endif
