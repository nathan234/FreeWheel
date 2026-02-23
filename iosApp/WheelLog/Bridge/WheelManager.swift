import Foundation
import Combine
import WheelLogCore

/// Swift wrapper for KMP wheel management APIs.
/// Provides an ObservableObject interface for SwiftUI integration.
@MainActor
class WheelManager: ObservableObject {
    // MARK: - Published State

    @Published private(set) var wheelState: WheelState = WheelState.companion.empty()
    @Published private(set) var connectionState: ConnectionStateWrapper = .disconnected
    @Published private(set) var discoveredDevices: [DiscoveredDevice] = []
    @Published private(set) var isScanning: Bool = false
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
    @Published var alarmAction: WheelLogCore.AlarmAction = WheelLogCore.AlarmAction.companion.fromValue(value: Int32(UserDefaults.standard.integer(forKey: PreferenceKeys.shared.ALARM_ACTION))) {
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

    // MARK: - Saved Wheel Profiles

    private static var savedAddressesKey: String { PreferenceKeys.shared.SAVED_WHEEL_ADDRESSES }
    private static var profileNameSuffix: String { PreferenceKeys.shared.SUFFIX_PROFILE_NAME }
    private static var wheelTypeSuffix: String { PreferenceKeys.shared.SUFFIX_WHEEL_TYPE }
    private static var lastConnectedSuffix: String { PreferenceKeys.shared.SUFFIX_LAST_CONNECTED }

    @Published private(set) var savedAddresses: Set<String> = {
        let arr = UserDefaults.standard.stringArray(forKey: WheelManager.savedAddressesKey) ?? []
        return Set(arr)
    }()

    func getSavedDisplayName(address: String) -> String? {
        let name = UserDefaults.standard.string(forKey: address + Self.profileNameSuffix)
        return (name?.isEmpty ?? true) ? nil : name
    }

    func saveProfile(address: String, displayName: String, wheelTypeName: String) {
        var addresses = savedAddresses
        addresses.insert(address)
        UserDefaults.standard.set(Array(addresses), forKey: Self.savedAddressesKey)
        if !displayName.isEmpty {
            UserDefaults.standard.set(displayName, forKey: address + Self.profileNameSuffix)
        }
        UserDefaults.standard.set(wheelTypeName, forKey: address + Self.wheelTypeSuffix)
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: address + Self.lastConnectedSuffix)
        savedAddresses = addresses
    }

    func forgetProfile(address: String) {
        var addresses = savedAddresses
        addresses.remove(address)
        UserDefaults.standard.set(Array(addresses), forKey: Self.savedAddressesKey)
        UserDefaults.standard.removeObject(forKey: address + Self.wheelTypeSuffix)
        UserDefaults.standard.removeObject(forKey: address + Self.lastConnectedSuffix)
        // Keep profile_name — may be reused if wheel reconnects
        savedAddresses = addresses
    }

    // MARK: - KMP Components

    private var bleManager: BleManager?
    private var connectionManager: WheelConnectionManager?

    // MARK: - Demo Data Provider (KMP)

    private let demoProvider = WheelConnectionManagerHelper.shared.createDemoProvider()

    // MARK: - Feature Managers

    let alarmManager = AlarmManager()
    private var autoConnectManager: AutoConnectManager?
    let rideLogger = RideLogger()
    let rideStore = RideStore()
    let locationManager = LocationManager()
    let backgroundManager = BackgroundManager()
    let telemetryBuffer = TelemetryBuffer()
    let telemetryHistory = TelemetryHistoryBridge()

    // MARK: - Connection Tracking

    private var lastConnectedAddress: String?
    private var previousConnectionState: ConnectionStateWrapper = .disconnected

    // MARK: - Polling Timers

