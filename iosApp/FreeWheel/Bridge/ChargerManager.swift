import Foundation
import FreeWheelCore

/// Manages HW Charger BLE connection independently from the wheel connection.
/// Owns its own BleManager and ChargerConnectionManager.
@MainActor
class ChargerManager: ObservableObject {
    // MARK: - Published State

    @Published private(set) var chargerState: ChargerState = ChargerState.companion.empty()
    @Published private(set) var connectionState: ConnectionStateWrapper = .disconnected

    // MARK: - Saved Charger Profiles

    @Published private(set) var savedAddresses: Set<String> = {
        let arr = UserDefaults.standard.stringArray(forKey: PreferenceKeys.shared.SAVED_CHARGER_ADDRESSES) ?? []
        return Set(arr)
    }()

    // MARK: - Private KMP Components

    private var bleManager: BleManager?
    private var chargerConnectionManager: ChargerConnectionManager?

    // MARK: - Flow Observers

    private var chargerStateObserver: FlowObservation?
    private var connectionStateObserver: FlowObservation?

    // MARK: - Initialization

    init() {
        setupKmpComponents()
    }

    deinit {
        chargerStateObserver?.close()
        connectionStateObserver?.close()
    }

    private func setupKmpComponents() {
        bleManager = BleManager()
        bleManager?.initialize(restoreIdentifier: "FreeWheelChargerBLE")

        guard let ble = bleManager else { return }
        chargerConnectionManager = ChargerConnectionManagerHelper.shared.create(bleManager: ble)

        guard let ccm = chargerConnectionManager else { return }

        // Wire BLE callbacks
        bleManager?.setDataReceivedCallback { [weak self] data in
            guard let ccm = self?.chargerConnectionManager else { return }
            ChargerConnectionManagerHelper.shared.onDataReceived(manager: ccm, data: data)
        }

        bleManager?.setServicesDiscoveredCallback { [weak self] _, _ in
            guard let ccm = self?.chargerConnectionManager else { return }
            ChargerConnectionManagerHelper.shared.onServicesDiscovered(manager: ccm)
        }

        bleManager?.setBleErrorCallback { [weak self] in
            guard let ccm = self?.chargerConnectionManager else { return }
            ChargerConnectionManagerHelper.shared.onBleError(manager: ccm)
        }

        // Start observing state flows
        startObserving(ccm)
    }

    private func startObserving(_ ccm: ChargerConnectionManager) {
        let helper = ChargerConnectionManagerHelper.shared

        chargerStateObserver = helper.observeChargerState(manager: ccm) { [weak self] state in
            Task { @MainActor in
                self?.chargerState = state
            }
        }

        connectionStateObserver = helper.observeConnectionState(manager: ccm) { [weak self] kmpState in
            Task { @MainActor in
                guard let self = self else { return }
                let newState = ConnectionStateWrapper(from: kmpState)
                guard newState != self.connectionState else { return }
                self.connectionState = newState

                // Auto-save profile when connected
                if case .connected(let address, _) = newState {
                    self.autoSaveProfile(address: address)
                }
            }
        }
    }

    // MARK: - Connection

    func connect(address: String, password: String) {
        guard let ccm = chargerConnectionManager else { return }
        ChargerConnectionManagerHelper.shared.connect(manager: ccm, address: address, password: password)
    }

    func disconnect() {
        guard let ccm = chargerConnectionManager else { return }
        ChargerConnectionManagerHelper.shared.disconnect(manager: ccm)
    }

    // MARK: - Commands

    func setOutputVoltage(_ voltage: Float) {
        guard let ccm = chargerConnectionManager else { return }
        ChargerConnectionManagerHelper.shared.setOutputVoltage(manager: ccm, voltage: voltage)
    }

    func setOutputCurrent(_ current: Float) {
        guard let ccm = chargerConnectionManager else { return }
        ChargerConnectionManagerHelper.shared.setOutputCurrent(manager: ccm, current: current)
    }

