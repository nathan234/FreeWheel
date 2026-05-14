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

    // Unit preferences (persisted via AppSettingsStore; GLOBAL scope)
    @Published var useMph: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.useMph) {
        didSet {
            appSettingsStore.setBool(id: AppSettingId.useMph, value: useMph)
            pushDecoderConfig()
        }
    }
    @Published var useFahrenheit: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.useFahrenheit) {
        didSet {
            appSettingsStore.setBool(id: AppSettingId.useFahrenheit, value: useFahrenheit)
            pushDecoderConfig()
        }
    }
    @Published var isLightOn: Bool = false

    @Published var speedDisplayMode: SpeedDisplayMode = WheelManager.initSettingsStore.getSpeedDisplayMode() {
        didSet { appSettingsStore.setSpeedDisplayMode(mode: speedDisplayMode) }
    }

    // Alarm settings — routed through AppSettingsStore so PER_WHEEL ids in AppSettingId
    // get MAC-prefixed in storage. @Published values are seeded from the last connected
    // wheel's slot at init and refreshed via reloadAlarmsFromStore() when the connected
    // wheel changes.
    @Published var alarmsEnabled: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.alarmsEnabled) {
        didSet { appSettingsStore.setBool(id: AppSettingId.alarmsEnabled, value: alarmsEnabled) }
    }
    @Published var alarm1Speed: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarm1Speed)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarm1Speed, value: Int32(alarm1Speed)) }
    }
    @Published var alarm2Speed: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarm2Speed)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarm2Speed, value: Int32(alarm2Speed)) }
    }
    @Published var alarm3Speed: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarm3Speed)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarm3Speed, value: Int32(alarm3Speed)) }
    }
    @Published var alarmCurrent: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarmCurrent)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarmCurrent, value: Int32(alarmCurrent)) }
    }
    @Published var alarmTemperature: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarmTemperature)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarmTemperature, value: Int32(alarmTemperature)) }
    }
    @Published var alarmBattery: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarmBattery)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarmBattery, value: Int32(alarmBattery)) }
    }

    // Alarm action — GLOBAL scope, but routed through AppSettingsStore for consistency.
    @Published var alarmAction: FreeWheelCore.AlarmAction = FreeWheelCore.AlarmAction.companion.fromValue(value: Int32(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarmAction))) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarmAction, value: alarmAction.value) }
    }
    @Published private(set) var activeAlarms: Set<AlarmType> = []

    // PWM-based alarm settings
    @Published var pwmBasedAlarms: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.pwmBasedAlarms) {
        didSet { appSettingsStore.setBool(id: AppSettingId.pwmBasedAlarms, value: pwmBasedAlarms) }
    }
    @Published var alarmFactor1: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarmFactor1)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarmFactor1, value: Int32(alarmFactor1)) }
    }
    @Published var alarmFactor2: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarmFactor2)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarmFactor2, value: Int32(alarmFactor2)) }
    }

    // Pre-warning settings
    @Published var warningPwm: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.warningPwm)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.warningPwm, value: Int32(warningPwm)) }
    }
    @Published var warningSpeed: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.warningSpeed)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.warningSpeed, value: Int32(warningSpeed)) }
    }
    @Published var warningSpeedPeriod: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.warningSpeedPeriod)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.warningSpeedPeriod, value: Int32(warningSpeedPeriod)) }
    }

    // Battery thresholds per speed alarm
    @Published var alarm1Battery: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarm1Battery)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarm1Battery, value: Int32(alarm1Battery)) }
    }
    @Published var alarm2Battery: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarm2Battery)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarm2Battery, value: Int32(alarm2Battery)) }
    }
    @Published var alarm3Battery: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarm3Battery)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarm3Battery, value: Int32(alarm3Battery)) }
    }

    // New alarm types
    @Published var alarmPhaseCurrent: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarmPhaseCurrent)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarmPhaseCurrent, value: Int32(alarmPhaseCurrent)) }
    }
    @Published var alarmMotorTemperature: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.alarmMotorTemperature)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.alarmMotorTemperature, value: Int32(alarmMotorTemperature)) }
    }
    @Published var alarmWheel: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.alarmWheel) {
        didSet { appSettingsStore.setBool(id: AppSettingId.alarmWheel, value: alarmWheel) }
    }

    // Connection settings (persisted via AppSettingsStore; GLOBAL scope)
    @Published var autoReconnect: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.autoReconnect) {
        didSet { appSettingsStore.setBool(id: AppSettingId.autoReconnect, value: autoReconnect) }
    }
    @Published var showUnknownDevices: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.showUnknownDevices) {
        didSet { appSettingsStore.setBool(id: AppSettingId.showUnknownDevices, value: showUnknownDevices) }
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

    // Logging settings (persisted via AppSettingsStore; GLOBAL scope)
    @Published var autoStartLogging: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.autoLog) {
        didSet { appSettingsStore.setBool(id: AppSettingId.autoLog, value: autoStartLogging) }
    }
    @Published var logGPS: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.logLocationData) {
        didSet { appSettingsStore.setBool(id: AppSettingId.logLocationData, value: logGPS) }
    }
    @Published var autoCapture: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.autoCapture) {
        didSet { appSettingsStore.setBool(id: AppSettingId.autoCapture, value: autoCapture) }
    }
    @Published private(set) var isLogging: Bool = false
    @Published private(set) var isRidePaused: Bool = false
    @Published private(set) var liveRideStartDate: Date?
    @Published private(set) var liveRideElapsedSeconds: TimeInterval = 0
    @Published private(set) var liveRideMaxSpeedKmh: Double = 0
    @Published private(set) var liveRideMaxPwmPercent: Double = 0
    @Published private(set) var liveRideDistanceKm: Double = 0
    @Published private(set) var liveRoutePoints: [RoutePoint] = []
    @Published private(set) var liveRouteSpeedRange: SpeedRange?

    // Nearby charging stations (OpenChargeMap, refreshed as the Map tab camera idles)
    @Published private(set) var nearbyChargers: [ChargingStation] = []
    private let chargingStationRepository: ChargingStationRepository = {
        let key = (Bundle.main.object(forInfoDictionaryKey: "OpenChargeMapApiKey") as? String) ?? ""
        return ChargingStationManagerHelper.shared.create(apiKey: key)
    }()
    private var chargersObserver: FlowObservation?

    func refreshChargers(latitude: Double, longitude: Double) {
        ChargingStationManagerHelper.shared.refreshNearby(
            repository: chargingStationRepository,
            latitude: latitude,
            longitude: longitude
        )
    }
    private let liveRouteBuffer = FreeWheelCore.LiveRouteBuffer(
        minIntervalMs: 1_000,
        minDistanceMeters: 1.0,
        maxPoints: 10_000
    )
    private var ridePauseTimeoutTask: Task<Void, Never>?
    private var pausedRideAddress: String?
    private var recoveryEpisodeAddress: String?
    private var lastLoggedReconnectAttempt: Int?
    private var pendingUserDisconnect: Bool = false

    /// Tracks an in-flight recovery episode that posted a "Lost" notification.
    /// Distinct from `recoveryEpisodeAddress` (which is set on every
    /// ConnectionLost regardless of foreground state) — this field is set
    /// only when the lost notification actually fires, so the follow-up
    /// restored / given_up paths can't post a banner without a corresponding
    /// "Lost" already in Notification Center.
    ///
    /// Lifecycle rule: read → act → clear at terminal handlers. Cleanup
    /// helpers (`clearReconnectRecovery`, `clearPauseState`) must NOT touch
    /// this field; every terminal path makes an explicit decision (post,
    /// remove, or leave).
    private struct NotificationEpisode {
        let address: String
        let wheelName: String
        let issue: ConnectionIssueContext
        let startedAtMs: Int64
    }
    private var notificationEpisode: NotificationEpisode?

    private static let ridePauseTimeoutSeconds: TimeInterval = 3600 // 1 hour

    // Auto-torch settings (persisted via AppSettingsStore; GLOBAL scope)
    @Published var autoTorchEnabled: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.autoTorchEnabled) {
        didSet { appSettingsStore.setBool(id: AppSettingId.autoTorchEnabled, value: autoTorchEnabled) }
    }
    @Published var autoTorchSpeedThreshold: Double = Double(WheelManager.initSettingsStore.getInt(id: AppSettingId.autoTorchSpeedThreshold)) {
        didSet { appSettingsStore.setInt(id: AppSettingId.autoTorchSpeedThreshold, value: Int32(autoTorchSpeedThreshold)) }
    }
    @Published var autoTorchUseSunset: Bool = WheelManager.initSettingsStore.getBool(id: AppSettingId.autoTorchUseSunset) {
        didSet { appSettingsStore.setBool(id: AppSettingId.autoTorchUseSunset, value: autoTorchUseSunset) }
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
    private var errorLogEventCount: Int = 0

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
    private var replayIdentityObserver: FlowObservation?
    private var replayBmsObserver: FlowObservation?
    private var replaySettingsObserver: FlowObservation?
    private var replayStateObserver: FlowObservation?
    private var replayPositionObserver: FlowObservation?
    private var replaySpeedObserver: FlowObservation?

    // MARK: - Dashboard & Navigation Config

    @Published var dashboardLayout: DashboardLayout = DashboardLayout.companion.default() {
        didSet {
            let serialized = DashboardLayoutSerializer.shared.serialize(layout: dashboardLayout)
            UserDefaults.standard.set(serialized, forKey: dashboardLayoutKey)
        }
    }

    @Published var navigationConfig: NavigationConfig = NavigationConfig.companion.default() {
        didSet {
            let serialized = NavigationConfigSerializer.shared.serialize(config: navigationConfig)
            UserDefaults.standard.set(serialized, forKey: PreferenceKeys.shared.NAVIGATION_CONFIG)
            loadCustomTabLayouts()
        }
    }

    // Per-wheel dashboard layout key. Anchored on LAST_CONNECTED_MAC (the
    // stable per-wheel scoping anchor that survives explicit disconnects)
    // rather than LAST_MAC (the auto-reconnect target, cleared on
    // disconnect) — see AppSettingsStore. Otherwise dashboard edits made
    // while disconnected would fall back to the bare key instead of
    // sticking to the last connected wheel. Falls back to the bare key
    // when no wheel has ever been connected.
    private var dashboardLayoutKey: String {
        let mac = appSettingsStore.getLastConnectedMac()
        let base = PreferenceKeys.shared.DASHBOARD_LAYOUT
        return mac.isEmpty ? base : "\(mac)_\(base)"
    }

    func loadDashboardLayout() {
        let canonical = dashboardLayoutKey
        let raw = UserDefaults.standard.string(forKey: canonical)
            ?? Self.migrateLegacyDashboardLayoutKey(canonical: canonical)
        if let raw, let layout = DashboardLayoutSerializer.shared.deserialize(input: raw) {
            dashboardLayout = layout
        } else {
            dashboardLayout = DashboardLayout.companion.default()
        }
    }

    /// One-time migration of the legacy global "dashboard_layout" key.
    /// Pre-fix iOS persisted the layout under the bare key for all
    /// wheels; post-fix reads ${LAST_CONNECTED_MAC}_dashboard_layout, and
    /// AppSettingsStore backfills LAST_CONNECTED_MAC from LAST_MAC on
    /// upgrade — so existing users' customization is orphaned after the
    /// first launch on the new build. Copy the legacy value onto the
    /// canonical per-wheel slot and remove the bare entry so this runs
    /// at most once per install. When the canonical *is* the bare key
    /// (no wheel anchor yet), skip — that's the fresh-install write
    /// target, not a legacy entry.
    static func migrateLegacyDashboardLayoutKey(canonical: String) -> String? {
        let legacyKey = PreferenceKeys.shared.DASHBOARD_LAYOUT
        if legacyKey == canonical { return nil }
        guard let legacy = UserDefaults.standard.string(forKey: legacyKey) else { return nil }
        UserDefaults.standard.set(legacy, forKey: canonical)
        UserDefaults.standard.removeObject(forKey: legacyKey)
        return legacy
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

    // MARK: - Settings Stores (KMP-backed)

    private let appSettingsStore = AppSettingsStore(store: UserDefaultsKeyValueStore(defaults: .standard))
    private let decoderConfigStore = DecoderConfigStore(store: UserDefaultsKeyValueStore(defaults: .standard))

    // Initializer-only counterpart to appSettingsStore. @Published property
    // initializers run before init() body and cannot reference `self`, so the
    // instance store isn't available yet. Both wrap the same NSUserDefaults
    // singleton — they are functionally interchangeable for read/write.
    private static let initSettingsStore = AppSettingsStore(store: UserDefaultsKeyValueStore(defaults: .standard))

    // MARK: - Veteran Lock Password Storage (Keychain-backed)

    /// Per-MAC Veteran lock password store, paired with its backing label so
    /// the lock prompt can decide whether to render the "remember password"
    /// toggle without inspecting the store's identity. iOS always lands on
    /// SECURE because Keychain Services is available on every supported iOS
    /// version (mirrors AndroidWheelPasswordStoreFactory.createWithBacking).
    private let passwordStoreSelection: WheelPasswordStoreSelection =
        IosWheelPasswordStoreFactory.shared.createWithBacking(
            serviceName: KeychainWheelPasswordStore.companion.DEFAULT_SERVICE
        )
    private var passwordStore: WheelPasswordStore { passwordStoreSelection.store }
    private var passwordStoreBacking: PasswordStorageBacking { passwordStoreSelection.backing }

    /// Drives the Veteran lock/unlock password prompt; .idle when no prompt
    /// is up. Mirror of the Compose viewModel.lockPromptState.
    @Published private(set) var lockPromptState: LockPromptState = LockPromptState.Idle.shared

    /// Drives the Veteran password-management dialog (set / modify / clear /
    /// auto-lock). .idle when no dialog is up. Mirror of the Compose
    /// viewModel.passwordManagementState.
    @Published private(set) var passwordManagementState: PasswordManagementState =
        PasswordManagementState.Idle.shared

    /// In-flight ack-listener Task for the password-management flow.
    /// Cancelled whenever state leaves PendingAck so two listeners never race.
    private var passwordAckTask: Task<Void, Never>?

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
            lastConnectedMs: Int64(Date().timeIntervalSince1970 * 1000),
            topSpeedOverrideKmh: nil
        ))
        refreshSavedAddresses()
    }

    func forgetProfile(address: String) {
        wheelProfileStore.deleteProfile(address: address)
        refreshSavedAddresses()
    }

    /// Pass 4 "Reset wheel type" surface: clears the saved wheelType for
    /// `address` without dropping the rest of the profile (display name,
    /// top-speed override, etc.). The next connect to that MAC re-runs
    /// detection, falling into the `WheelTypePickerSheet` if topology + name
    /// both miss.
    func resetWheelType(address: String) {
        wheelProfileStore.clearWheelType(address: address)
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
        let storedUUID = appSettingsStore.getLastMac()
        guard !storedUUID.isEmpty else { return }
        guard bleManager != nil else { return }

        startupScanTarget = storedUUID

        // CBCentralManager needs time to reach poweredOn on cold launch
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 1_000_000_000) // 1 second
            self.startScan()
        }
    }

    deinit {
        // FlowObservation.close() just cancels a CoroutineScope and is safe
        // to call from any thread.
        telemetryObserver?.close()
        identityObserver?.close()
        bmsObserver?.close()
        settingsObserver?.close()
        connectionStateObserver?.close()
        capabilitiesObserver?.close()
        autoConnectingObserver?.close()
        reconnectStateObserver?.close()
        bluetoothStateObserver?.close()
        eventLogObserver?.close()
        chargersObserver?.close()
        demoTelemetryObserver?.close()
        demoIdentityObserver?.close()
        demoBmsObserver?.close()
        replayTelemetryObserver?.close()
        replayIdentityObserver?.close()
        replayBmsObserver?.close()
        replaySettingsObserver?.close()
        replayStateObserver?.close()
        replayPositionObserver?.close()
        replaySpeedObserver?.close()

        // KMP teardown: the helper's scope map is main-thread-only, so hop
        // to MainActor. Capture references by value so the Task doesn't
        // keep self alive past deinit.
        //
        // Capture/logging finalization is MainActor-isolated state that
        // can't be read from nonisolated deinit; it runs in shutdown(),
        // wired to willTerminateNotification. If shutdown() wasn't called
        // before this deinit, those finalizers are skipped — preferable to
        // racing on partially-destroyed state.
        let demoProviderRef = demoProvider
        let replayEngineRef = replayEngine
        let connectionManagerRef = connectionManager
        let autoConnectManagerRef = autoConnectManager
        let bleManagerRef = bleManager
        Task { @MainActor in
            WheelConnectionManagerHelper.shared.stopDemo(provider: demoProviderRef)
            WheelConnectionManagerHelper.shared.stopReplay(engine: replayEngineRef)
            if let acm = autoConnectManagerRef {
                WheelConnectionManagerHelper.shared.destroyAutoConnect(manager: acm)
            }
            if let cm = connectionManagerRef {
                WheelConnectionManagerHelper.shared.shutdownConnectionManager(manager: cm)
            }
            bleManagerRef?.destroy()
        }
    }

    /// Synchronously finalize ride/capture state and tear down KMP
    /// resources. Idempotent — safe to call from `willTerminateNotification`
    /// or scenePhase transitions. After this returns, deinit's async hop
    /// becomes a no-op.
    @MainActor
    func shutdown() {
        guard !isShutdown else { return }
        isShutdown = true

        telemetryObserver?.close(); telemetryObserver = nil
        identityObserver?.close(); identityObserver = nil
        bmsObserver?.close(); bmsObserver = nil
        settingsObserver?.close(); settingsObserver = nil
        connectionStateObserver?.close(); connectionStateObserver = nil
        capabilitiesObserver?.close(); capabilitiesObserver = nil
        autoConnectingObserver?.close(); autoConnectingObserver = nil
        reconnectStateObserver?.close(); reconnectStateObserver = nil
        bluetoothStateObserver?.close(); bluetoothStateObserver = nil
        eventLogObserver?.close(); eventLogObserver = nil
        chargersObserver?.close(); chargersObserver = nil
        demoTelemetryObserver?.close(); demoTelemetryObserver = nil
        demoIdentityObserver?.close(); demoIdentityObserver = nil
        demoBmsObserver?.close(); demoBmsObserver = nil
        replayTelemetryObserver?.close(); replayTelemetryObserver = nil
        replayIdentityObserver?.close(); replayIdentityObserver = nil
        replayBmsObserver?.close(); replayBmsObserver = nil
        replaySettingsObserver?.close(); replaySettingsObserver = nil
        replayStateObserver?.close(); replayStateObserver = nil
        replayPositionObserver?.close(); replayPositionObserver = nil
        replaySpeedObserver?.close(); replaySpeedObserver = nil

        if isCapturing { stopCapture() }
        if isLogging {
            if let metadata = rideLogger.stopLogging(currentDistance: telemetry?.totalDistanceKm ?? 0) {
                rideStore.addRide(metadata)
            }
            isLogging = false
        }
        // Finalize the connection error CSV (writes the footer + closes the
        // file handle) before tearing down connectionManager. The normal
        // disconnect/failure path goes through handleConnectionStateChange,
        // but shutdown() nils the connection-state observer above, so an
        // app-termination shutdown would otherwise leave the file
        // footerless.
        endErrorLogSession(reason: "App terminating")
        WheelConnectionManagerHelper.shared.stopDemo(provider: demoProvider)
        WheelConnectionManagerHelper.shared.stopReplay(engine: replayEngine)

        if let acm = autoConnectManager {
            WheelConnectionManagerHelper.shared.destroyAutoConnect(manager: acm)
            autoConnectManager = nil
        }
        if let cm = connectionManager {
            WheelConnectionManagerHelper.shared.shutdownConnectionManager(manager: cm)
            connectionManager = nil
        }
        bleManager?.destroy()
        bleManager = nil
    }

    private var isShutdown = false

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

        // Wire BLE data to connection manager. attemptId stamped by KMP
        // BleManager at connect() time so the reducer can drop frames from a
        // prior session that the OS BLE stack hasn't fully torn down yet.
        // Kotlin/Native exports lambda params as boxed KotlinLong — unbox to
        // Int64 at the boundary.
        bleManager?.setDataReceivedCallback { [weak self] data, attemptId in
            self?.connectionManager?.onDataReceived(data: data, attemptId: attemptId.int64Value)
        }

        // Create shared auto-connect manager
        guard let cm = connectionManager else { return }
        autoConnectManager = WheelConnectionManagerHelper.shared.createAutoConnectManager(manager: cm)

        // Wire service discovery to connection manager
        bleManager?.setServicesDiscoveredCallback { [weak self] services, deviceName, attemptId in
            self?.connectionManager?.onServicesDiscovered(services: services, deviceName: deviceName, attemptId: attemptId.int64Value)
        }

        // Wire BLE errors to connection manager
        bleManager?.setBleErrorCallback { [weak self] in
            self?.connectionManager?.onBleError()
        }

        // Wire OS-level disconnects to connection manager
        bleManager?.setBleDisconnectedCallback { [weak self] address, issue, attemptId in
            self?.connectionManager?.onBleDisconnected(
                address: address,
                reason: issue.message,
                issue: issue,
                attemptId: attemptId.int64Value
            )
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

        WheelConnectionManagerHelper.shared.injectTestData(manager: cm, data: kotlinBytes)
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

        // Nearby chargers (independent of the wheel — always on)
        chargersObserver = ChargingStationManagerHelper.shared.observeStations(
            repository: chargingStationRepository
        ) { [weak self] stations in
            Task { @MainActor in
                self?.nearbyChargers = stations
            }
        }

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
                // useMph is a global app preference owned by the user. The
                // wheel-reported inMiles flag stays in wheelSettings for UI
                // that wants to show what the wheel itself is displaying
                // (e.g. Settings screen), but it must not silently rewrite
                // the app-wide unit preference — connecting to one wheel
                // would otherwise flip display units everywhere.
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
                        let attempt = Int(helper.reconnectAttemptNumber(state: kmpState))
                        self.reconnectState = .attempting(attempt: attempt)
                        if let address = self.recoveryEpisodeAddress,
                           self.lastLoggedReconnectAttempt != attempt {
                            self.lastLoggedReconnectAttempt = attempt
                            self.appendRecoveryLogEvent(
                                stage: "ATTEMPTING",
                                address: address,
                                attempt: attempt,
                                detail: "App-level reconnect attempt"
                            )
                        }
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
            if let stats = rideLogger.liveStats(currentDistance: newTelemetry.totalDistanceKm) {
                liveRideStartDate = Date(timeIntervalSince1970: Double(stats.startTimeMs) / 1000.0)
                liveRideElapsedSeconds = Double(stats.elapsedMs) / 1000.0
                liveRideMaxSpeedKmh = stats.maxSpeedKmh
                liveRideMaxPwmPercent = stats.maxPwmPercent
                liveRideDistanceKm = Double(stats.distanceMeters) / 1000.0
            }

            // Append to live-ride route buffer for the Map tab
            if !isRidePaused, let loc = locationManager.currentLocation {
                let gpsSpeedKmh = ByteUtils.shared.metersPerSecondToKmh(speedMs: max(0, loc.speed))
                let rp = RoutePoint(
                    timestampMs: Int64(loc.timestamp.timeIntervalSince1970 * 1000),
                    latitude: loc.coordinate.latitude,
                    longitude: loc.coordinate.longitude,
                    altitude: loc.altitude,
                    bearing: loc.course >= 0 ? loc.course : 0,
                    speedKmh: newTelemetry.speedKmh,
                    gpsSpeedKmh: gpsSpeedKmh
                )
                if liveRouteBuffer.addPointIfNeeded(point: rp) {
                    liveRoutePoints = liveRouteBuffer.snapshot()
                    liveRouteSpeedRange = liveRouteBuffer.speedRangeKmh()
                }
            }
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

    /// Re-read all per-wheel alarm fields from AppSettingsStore. Call after the
    /// connected wheel's MAC changes so @Published values reflect the new wheel's
    /// stored thresholds instead of the previous wheel's (or default) values.
    private func reloadAlarmsFromStore() {
        alarmsEnabled = appSettingsStore.getBool(id: AppSettingId.alarmsEnabled)
        pwmBasedAlarms = appSettingsStore.getBool(id: AppSettingId.pwmBasedAlarms)
        alarmWheel = appSettingsStore.getBool(id: AppSettingId.alarmWheel)
        alarmAction = FreeWheelCore.AlarmAction.companion.fromValue(
            value: Int32(appSettingsStore.getInt(id: AppSettingId.alarmAction))
        )
        alarmFactor1 = Double(appSettingsStore.getInt(id: AppSettingId.alarmFactor1))
        alarmFactor2 = Double(appSettingsStore.getInt(id: AppSettingId.alarmFactor2))
        warningPwm = Double(appSettingsStore.getInt(id: AppSettingId.warningPwm))
        warningSpeed = Double(appSettingsStore.getInt(id: AppSettingId.warningSpeed))
        warningSpeedPeriod = Double(appSettingsStore.getInt(id: AppSettingId.warningSpeedPeriod))
        alarm1Speed = Double(appSettingsStore.getInt(id: AppSettingId.alarm1Speed))
        alarm2Speed = Double(appSettingsStore.getInt(id: AppSettingId.alarm2Speed))
        alarm3Speed = Double(appSettingsStore.getInt(id: AppSettingId.alarm3Speed))
        alarm1Battery = Double(appSettingsStore.getInt(id: AppSettingId.alarm1Battery))
        alarm2Battery = Double(appSettingsStore.getInt(id: AppSettingId.alarm2Battery))
        alarm3Battery = Double(appSettingsStore.getInt(id: AppSettingId.alarm3Battery))
        alarmCurrent = Double(appSettingsStore.getInt(id: AppSettingId.alarmCurrent))
        alarmPhaseCurrent = Double(appSettingsStore.getInt(id: AppSettingId.alarmPhaseCurrent))
        alarmTemperature = Double(appSettingsStore.getInt(id: AppSettingId.alarmTemperature))
        alarmMotorTemperature = Double(appSettingsStore.getInt(id: AppSettingId.alarmMotorTemperature))
        alarmBattery = Double(appSettingsStore.getInt(id: AppSettingId.alarmBattery))
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
            let priorRecoveryAddress = recoveryEpisodeAddress
            let recoveredSameWheel = priorRecoveryAddress == address
            lastConnectedAddress = address
            pendingUserDisconnect = false

            // Notification episode: consume BEFORE clearRecoveryEpisode() per
            // the read → act → clear lifecycle rule. The recovery cleanup
            // helper must not touch notificationEpisode, so order doesn't
            // matter, but consuming first keeps the contract explicit.
            if let episode = notificationEpisode {
                if episode.address == address {
                    let durationMs = Int64(Date().timeIntervalSince1970 * 1000) - episode.startedAtMs
                    backgroundManager.postConnectionRestoredNotification(
                        wheelName: episode.wheelName,
                        address: episode.address,
                        originalIssue: episode.issue,
                        recoveryDurationMs: durationMs
                    )
                } else {
                    // Different-wheel .connected: replace the prior wheel's
                    // stale "Lost" banner using the EPISODE's identifier
                    // (not the new connection's). The banner being replaced
                    // belongs to the prior wheel.
                    backgroundManager.postRecoveryGivenUpNotification(
                        wheelName: episode.wheelName,
                        address: episode.address,
                        originalIssue: episode.issue,
                        terminalIssue: nil,
                        reason: "Connected to a different wheel"
                    )
                }
                notificationEpisode = nil
            }

            if let priorRecoveryAddress, !recoveredSameWheel {
                appendRecoveryLogEvent(
                    stage: "EXHAUSTED",
                    address: priorRecoveryAddress,
                    detail: "Connected to a different wheel during recovery"
                )
                endErrorLogSession(reason: "Connected to a different wheel during recovery")
                clearRecoveryEpisode()
            }
            if recoveredSameWheel {
                appendRecoveryLogEvent(
                    stage: "SUCCEEDED",
                    address: address,
                    detail: "Connection restored"
                )
                clearRecoveryEpisode()
            }
            // Per-wheel scoping anchor for AppSettingsStore / DecoderConfigStore.
            appSettingsStore.setLastMac(mac: address)
            // Reload PER_WHEEL alarm fields so @Published values reflect this wheel's
            // stored thresholds rather than the previous wheel's (or default) values.
            reloadAlarmsFromStore()
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

            // Resume paused ride if reconnecting to same wheel
            if isRidePaused {
                if address == pausedRideAddress {
                    clearPauseState()
                    rideLogger.resume()
                } else {
                    clearPauseState()
                    stopLogging()
                }
            } else {
                // Auto-start logging if enabled (only for fresh connections, not resumes)
                if autoStartLogging && !isLogging {
                    startLogging()
                }
            }

            // Auto BLE capture
            if autoCapture && !isCapturing {
                startCapture()
            }

            // Start a new error log only for a fresh session. Recovery resumes
            // keep the existing file open so the whole episode lives together.
            if errorLogFileHandle == nil {
                startErrorLogSession(address: address)
            }
        }

        // Connection lost — pause ride instead of ending it.
        // OS-level auto-reconnect handles mid-ride reconnection
        // (centralManager.connect in onPeripheralDisconnected).
        if case .connectionLost(let address, let reason, let issue) = newState {
            if recoveryEpisodeAddress != address {
                recoveryEpisodeAddress = address
                lastLoggedReconnectAttempt = nil
                appendRecoveryLogEvent(
                    stage: "STARTED",
                    address: address,
                    detail: reason
                )
            }
            if isCapturing { stopCapture() }
            if isLogging && !isRidePaused {
                rideLogger.pause()
                isRidePaused = true
                pausedRideAddress = lastConnectedAddress
                ridePauseTimeoutTask?.cancel()
                ridePauseTimeoutTask = Task { @MainActor [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(Self.ridePauseTimeoutSeconds * 1_000_000_000))
                    guard !Task.isCancelled, let self else { return }
                    // Pause timeout ends the recovery — replace any open
                    // notification episode with a "Gave up" banner.
                    // terminalIssue is nil because the timeout is a wall-
                    // clock condition, not a typed BLE failure.
                    if let episode = self.notificationEpisode {
                        self.backgroundManager.postRecoveryGivenUpNotification(
                            wheelName: episode.wheelName,
                            address: episode.address,
                            originalIssue: episode.issue,
                            terminalIssue: nil,
                            reason: "Ride paused too long without reconnect"
                        )
                        self.notificationEpisode = nil
                    }
                    if let recoveryAddress = self.recoveryEpisodeAddress {
                        self.appendRecoveryLogEvent(
                            stage: "EXHAUSTED",
                            address: recoveryAddress,
                            detail: "Ride pause timeout"
                        )
                        self.endErrorLogSession(reason: "Ride paused too long without reconnect")
                    }
                    self.stopLogging()
                }
            }
            if backgroundManager.isInBackground {
                let wheelName = identity.displayName
                backgroundManager.postConnectionLostNotification(
                    wheelName: wheelName,
                    address: address,
                    reason: reason,
                    issue: issue
                )
                notificationEpisode = NotificationEpisode(
                    address: address,
                    wheelName: wheelName,
                    issue: issue,
                    startedAtMs: Int64(Date().timeIntervalSince1970 * 1000)
                )
            }

            locationManager.stopTracking()
            beginReconnectRecovery(for: address)
        }

        // User-initiated disconnect — always end ride, even if the user
        // disconnected while a paused ride was waiting on reconnect.
        if case .disconnected = newState {
            let explicitDisconnect = pendingUserDisconnect
            let recoveryAddress = recoveryEpisodeAddress
            let shouldFinalizeRide = oldState.isConnected || isRidePaused || recoveryAddress != nil || explicitDisconnect
            pendingUserDisconnect = false
            guard shouldFinalizeRide else { return }

            // Notification episode: read → act → clear. On user disconnect
            // mid-recovery, drop the stale "Lost" banner instead of posting
            // a replacement — the user knows they tapped Disconnect and an
            // extra banner is noise. Non-user disconnects with an open
            // episode are rare (transitionToIdle outside the user path) but
            // also get the banner removed.
            if let episode = notificationEpisode {
                backgroundManager.removeConnectionEpisodeNotification(
                    address: episode.address
                )
                notificationEpisode = nil
            }

            if let recoveryAddress, explicitDisconnect {
                appendRecoveryLogEvent(
                    stage: "CANCELLED",
                    address: recoveryAddress,
                    detail: "User disconnect"
                )
            }
            clearRecoveryEpisode()
            endErrorLogSession(reason: explicitDisconnect ? "User disconnect" : "Disconnected")
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

        // Failed state during a reconnect-recovery flow should keep the ride
        // paused and let the shared AutoConnectManager keep retrying.
        if case .failed(let address, let error, let issue) = newState {
            if isCapturing { stopCapture() }
            if shouldPreservePausedRideDuringReconnectFailure(
                address: address,
                issueRecoverable: issue.isRecoverable
            ) {
                locationManager.stopTracking()
                return
            }

            // Terminal failure ends the recovery — replace the prior "Lost"
            // banner with "Recovery gave up" carrying both the original
            // disconnect cause and this terminal issue. Read → act → clear.
            if let episode = notificationEpisode {
                backgroundManager.postRecoveryGivenUpNotification(
                    wheelName: episode.wheelName,
                    address: episode.address,
                    originalIssue: episode.issue,
                    terminalIssue: issue,
                    reason: error
                )
                notificationEpisode = nil
            }

            if let recoveryAddress = recoveryEpisodeAddress {
                appendRecoveryLogEvent(
                    stage: "EXHAUSTED",
                    address: recoveryAddress,
                    detail: error
                )
            }
            clearRecoveryEpisode()
            endErrorLogSession(reason: error)
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
        // Per-wheel decoder tuning fields scope through last_mac (dual-written at
        // connect/disconnect). useMph/useFahrenheit are global app settings and
        // continue to come from the @Published properties above.
        let config = DecoderConfig(
            useMph: useMph,
            useFahrenheit: useFahrenheit,
            useCustomPercents: decoderConfigStore.getCustomPercents(),
            cellVoltageTiltback: Int32(decoderConfigStore.getCellVoltageTiltback()),
            rotationSpeed: Int32(decoderConfigStore.getRotationSpeed()),
            rotationVoltage: Int32(decoderConfigStore.getRotationVoltage()),
            powerFactor: Int32(decoderConfigStore.getPowerFactor()),
            batteryCapacity: Int32(decoderConfigStore.getBatteryCapacity()),
            wheelPassword: decoderConfigStore.getWheelPassword(),
            gotwayNegative: Int32(decoderConfigStore.getGotwayNegative()),
            useRatio: decoderConfigStore.getUseRatio(),
            gotwayVoltage: Int32(decoderConfigStore.getGotwayVoltage()),
            hwPwmEnabled: decoderConfigStore.getHwPwm(),
            ks18LScaler: decoderConfigStore.getKs18LScaler(),
            autoVoltage: decoderConfigStore.getAutoVoltage()
        )
        WheelConnectionManagerHelper.shared.updateDecoderConfig(manager: cm, config: config)
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
        // Veteran wheels gate lock/unlock behind a numeric password. Route the
        // LOCK toggle through the prompt instead of dispatching the generic
        // SetLock the Veteran decoder would drop on the floor. Mirrors the
        // identical interception in WheelViewModel.executeWheelCommand.
        if commandId == SettingsCommandId.lock && identity.wheelType == WheelType.veteran {
            requestLock(action: boolValue ? LockPromptState.LockAction.lock : LockPromptState.LockAction.unlock)
            return
        }
        WheelConnectionManagerHelper.shared.executeCommand(
            manager: cm,
            commandId: commandId,
            intValue: intValue,
            boolValue: boolValue
        )
    }

    // MARK: - Veteran lock prompt flow

    /// Open the lock prompt for the currently-connected wheel.
    func requestLock(action: LockPromptState.LockAction) {
        guard case .connected(let address, _) = connectionState else { return }
        lockPromptState = LockPromptState.companion.start(
            action: action,
            address: address,
            store: passwordStore,
            storeBacking: passwordStoreBacking
        )
    }

    /// Submit the password from the prompt. Validates, dispatches, and
    /// persists according to the rememberPassword opt-in.
    func submitLockPassword(_ password: String, rememberPassword: Bool) {
        guard let current = lockPromptState as? LockPromptState.AwaitingPassword else { return }
        if let validationError = LockPromptState.companion.validate(password: password) {
            lockPromptState = LockPromptState.Error(
                reason: validationError,
                action: current.action,
                address: current.address
            )
            return
        }
        guard case .connected = connectionState else {
            lockPromptState = LockPromptState.Error(
                reason: LockPromptState.ErrorReason.notConnected,
                action: current.action,
                address: current.address
            )
            return
        }
        guard let cm = connectionManager else { return }

        lockPromptState = LockPromptState.Sending(action: current.action, address: current.address)

        let locked = current.action == LockPromptState.LockAction.lock
        WheelConnectionManagerHelper.shared.sendSetVeteranLock(manager: cm, locked: locked, password: password)

        // Two-way switch: opting out clears any previously-saved entry so the
        // app never silently retains the password against the user's choice.
        if rememberPassword {
            passwordStore.setPassword(address: current.address, password: password)
        } else {
            passwordStore.clearPassword(address: current.address)
        }

        lockPromptState = LockPromptState.Sent.shared
    }

    /// Dismiss the prompt without sending.
    func dismissLockPrompt() {
        lockPromptState = LockPromptState.Idle.shared
    }

    // MARK: - Veteran password-management flow
    //
    // Mirrors the Android ViewModel ack-listener (commit 320c87db). The
    // wheel acks each command out-of-band via lockState; the listener owns
    // the deadline and state-change tracking centrally so SwiftUI views stay
    // dumb. Same same-state-fallback policy: SET / MODIFY / CLEAR require an
    // actually-changed readback or time out; AUTO_LOCK_ON / OFF accept the
    // same-state snapshot because bit 5 reflects current state rather than
    // last-command ack.

    /// Open the password-management dialog for `operation` against the
    /// currently-connected wheel.
    func requestPasswordManagement(_ operation: PasswordManagementState.Operation) {
        guard case .connected(let address, _) = connectionState else { return }
        cancelPasswordAckTask()
        passwordManagementState = PasswordManagementState.companion.start(
            operation: operation,
            address: address,
            store: passwordStore,
            storeBacking: passwordStoreBacking
        )
    }

    /// Submit the form. Validates via the shared state machine, dispatches
    /// the wire command, then waits up to the deadline for an ack.
    func submitPasswordManagement(input: PasswordManagementInput) {
        guard let editing = passwordManagementState as? PasswordManagementState.Editing else { return }
        let next = PasswordManagementState.companion.submit(editing: editing, input: input)
        if let failed = next as? PasswordManagementState.Failed {
            passwordManagementState = failed
            return
        }
        guard let submitting = next as? PasswordManagementState.Submitting else { return }
        guard case .connected = connectionState, let cm = connectionManager else {
            passwordManagementState = PasswordManagementState.Failed(
                operation: submitting.operation,
                address: submitting.address,
                reason: PasswordManagementState.FailureReason.notConnected
            )
            return
        }
        passwordManagementState = submitting
        dispatchPasswordCommand(submitting: submitting, cm: cm)
        let deadlineEpochMs = Int64(Date().timeIntervalSince1970 * 1000) +
            PasswordManagementState.companion.DEFAULT_ACK_TIMEOUT_MS
        let pending = PasswordManagementState.companion.dispatched(
            submitting: submitting,
            deadlineEpochMs: deadlineEpochMs
        )
        passwordManagementState = pending
        startPasswordAckListener(pending: pending)
    }

    /// Dismiss the dialog and cancel any in-flight ack listener.
    func dismissPasswordManagement() {
        cancelPasswordAckTask()
        passwordManagementState = PasswordManagementState.Idle.shared
    }

    private func dispatchPasswordCommand(submitting: PasswordManagementState.Submitting,
                                         cm: WheelConnectionManager) {
        let helper = WheelConnectionManagerHelper.shared
        switch submitting.operation {
        case PasswordManagementState.Operation.set:
            helper.sendSetVeteranPassword(manager: cm, newPassword: submitting.newPassword)
        case PasswordManagementState.Operation.modify:
            helper.sendModifyVeteranPassword(
                manager: cm,
                oldPassword: submitting.oldPassword,
                newPassword: submitting.newPassword
            )
        case PasswordManagementState.Operation.clear:
            helper.sendClearVeteranPassword(manager: cm, password: submitting.oldPassword)
        case PasswordManagementState.Operation.autoLockOn:
            helper.sendSetVeteranAutoLock(manager: cm, enabled: true, password: submitting.oldPassword)
        case PasswordManagementState.Operation.autoLockOff:
            helper.sendSetVeteranAutoLock(manager: cm, enabled: false, password: submitting.oldPassword)
        default:
            break // KMP enum is exhaustive but Swift requires a default for safety.
        }
    }

    private func startPasswordAckListener(pending: PasswordManagementState.PendingAck) {
        cancelPasswordAckTask()
        let baseline = currentVeteranLockState()
        passwordAckTask = Task { @MainActor [weak self] in
            guard let self = self else { return }
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let timeBudgetMs = max(pending.deadlineEpochMs - nowMs, 0)
            let timeBudgetNanos = UInt64(timeBudgetMs) * 1_000_000

            // Race state-changing readbacks against the deadline using a
            // structured group: one child observes $wheelSettings, the other
            // sleeps the deadline. Whichever finishes first wins.
            let resolved: PasswordManagementState? = await withTaskGroup(of: PasswordManagementState?.self) { group in
                group.addTask { @MainActor [weak self] in
                    guard let self = self else { return nil }
                    for await settings in self.$wheelSettings.values {
                        if Task.isCancelled { return nil }
                        guard let veteran = settings as? WheelSettings.Veteran else { continue }
                        let lockState = Int(veteran.lockState)
                        if lockState < 0 || lockState == baseline { continue }
                        let candidate = PasswordManagementState.companion.observeLockState(
                            pending: pending,
                            newLockState: Int32(lockState)
                        )
                        if !(candidate is PasswordManagementState.PendingAck) {
                            return candidate
                        }
                    }
                    return nil
                }
                group.addTask {
                    try? await Task.sleep(nanoseconds: timeBudgetNanos)
                    return nil
                }
                let first = await group.next() ?? nil
                group.cancelAll()
                return first
            }

            if Task.isCancelled { return }

            let terminal: PasswordManagementState
            if let resolved = resolved {
                terminal = resolved
            } else {
                switch pending.operation {
                case PasswordManagementState.Operation.autoLockOn,
                     PasswordManagementState.Operation.autoLockOff:
                    // Auto-lock: bit 5 reflects current state, so a re-emitted
                    // same lockState matching the intent is a genuine
                    // confirmation. Snapshot once before declaring timeout.
                    let snap = PasswordManagementState.companion.observeLockState(
                        pending: pending,
                        newLockState: Int32(self.currentVeteranLockState())
                    )
                    if snap is PasswordManagementState.PendingAck {
                        terminal = PasswordManagementState.companion.timeout(pending: pending)
                    } else {
                        terminal = snap
                    }
                default:
                    // SET / MODIFY / CLEAR: bit 0 might be stale from a prior
                    // successful command. Require an actually-changed readback
                    // within the deadline; otherwise time out rather than
                    // trust the snapshot.
                    terminal = PasswordManagementState.companion.timeout(pending: pending)
                }
            }
            self.passwordManagementState = terminal
            if let confirmed = terminal as? PasswordManagementState.Confirmed {
                self.applyPasswordPersistence(confirmed: confirmed)
            }
        }
    }

    private func cancelPasswordAckTask() {
        passwordAckTask?.cancel()
        passwordAckTask = nil
    }

    private func currentVeteranLockState() -> Int {
        guard let veteran = wheelSettings as? WheelSettings.Veteran else { return -1 }
        return Int(veteran.lockState)
    }

    private func applyPasswordPersistence(confirmed: PasswordManagementState.Confirmed) {
        switch confirmed.persistence {
        case let store as PasswordManagementState.PersistenceActionStore:
            passwordStore.setPassword(address: confirmed.address, password: store.password)
        case is PasswordManagementState.PersistenceActionClear:
            passwordStore.clearPassword(address: confirmed.address)
        case is PasswordManagementState.PersistenceActionNoOp:
            break
        default:
            break
        }
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

    private func beginReconnectRecovery(for address: String) {
        recoveryEpisodeAddress = address
        guard autoReconnect || isLogging else { return }
        guard let acm = autoConnectManager else { return }

        let hint = WheelConnectionManagerHelper.shared.savedProfileHint(
            store: wheelProfileStore,
            address: address
        )
        WheelConnectionManagerHelper.shared.startReconnectRecovery(
            manager: acm,
            address: address,
            hint: hint
        )
    }

    private func clearRecoveryEpisode() {
        recoveryEpisodeAddress = nil
        lastLoggedReconnectAttempt = nil
    }

    private func shouldPreservePausedRideDuringReconnectFailure(
        address: String?,
        issueRecoverable: Bool
    ) -> Bool {
        guard isRidePaused else { return false }
        guard issueRecoverable else { return false }
        guard let recoveryEpisodeAddress else { return false }
        return address == recoveryEpisodeAddress
    }

    private func clearPauseState() {
        ridePauseTimeoutTask?.cancel()
        ridePauseTimeoutTask = nil
        isRidePaused = false
        pausedRideAddress = nil
        clearRecoveryEpisode()
    }

    func stopLogging() {
        clearPauseState()
        if let metadata = rideLogger.stopLogging(currentDistance: telemetry?.totalDistanceKm ?? 0) {
            rideStore.addRide(metadata)
        }
        isLogging = false
        liveRideStartDate = nil
        liveRideElapsedSeconds = 0
        liveRideMaxSpeedKmh = 0
        liveRideMaxPwmPercent = 0
        liveRideDistanceKm = 0
        liveRouteBuffer.clear()
        liveRoutePoints = []
        liveRouteSpeedRange = nil
    }

    // MARK: - Ride Stitch & Split

    /// Build a shareable GPX file in cache for [ride] and return its URL.
    /// Own-logged rides are converted from their CSV via the KMP CsvParser +
    /// GpxWriter; imported rides re-emit their stored GPX so the share is
    /// always the canonical file.
    func exportRideAsGpxURL(_ ride: RideMetadata) -> URL? {
        let ridesDir = RideStore.ridesDirectory()
        let storedURL = ridesDir.appendingPathComponent(ride.fileName)

        let gpx: String
        switch ride.source {
        case .imported:
            guard let content = try? String(contentsOf: storedURL, encoding: .utf8) else { return nil }
            gpx = content
        case .ownLog:
            guard let csv = try? String(contentsOf: storedURL, encoding: .utf8) else { return nil }
            let samples = CsvParser.shared.parseRideSamples(csvContent: csv)
            guard !samples.isEmpty else { return nil }
            let manifest = RideManifest(
                rideId: ride.id,
                name: ride.fileName.replacingOccurrences(of: ".csv", with: ""),
                startedAtUtc: Int64(ride.startDate.timeIntervalSince1970 * 1000),
                wheelType: nil,
                wheelName: nil,
                wheelAddress: nil,
                distanceMeters: KotlinLong(value: Int64(ride.distance * 1000)),
                durationMs: KotlinLong(value: Int64(ride.duration * 1000)),
                appVersion: "0.1.0",
                schemaVersion: Int32(RideManifest.companion.SCHEMA_VERSION_V1)
            )
            let bundle = RideBundle(manifest: manifest, samples: samples)
            gpx = GpxWriter.shared.write(bundle: bundle)
        }

        let cacheURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("share-\(ride.id).gpx")
        do {
            try gpx.write(to: cacheURL, atomically: true, encoding: .utf8)
        } catch {
            return nil
        }
        return cacheURL
    }

    /// Import a GPX file picked from the file system. Writes the file to
    /// `Documents/rides/imported/<rideId>.gpx`, upserts the RideStore row by
    /// rideId, returns the rideId on success or nil on parse failure.
    func importRideFromGpxURL(_ url: URL) -> String? {
        let needsScopedAccess = url.startAccessingSecurityScopedResource()
        defer { if needsScopedAccess { url.stopAccessingSecurityScopedResource() } }

        guard let content = try? String(contentsOf: url, encoding: .utf8) else { return nil }
        guard let bundle = GpxReader.shared.parse(input: content) else { return nil }

        let ridesDir = RideStore.ridesDirectory()
        let importedDir = ridesDir.appendingPathComponent("imported")
        try? FileManager.default.createDirectory(at: importedDir, withIntermediateDirectories: true)

        let storedFileName = "imported/\(bundle.manifest.rideId).gpx"
        let storedURL = ridesDir.appendingPathComponent(storedFileName)
        let canonicalGpx = GpxWriter.shared.write(bundle: bundle)
        try? canonicalGpx.write(to: storedURL, atomically: true, encoding: .utf8)

        let stats = derivedStats(from: bundle)
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: storedURL.path)[.size] as? Int64) ?? 0
        let firstSampleMs = bundle.samples.first?.timestampMs ?? bundle.manifest.startedAtUtc
        let lastSampleMs = bundle.samples.last?.timestampMs ?? firstSampleMs
        let durationSec = max(0, Double(lastSampleMs - firstSampleMs) / 1000.0)

        let metadata = RideMetadata(
            id: bundle.manifest.rideId,
            fileName: storedFileName,
            startDate: Date(timeIntervalSince1970: Double(bundle.manifest.startedAtUtc) / 1000),
            endDate: Date(timeIntervalSince1970: Double(lastSampleMs) / 1000),
            duration: durationSec,
            distance: Double(bundle.manifest.distanceMeters?.int64Value ?? Int64(stats.distanceMeters)) / 1000,
            maxSpeed: stats.maxSpeedKmh,
            avgSpeed: stats.avgSpeedKmh,
            sampleCount: bundle.samples.count,
            fileSize: fileSize,
            maxCurrent: stats.maxCurrentA,
            maxPower: stats.maxPowerW,
            maxPwm: stats.maxPwmPercent,
            consumptionWh: 0,
            consumptionWhPerKm: 0,
            source: .imported
        )
        rideStore.upsertByRideId(metadata)
        return bundle.manifest.rideId
    }

    private struct DerivedRideStats {
        let maxSpeedKmh: Double
        let avgSpeedKmh: Double
        let maxPwmPercent: Double
        let maxCurrentA: Double
        let maxPowerW: Double
        let distanceMeters: Double
    }

    private func derivedStats(from bundle: RideBundle) -> DerivedRideStats {
        let samples = bundle.samples
        let speeds = samples.compactMap { $0.speedKmh?.doubleValue }
        let maxSpeed = speeds.max() ?? 0
        let avgSpeed = speeds.isEmpty ? 0 : speeds.reduce(0, +) / Double(speeds.count)
        let maxPwm = samples.compactMap { $0.pwmPct?.doubleValue }.max() ?? 0
        let maxCurrent = samples.compactMap { $0.currentA?.doubleValue }.max() ?? 0
        let maxPower = samples.compactMap { $0.powerW?.doubleValue }.max() ?? 0
        let distance = bundle.manifest.distanceMeters?.doubleValue ?? estimateDistanceMeters(samples)
        return DerivedRideStats(
            maxSpeedKmh: maxSpeed,
            avgSpeedKmh: avgSpeed,
            maxPwmPercent: maxPwm,
            maxCurrentA: maxCurrent,
            maxPowerW: maxPower,
            distanceMeters: distance
        )
    }

    private func estimateDistanceMeters(_ samples: [RideSample]) -> Double {
        guard samples.count >= 2 else { return 0 }
        var total = 0.0
        for i in 1..<samples.count {
            total += haversineMeters(
                lat1: samples[i - 1].latitude, lng1: samples[i - 1].longitude,
                lat2: samples[i].latitude, lng2: samples[i].longitude
            )
        }
        return total
    }

    private func haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double) -> Double {
        let r = 6_371_000.0
        let dLat = (lat2 - lat1) * .pi / 180
        let dLng = (lng2 - lng1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180) *
            sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    func stitchRides(_ rideIds: [String]) -> Bool {
        let ridesDir = RideStore.ridesDirectory()
        let selectedRides = rideStore.rides.filter { rideIds.contains($0.id) }
        guard selectedRides.count >= 2 else { return false }

        var csvContents: [String] = []
        for ride in selectedRides {
            let fileURL = ridesDir.appendingPathComponent(ride.fileName)
            guard let content = try? String(contentsOf: fileURL, encoding: .utf8) else { continue }
            csvContents.append(content)
        }
        guard csvContents.count >= 2 else { return false }

        let earliestRide = selectedRides.min(by: { $0.startDate < $1.startDate })!
        let result = RideCsvEditor.shared.stitch(csvContents: csvContents, mergedFileName: earliestRide.fileName)

        // Write merged CSV
        let mergedURL = ridesDir.appendingPathComponent(earliestRide.fileName)
        try? result.mergedCsv.write(to: mergedURL, atomically: true, encoding: .utf8)

        // Remove originals (except the file being reused)
        let idsToRemove = Set(rideIds)
        for ride in selectedRides where ride.fileName != earliestRide.fileName {
            let fileURL = ridesDir.appendingPathComponent(ride.fileName)
            try? FileManager.default.removeItem(at: fileURL)
        }
        rideStore.rides.removeAll { idsToRemove.contains($0.id) }

        // Add merged metadata
        let meta = result.metadata
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: mergedURL.path)[.size] as? Int64) ?? 0
        let merged = RideMetadata(
            id: UUID().uuidString,
            fileName: meta.fileName,
            startDate: Date(timeIntervalSince1970: Double(meta.startTimeMillis) / 1000),
            endDate: Date(timeIntervalSince1970: Double(meta.endTimeMillis) / 1000),
            duration: Double(meta.durationSeconds),
            distance: Double(meta.distanceMeters) / 1000,
            maxSpeed: meta.maxSpeedKmh,
            avgSpeed: meta.avgSpeedKmh,
            sampleCount: Int(meta.sampleCount),
            fileSize: fileSize,
            maxCurrent: meta.maxCurrentA,
            maxPower: meta.maxPowerW,
            maxPwm: meta.maxPwmPercent,
            consumptionWh: meta.consumptionWh,
            consumptionWhPerKm: meta.consumptionWhPerKm
        )
        rideStore.addRide(merged)
        return true
    }

    func splitRide(_ ride: RideMetadata, atTimestampMs splitMs: Int64) -> Bool {
        let ridesDir = RideStore.ridesDirectory()
        let fileURL = ridesDir.appendingPathComponent(ride.fileName)
        guard let csvContent = try? String(contentsOf: fileURL, encoding: .utf8) else { return false }

        let secondFileName = "\(PlatformDateFormatter.shared.formatRideFilename(epochMs: splitMs)).csv"
        let result = RideCsvEditor.shared.split(
            csvContent: csvContent,
            splitTimestampMs: Int64(splitMs),
            firstFileName: ride.fileName,
            secondFileName: secondFileName
        )

        // Write both files
        try? result.firstCsv.write(to: fileURL, atomically: true, encoding: .utf8)
        let secondURL = ridesDir.appendingPathComponent(secondFileName)
        try? result.secondCsv.write(to: secondURL, atomically: true, encoding: .utf8)

        func makeSwiftMetadata(kmpMeta meta: FreeWheelCore.RideMetadata, url: URL) -> RideMetadata {
            let fileSize = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
            return RideMetadata(
                id: UUID().uuidString,
                fileName: meta.fileName,
                startDate: Date(timeIntervalSince1970: Double(meta.startTimeMillis) / 1000),
                endDate: Date(timeIntervalSince1970: Double(meta.endTimeMillis) / 1000),
                duration: Double(meta.durationSeconds),
                distance: Double(meta.distanceMeters) / 1000,
                maxSpeed: meta.maxSpeedKmh,
                avgSpeed: meta.avgSpeedKmh,
                sampleCount: Int(meta.sampleCount),
                fileSize: fileSize,
                maxCurrent: meta.maxCurrentA,
                maxPower: meta.maxPowerW,
                maxPwm: meta.maxPwmPercent,
                consumptionWh: meta.consumptionWh,
                consumptionWhPerKm: meta.consumptionWhPerKm
            )
        }

        let first = makeSwiftMetadata(kmpMeta: result.firstMetadata, url: fileURL)
        let second = makeSwiftMetadata(kmpMeta: result.secondMetadata, url: secondURL)
        rideStore.replaceRide(id: ride.id, with: [first, second])
        return true
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

    private func appendErrorLogRow(_ csvRow: String) {
        guard let handle = errorLogFileHandle else { return }
        if let data = (csvRow + "\n").data(using: .utf8) {
            handle.write(data)
            errorLogEventCount += 1
        }
    }

    private func appendRecoveryLogEvent(
        stage: String,
        address: String,
        attempt: Int? = nil,
        detail: String = ""
    ) {
        guard errorLogFileHandle != nil else { return }
        let csvRow = WheelConnectionManagerHelper.shared.formatRecoveryLogEvent(
            sessionStartMs: errorLogSessionStartMs,
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            stage: stage,
            address: address,
            attempt: Int32(attempt ?? 0),
            detail: detail
        )
        appendErrorLogRow(csvRow)
    }

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
        errorLogEventCount = 0

        if let cm = connectionManager {
            WheelConnectionManagerHelper.shared.setErrorLogCallback(
                manager: cm,
                sessionStartMs: nowMs
            ) { [weak self] csvRow in
                Task { @MainActor in
                    self?.appendErrorLogRow(csvRow)
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
            totalEventsLogged: Int32(errorLogEventCount)
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
        replayIdentityObserver = helper.observeReplayIdentity(engine: replayEngine) { [weak self] id in
            Task { @MainActor in
                guard let self = self, self.isReplayMode else { return }
                self.identity = id
            }
        }
        replayBmsObserver = helper.observeReplayBms(engine: replayEngine) { [weak self] bms in
            Task { @MainActor in
                guard let self = self, self.isReplayMode else { return }
                self.bmsState = bms
            }
        }
        replaySettingsObserver = helper.observeReplaySettings(engine: replayEngine) { [weak self] settings in
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
        replayIdentityObserver?.close()
        replayIdentityObserver = nil
        replayBmsObserver?.close()
        replayBmsObserver = nil
        replaySettingsObserver?.close()
        replaySettingsObserver = nil
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

        // Pass 4: prefer the saved per-MAC profile over the scan-time name
        // hint. A confirmed pick (SAVED_PROFILE) is durable user state and
        // short-circuits the WheelTypeRequired picker on Unknown topology;
        // the scan-name hint is a heuristic guess that yields to the picker.
        // Falling back to scan-name preserves the legacy iOS behavior for
        // wheels we've never connected to.
        //
        // Advertised name comes from the cache rather than
        // discoveredDevices[].name because the latter is
        // `peripheral.name ?: advertisedName` from the scan callback, so a
        // cached CBPeripheral.name (set during a prior connect) can shadow
        // the live advertisement local name. The cache stores advertisedName
        // separately and uncontaminated.
        let savedHint = WheelConnectionManagerHelper.shared.savedProfileHint(
            store: wheelProfileStore,
            address: address
        )
        let hint: ConnectionHint?
        if let savedHint {
            hint = savedHint
        } else {
            let advertisedName = bleManager?.getAdvertisement(address: address)?.advertisedName
            hint = WheelConnectionManagerHelper.shared.scanNameHint(rawName: advertisedName)
        }

        // Fire-and-forget — connection state updates come through StateFlow polling
        WheelConnectionManagerHelper.shared.connectWithHint(
            manager: connectionManager,
            address: address,
            hint: hint
        )
    }

    /// User picked a wheel type from `WheelTypePickerSheet` while in
    /// `.wheelTypeRequired`. Forwards to the connection manager, which
    /// transitions the live BLE session to Connected via ConfigureBle without
    /// a reconnect. Picker dismissal calls `disconnect()` instead — Pass 4
    /// guardrail: we never auto-pick.
    func confirmWheelType(_ wheelType: WheelType) {
        guard let connectionManager = connectionManager else { return }
        WheelConnectionManagerHelper.shared.confirmWheelType(
            manager: connectionManager,
            wheelType: wheelType
        )
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
        pendingUserDisconnect = true
        appSettingsStore.setLastMac(mac: "")

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
///
/// Carries the KMP `ConnectionIssueCode` directly — no Swift mirror — so the
/// enum stays a single source of truth in `core/.../ConnectionIssue.kt` and
/// callers can switch exhaustively on the typed value. `Equatable` is hand-
/// written because KMP enums bridge to Swift as classes (identity-equal
/// singletons per case) rather than synthesized-`Equatable` value types.
struct ConnectionIssueContext: Equatable {
    let code: ConnectionIssueCode
    let isRecoverable: Bool

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.code === rhs.code && lhs.isRecoverable == rhs.isRecoverable
    }
}

enum ConnectionStateWrapper: Equatable {
    case disconnected
    case scanning
    case connecting(address: String)
    case discoveringServices(address: String)
    case connected(address: String, wheelName: String)
    case connectionLost(address: String, reason: String, issue: ConnectionIssueContext)
    case failed(address: String?, error: String, issue: ConnectionIssueContext)
    /// Pass 4: BLE is connected and services discovered, but the wheel type
    /// couldn't be resolved from topology + name. The picker UI runs against
    /// the still-live peripheral; confirmation transitions to `.connected`.
    case wheelTypeRequired(address: String, deviceName: String?)

    var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }

    var isConnecting: Bool {
        switch self {
        case .connecting, .discoveringServices, .wheelTypeRequired:
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
        case .wheelTypeRequired(let address, _):
            return address
        default:
            return nil
        }
    }

    var connectedAddress: String? {
        if case .connected(let address, _) = self { return address }
        return nil
    }

    var failedAddress: String? {
        if case .failed(let address, _, _) = self { return address }
        return nil
    }

    var statusText: String {
        switch self {
        case .disconnected: return "Disconnected"
        case .scanning: return "Scanning..."
        case .connecting: return "Connecting..."
        case .discoveringServices: return "Discovering services..."
        case .connected(_, let name): return "Connected to \(name)"
        case .connectionLost(_, let reason, _): return "Connection lost: \(reason)"
        case .failed(_, let error, _): return "Failed: \(error)"
        case .wheelTypeRequired: return "Select wheel type"
        }
    }

    init(from kmpState: ConnectionState) {
        let helper = WheelConnectionManagerHelper.shared
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
            self = .connectionLost(
                address: lost.address,
                reason: lost.reason,
                issue: ConnectionIssueContext(
                    code: helper.connectionIssueCode(state: kmpState) ?? ConnectionIssueCode.unknown,
                    isRecoverable: helper.isRecoverableConnectionIssue(state: kmpState)
                )
            )
        case let failed as ConnectionState.Failed:
            self = .failed(
                address: failed.address,
                error: failed.error,
                issue: ConnectionIssueContext(
                    code: helper.connectionIssueCode(state: kmpState) ?? ConnectionIssueCode.unknown,
                    isRecoverable: helper.isRecoverableConnectionIssue(state: kmpState)
                )
            )
        case let required as ConnectionState.WheelTypeRequired:
            self = .wheelTypeRequired(address: required.address, deviceName: required.deviceName)
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