    private var statePollingTimer: Timer?
    private var demoPollingTimer: Timer?

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
            ("WheelLogSavedAddresses", PreferenceKeys.shared.SAVED_WHEEL_ADDRESSES),
        ]
        for (old, new) in migrations {
            if defaults.object(forKey: old) != nil && defaults.object(forKey: new) == nil {
                defaults.set(defaults.object(forKey: old), forKey: new)
            }
        }
        defaults.set(true, forKey: "PreferenceKeysMigrated_v1")
    }()

    nonisolated init() {
        // Trigger one-time key migration before anything else
        _ = Self._migrationOnce
        // Setup happens in Task
        Task { @MainActor in
            self.setupKmpComponents()
            self.setupAlarmCallbacks()
            self.startPolling()
            self.backgroundManager.requestNotificationPermission()

            self.startupScan()

            // Auto-enable mock mode on simulator
            #if targetEnvironment(simulator)
            self.isMockMode = true
            #endif
        }
    }

    private var startupScanTarget: String?

    private func startupScan() {
        guard let storedUUID = UserDefaults.standard.string(forKey: "WheelLogLastPeripheralUUID"),
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
        statePollingTimer?.invalidate()
        statePollingTimer = nil
        demoPollingTimer?.invalidate()
        demoPollingTimer = nil
        WheelConnectionManagerHelper.shared.stopDemo(provider: demoProvider)
    }

    private func setupKmpComponents() {
        // Initialize KMP BLE manager
        bleManager = BleManager()
        bleManager?.initialize()

        // Create WheelConnectionManager using iOS factory
        guard let ble = bleManager else { return }
        connectionManager = WheelConnectionManagerHelper.shared.create(bleManager: ble)

        // Wire BLE data to connection manager
        bleManager?.setDataReceivedCallback { [weak self] data in
            self?.connectionManager?.onDataReceived(data: data)
        }

        // Create shared auto-connect manager
        guard let cm = connectionManager else { return }
        autoConnectManager = WheelConnectionManagerHelper.shared.createAutoConnectManager(manager: cm)

        // Wire service discovery to connection manager
        bleManager?.setServicesDiscoveredCallback { [weak self] services, deviceName in
            guard let self = self else { return }
            self.connectionManager?.onServicesDiscovered(services: services, deviceName: deviceName)

            // After wheel type detection, configure BLE manager with the detected UUIDs
            // so it can match and subscribe to the correct characteristics
            if let info = self.connectionManager?.getConnectionInfo() {
                self.bleManager?.configureForWheel(
                    readServiceUuid: info.readServiceUuid,
                    readCharUuid: info.readCharacteristicUuid,
                    writeServiceUuid: info.writeServiceUuid,
                    writeCharUuid: info.writeCharacteristicUuid
                )
            }
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
            self.startDemoPolling()
        }
    }

    func stopMockMode() {
        demoPollingTimer?.invalidate()
        demoPollingTimer = nil
        WheelConnectionManagerHelper.shared.stopDemo(provider: demoProvider)
        isMockMode = false
        connectionState = .disconnected
        wheelState = WheelState.companion.empty()
        telemetryBuffer.clear()
    }

    private func startDemoPolling() {
        demoPollingTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.pollDemoState()
            }
        }
    }

    private func pollDemoState() {
        let kmpState = WheelConnectionManagerHelper.shared.getDemoState(provider: demoProvider)
        let newWheelState = kmpState
        guard newWheelState != wheelState else { return }

        wheelState = newWheelState

        // Feed telemetry buffer and history for chart view
        let gpsSpeed = ByteUtils.shared.metersPerSecondToKmh(speedMs: max(0, locationManager.currentLocation?.speed ?? 0))
        let demoSample = WheelLogCore.TelemetrySample.companion.fromWheelState(
            state: kmpState,
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            gpsSpeedKmh: gpsSpeed
        )
        telemetryBuffer.addSampleIfNeeded(sample: demoSample)
        telemetryHistory.addSample(demoSample)

        // Check alarms via KMP
        let kmpAlarmState = WheelConnectionManagerHelper.shared.getDemoState(provider: demoProvider)
        let alarmConfig = buildAlarmConfig()
        alarmManager.checkAlarms(state: kmpAlarmState, config: alarmConfig, enabled: alarmsEnabled, action: alarmAction)
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
        wheelState = WheelState.companion.empty()
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

    // MARK: - Polling

    private func startPolling() {
        // Poll wheel state and connection state at 20Hz
        statePollingTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.pollState()
            }
        }
    }

    private func pollState() {
        guard !isMockMode else { return }  // Demo mode uses its own polling timer
        guard let cm = connectionManager else { return }

        // In test mode, only poll wheel state (not connection state)
        if isTestMode {
            let kmpWheelState = WheelConnectionManagerHelper.shared.getWheelState(manager: cm)
            let newWheelState = kmpWheelState
            if newWheelState != wheelState {
                syncUnitsFromWheel(newWheelState)
                wheelState = newWheelState
            }
            return
        }

        // Poll connection state using iOS helper
        let kmpConnectionState = WheelConnectionManagerHelper.shared.getConnectionState(manager: cm)
        let newState = ConnectionStateWrapper(from: kmpConnectionState)
        if newState != connectionState {
            handleConnectionStateChange(from: connectionState, to: newState)
            connectionState = newState
        }

        // Poll wheel state using iOS helper
        let kmpWheelState = WheelConnectionManagerHelper.shared.getWheelState(manager: cm)
        let newWheelState = kmpWheelState
        if newWheelState != wheelState {
            syncUnitsFromWheel(newWheelState)
            wheelState = newWheelState
        }

        // Update scanning state
        if case .scanning = connectionState {
            isScanning = true
        } else if isScanning && connectionState != .scanning {
            isScanning = false
        }

        // Feature 1: Check alarms when connected via KMP
        if connectionState.isConnected {
            let kmpAlarmState = WheelConnectionManagerHelper.shared.getWheelState(manager: cm)
            let alarmConfig = buildAlarmConfig()
            alarmManager.checkAlarms(state: kmpAlarmState, config: alarmConfig, enabled: alarmsEnabled, action: alarmAction)
            activeAlarms = alarmManager.activeAlarms
        }

        // Feature 3: Write ride log sample
        if isLogging {
            rideLogger.writeSample(
                state: kmpWheelState,
                location: locationManager.currentLocation,
                includeGPS: logGPS
            )
        }

        // Feature 6: Telemetry buffer sampling + history
        if connectionState.isConnected {
            let gpsSpeedPoll = ByteUtils.shared.metersPerSecondToKmh(speedMs: max(0, locationManager.currentLocation?.speed ?? 0))
            let telSample = WheelLogCore.TelemetrySample.companion.fromWheelState(
                state: kmpWheelState,
                timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
                gpsSpeedKmh: gpsSpeedPoll
            )
            telemetryBuffer.addSampleIfNeeded(sample: telSample)
            telemetryHistory.addSample(telSample)
        }

        // Feature 2: Update reconnect state + auto-connect from shared KMP manager
        if let acm = autoConnectManager {
            let helper = WheelConnectionManagerHelper.shared
            isAutoConnecting = helper.getIsAutoConnecting(manager: acm)

            let kmpReconnectState = helper.getReconnectState(manager: acm)
            if helper.isReconnectIdle(state: kmpReconnectState) {
                reconnectState = .idle
            } else if helper.isReconnectWaiting(state: kmpReconnectState) {
                reconnectState = .waiting(
                    attempt: Int(helper.reconnectAttemptNumber(state: kmpReconnectState)),
                    nextRetryMs: helper.reconnectNextRetryMs(state: kmpReconnectState)
                )
            } else if helper.isReconnectAttempting(state: kmpReconnectState) {
                reconnectState = .attempting(
                    attempt: Int(helper.reconnectAttemptNumber(state: kmpReconnectState))
                )
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
            // Auto-connect flags are cleared automatically by the shared AutoConnectManager
            // via its connection state observer

            // Push current unit preferences to decoder
            pushDecoderConfig()

            // Auto-save wheel profile
            let displayName = wheelState.displayName == "Dashboard" ? "" : wheelState.displayName
            saveProfile(address: address, displayName: displayName, wheelTypeName: wheelState.wheelType.name)

            // Load telemetry history for this wheel
            telemetryHistory.loadForWheel(address: address)

            // Start GPS tracking for speed tile / telemetry
            locationManager.startTracking()

            // Auto-start logging if enabled
            if autoStartLogging && !isLogging {
                startLogging()
            }
        }

        // Detect connection lost → start auto-reconnect via shared manager
        if case .connectionLost(let address, _) = newState {
            if autoReconnect {
                if let acm = autoConnectManager {
                    WheelConnectionManagerHelper.shared.startReconnecting(manager: acm, address: address)
                }
            }

            // Stop logging on disconnect
            if isLogging {
                stopLogging()
            }

            locationManager.stopTracking()
            telemetryHistory.save()
            telemetryBuffer.clear()
        }

        // Also handle explicit disconnected state
        if case .disconnected = newState, oldState.isConnected {
            if isLogging {
                stopLogging()
            }
            locationManager.stopTracking()
            telemetryHistory.save()
            telemetryBuffer.clear()
        }
    }

    // MARK: - DecoderConfig Propagation

    private func pushDecoderConfig() {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.updateDecoderConfig(manager: cm, useMph: useMph, useFahrenheit: useFahrenheit)
    }

    // MARK: - Unit Sync

    /// Auto-set useMph based on the wheel's reported miles setting.
    /// Called when wheel state changes so the app matches the wheel's configuration.
    private func syncUnitsFromWheel(_ newState: WheelState) {
        if newState.inMiles != wheelState.inMiles {
            useMph = newState.inMiles
        }
    }

    // MARK: - Wheel Commands

    func wheelBeep() {
        guard let cm = connectionManager else { return }
        WheelConnectionManagerHelper.shared.sendBeep(manager: cm)
    }

    func toggleLight() {
        guard let cm = connectionManager else { return }
        isLightOn.toggle()
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
        if let metadata = rideLogger.stopLogging(currentDistance: wheelState.totalDistanceKm) {
            rideStore.addRide(metadata)
        }
        isLogging = false
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

        connectionState = .connecting(address: address)

        // Use WheelConnectionManager for connection
        connectionManager.connect(address: address, wheelType: nil) { error in
            if let error = error {
                Task { @MainActor in
                    self.connectionState = .failed(address: address, error: error.localizedDescription)
                }
            }
            // Connection state will be updated through polling
        }
    }

    func stopReconnecting() {
        if let acm = autoConnectManager {
            WheelConnectionManagerHelper.shared.stopAutoConnect(manager: acm)
        }
    }

    func disconnect() {
        guard let connectionManager = connectionManager else { return }

        // Explicit disconnect — stop reconnection and clear saved address
        if let acm = autoConnectManager {
            WheelConnectionManagerHelper.shared.stopAutoConnect(manager: acm)
        }
        UserDefaults.standard.removeObject(forKey: "WheelLogLastPeripheralUUID")

        // Stop logging
        if isLogging {
            stopLogging()
        }

        connectionManager.disconnect { [weak self] error in
            Task { @MainActor in
                self?.connectionState = .disconnected
                self?.wheelState = WheelState.companion.empty()
                self?.telemetryBuffer.clear()
                if let error = error {
                    print("Disconnect error: \(error.localizedDescription)")
                }
            }
        }
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
        wheelState: WheelState = WheelState.companion.empty(),
        devices: [DiscoveredDevice] = []
    ) -> WheelManager {
        let manager = WheelManager()
        // Note: Would need to expose setters for preview, this is a placeholder
        return manager
    }
}
#endif