    func toggleOutput(_ enable: Bool) {
        guard let ccm = chargerConnectionManager else { return }
        ChargerConnectionManagerHelper.shared.toggleOutput(manager: ccm, enable: enable)
    }

    func setPowerLimit(_ watts: Int32) {
        guard let ccm = chargerConnectionManager else { return }
        ChargerConnectionManagerHelper.shared.setPowerLimit(manager: ccm, watts: watts)
    }

    func setTwoStageCharging(_ enabled: Bool) {
        guard let ccm = chargerConnectionManager else { return }
        ChargerConnectionManagerHelper.shared.setTwoStageCharging(manager: ccm, enabled: enabled)
    }

    func setEndOfChargeCurrent(_ current: Float) {
        guard let ccm = chargerConnectionManager else { return }
        ChargerConnectionManagerHelper.shared.setEndOfChargeCurrent(manager: ccm, current: current)
    }

    func setPowerOnOutput(_ enabled: Bool) {
        guard let ccm = chargerConnectionManager else { return }
        ChargerConnectionManagerHelper.shared.setPowerOnOutput(manager: ccm, enabled: enabled)
    }

    // MARK: - Profile Management

    struct ChargerProfile {
        let address: String
        var displayName: String
        var password: String
        var lastConnectedMs: Double
    }

    func getSavedProfiles() -> [ChargerProfile] {
        savedAddresses.map { address in
            ChargerProfile(
                address: address,
                displayName: UserDefaults.standard.string(forKey: address + PreferenceKeys.shared.SUFFIX_CHARGER_NAME) ?? "",
                password: UserDefaults.standard.string(forKey: address + PreferenceKeys.shared.SUFFIX_CHARGER_PASSWORD) ?? "",
                lastConnectedMs: UserDefaults.standard.double(forKey: address + PreferenceKeys.shared.SUFFIX_CHARGER_LAST_CONNECTED)
            )
        }.sorted { $0.lastConnectedMs > $1.lastConnectedMs }
    }

    func saveProfile(_ profile: ChargerProfile) {
        var addresses = savedAddresses
        addresses.insert(profile.address)
        UserDefaults.standard.set(Array(addresses), forKey: PreferenceKeys.shared.SAVED_CHARGER_ADDRESSES)
        UserDefaults.standard.set(profile.displayName, forKey: profile.address + PreferenceKeys.shared.SUFFIX_CHARGER_NAME)
        UserDefaults.standard.set(profile.password, forKey: profile.address + PreferenceKeys.shared.SUFFIX_CHARGER_PASSWORD)
        UserDefaults.standard.set(profile.lastConnectedMs, forKey: profile.address + PreferenceKeys.shared.SUFFIX_CHARGER_LAST_CONNECTED)
        savedAddresses = addresses
    }

    func deleteProfile(address: String) {
        var addresses = savedAddresses
        addresses.remove(address)
        UserDefaults.standard.set(Array(addresses), forKey: PreferenceKeys.shared.SAVED_CHARGER_ADDRESSES)
        UserDefaults.standard.removeObject(forKey: address + PreferenceKeys.shared.SUFFIX_CHARGER_NAME)
        UserDefaults.standard.removeObject(forKey: address + PreferenceKeys.shared.SUFFIX_CHARGER_PASSWORD)
        UserDefaults.standard.removeObject(forKey: address + PreferenceKeys.shared.SUFFIX_CHARGER_LAST_CONNECTED)
        savedAddresses = addresses
    }

    private func autoSaveProfile(address: String) {
        let existing = getSavedProfiles().first { $0.address == address }
        saveProfile(ChargerProfile(
            address: address,
            displayName: existing?.displayName ?? "HW Charger",
            password: existing?.password ?? "",
            lastConnectedMs: Date().timeIntervalSince1970 * 1000
        ))
    }
}
