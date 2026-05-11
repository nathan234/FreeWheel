import SwiftUI
import FreeWheelCore

struct ChargerView: View {
    @EnvironmentObject var chargerManager: ChargerManager

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if chargerManager.connectionState.isConnected {
                    ConnectedChargerContent(chargerManager: chargerManager)
                } else {
                    DisconnectedChargerContent(chargerManager: chargerManager)
                }
            }
            .padding()
        }
        .navigationTitle("HW Charger")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Connected Content

private struct ConnectedChargerContent: View {
    @ObservedObject var chargerManager: ChargerManager

    @State private var voltageText = ""
    @State private var currentText = ""

    private var state: ChargerState { chargerManager.chargerState }

    var body: some View {
        // Output control
        GroupBox("Output Control") {
            Toggle("Output Enabled", isOn: Binding(
                get: { state.isOutputEnabled },
                set: { chargerManager.toggleOutput($0) }
            ))
        }

        // DC Output
        GroupBox("DC Output") {
            TelemetryRow(label: "Voltage", value: DisplayUtils.shared.formatChargerVoltage(voltage: state.dcVoltage))
            TelemetryRow(label: "Current", value: DisplayUtils.shared.formatChargerCurrent(current: state.dcCurrent))
            TelemetryRow(label: "Power", value: DisplayUtils.shared.formatChargerPower(watts: state.dcPower))
            if state.isCharging {
                Text("Charging")
                    .font(.caption)
                    .foregroundColor(.green)
            }
        }

        // AC Input
        GroupBox("AC Input") {
            TelemetryRow(label: "Voltage", value: DisplayUtils.shared.formatChargerVoltage(voltage: state.acVoltage))
            TelemetryRow(label: "Current", value: DisplayUtils.shared.formatChargerCurrent(current: state.acCurrent))
            TelemetryRow(label: "Power", value: DisplayUtils.shared.formatChargerPower(watts: state.acPower))
            TelemetryRow(label: "Frequency", value: DisplayUtils.shared.formatChargerFrequency(hz: state.acFrequency))
        }

        // Status
        GroupBox("Status") {
            TelemetryRow(label: "Efficiency", value: DisplayUtils.shared.formatChargerEfficiency(percent: state.efficiency))
            TelemetryRow(label: "Temp 1", value: DisplayUtils.shared.formatChargerTemperature(celsius: state.temperature1))
            TelemetryRow(label: "Temp 2", value: DisplayUtils.shared.formatChargerTemperature(celsius: state.temperature2))
            TelemetryRow(label: "Current Limit", value: DisplayUtils.shared.formatChargerCurrentLimit(amps: state.currentLimitingPoint))
        }

        // Setpoints
        if state.targetVoltage > 0 || state.targetCurrent > 0 {
            GroupBox("Setpoints") {
                TelemetryRow(label: "Target Voltage", value: DisplayUtils.shared.formatChargerVoltage(voltage: state.targetVoltage))
                TelemetryRow(label: "Target Current", value: DisplayUtils.shared.formatChargerCurrent(current: state.targetCurrent))
            }
        }

        // Firmware
        if !state.firmwareVersion.isEmpty {
            GroupBox("Info") {
                TelemetryRow(label: "Firmware", value: state.firmwareVersion)
            }
        }

        // Set output voltage/current
        GroupBox("Set Output") {
            HStack {
                TextField("Voltage (V)", text: $voltageText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                Button("Set") {
                    if let v = Float(voltageText) {
                        chargerManager.setOutputVoltage(v)
                    }
                }
                .buttonStyle(.bordered)
            }

            HStack {
                TextField("Current (A)", text: $currentText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                Button("Set") {
                    if let c = Float(currentText) {
                        chargerManager.setOutputCurrent(c)
                    }
                }
                .buttonStyle(.bordered)
            }
        }

        Button("Disconnect Charger", role: .destructive) {
            chargerManager.disconnect()
        }
        .buttonStyle(.bordered)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Disconnected Content

private struct DisconnectedChargerContent: View {
    @ObservedObject var chargerManager: ChargerManager

    @State private var selectedAddress: String?
    @State private var password = ""

    var body: some View {
        // Connection status
        switch chargerManager.connectionState {
        case .connecting:
            HStack {
                ProgressView()
                Text("Connecting...")
            }
            Button("Cancel") { chargerManager.disconnect() }
                .buttonStyle(.bordered)
        case .discoveringServices:
            HStack {
                ProgressView()
                Text("Discovering services...")
            }
            Button("Cancel") { chargerManager.disconnect() }
                .buttonStyle(.bordered)
        case .failed(_, let error, _):
            Text("Connection failed: \(error)")
                .foregroundColor(.red)
        case .connectionLost(_, let reason, _):
            Text("Connection lost: \(reason)")
                .foregroundColor(.red)
        default:
            EmptyView()
        }

        // Saved chargers
        let profiles = chargerManager.getSavedProfiles()
        if !profiles.isEmpty {
            Text("Saved Chargers")
                .font(.headline)

            ForEach(profiles, id: \.address) { profile in
                HStack {
                    VStack(alignment: .leading) {
                        Text(profile.displayName.isEmpty ? profile.address : profile.displayName)
                            .font(.body)
                        Text(profile.address)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Button("Connect") {
                        chargerManager.connect(address: profile.address, password: profile.password)
                    }
                    .buttonStyle(.borderedProminent)
                    Button("Delete", role: .destructive) {
                        chargerManager.deleteProfile(address: profile.address)
                    }
                    .buttonStyle(.bordered)
                }
                .padding(.vertical, 4)
            }

            Divider()
        }

        // Scan for chargers
        HStack {
            Text("Find Chargers")
                .font(.headline)
            Spacer()
            if chargerManager.isScanning {
                ProgressView()
                    .padding(.trailing, 4)
                Button("Stop") { chargerManager.stopScan() }
                    .buttonStyle(.bordered)
            } else {
                Button("Scan") { chargerManager.startScan() }
                    .buttonStyle(.borderedProminent)
            }
        }

        if !chargerManager.discoveredChargers.isEmpty {
            ForEach(chargerManager.discoveredChargers) { charger in
                let isSelected = selectedAddress == charger.address
                VStack(alignment: .leading, spacing: 8) {
                    Button {
                        selectedAddress = charger.address
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(charger.name)
                                    .font(.body)
                                Text(charger.address)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                            Spacer()
                            Text("\(charger.rssi) dBm")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    if isSelected {
                        SecureField("Password", text: $password)
                            .textFieldStyle(.roundedBorder)

                        HStack {
                            Button("Connect & Save") {
                                let profile = ChargerProfile(
                                    address: charger.address,
                                    displayName: charger.name,
                                    password: password,
                                    lastConnectedMs: Int64(Date().timeIntervalSince1970 * 1000)
                                )
                                chargerManager.saveProfile(profile)
                                chargerManager.connect(address: charger.address, password: password)
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(password.isEmpty)

                            Button("Connect Once") {
                                chargerManager.connect(address: charger.address, password: password)
                            }
                            .buttonStyle(.bordered)
                            .disabled(password.isEmpty)
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        } else if !chargerManager.isScanning {
            Text("Tap Scan to search for nearby HW chargers")
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
    }
}

// MARK: - Helpers

private struct TelemetryRow: View {
    let label: String
    let value: String

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .foregroundColor(.secondary)
        }
    }
}
