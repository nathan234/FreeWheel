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
            TelemetryRow(label: "Voltage", value: String(format: "%.1f V", state.dcVoltage))
            TelemetryRow(label: "Current", value: String(format: "%.2f A", state.dcCurrent))
            TelemetryRow(label: "Power", value: String(format: "%.0f W", state.dcPower))
            if state.isCharging {
                Text("Charging")
                    .font(.caption)
                    .foregroundColor(.green)
            }
        }

        // AC Input
        GroupBox("AC Input") {
            TelemetryRow(label: "Voltage", value: String(format: "%.1f V", state.acVoltage))
            TelemetryRow(label: "Current", value: String(format: "%.2f A", state.acCurrent))
            TelemetryRow(label: "Power", value: String(format: "%.0f W", state.acPower))
            TelemetryRow(label: "Frequency", value: String(format: "%.1f Hz", state.acFrequency))
        }

        // Status
        GroupBox("Status") {
            TelemetryRow(label: "Efficiency", value: String(format: "%.1f%%", state.efficiency))
            TelemetryRow(label: "Temp 1", value: String(format: "%.1f \u{00B0}C", state.temperature1))
            TelemetryRow(label: "Temp 2", value: String(format: "%.1f \u{00B0}C", state.temperature2))
            TelemetryRow(label: "Current Limit", value: String(format: "%.1f A", state.currentLimitingPoint))
        }

        // Setpoints
        if state.targetVoltage > 0 || state.targetCurrent > 0 {
            GroupBox("Setpoints") {
                TelemetryRow(label: "Target Voltage", value: String(format: "%.1f V", state.targetVoltage))
                TelemetryRow(label: "Target Current", value: String(format: "%.1f A", state.targetCurrent))
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

    @State private var address = ""
    @State private var password = ""
    @State private var displayName = ""

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
        case .failed(_, let error):
            Text("Connection failed: \(error)")
                .foregroundColor(.red)
        case .connectionLost(_, let reason):
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

        // Manual connect
        Text("Connect to Charger")
            .font(.headline)

        TextField("MAC Address (AA:BB:CC:DD:EE:FF)", text: $address)
            .textFieldStyle(.roundedBorder)
            .autocapitalization(.allCharacters)
            .disableAutocorrection(true)

        SecureField("Password", text: $password)
            .textFieldStyle(.roundedBorder)

        TextField("Display Name (optional)", text: $displayName)
            .textFieldStyle(.roundedBorder)

        HStack {
            Button("Connect & Save") {
                let profile = ChargerManager.ChargerProfile(
                    address: address,
                    displayName: displayName.isEmpty ? "HW Charger" : displayName,
                    password: password,
                    lastConnectedMs: Date().timeIntervalSince1970 * 1000
                )
                chargerManager.saveProfile(profile)
                chargerManager.connect(address: address, password: password)
            }
            .buttonStyle(.borderedProminent)
            .disabled(address.isEmpty || password.isEmpty)

            Button("Connect Once") {
                chargerManager.connect(address: address, password: password)
            }
            .buttonStyle(.bordered)
            .disabled(address.isEmpty || password.isEmpty)
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
