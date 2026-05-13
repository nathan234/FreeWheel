import SwiftUI
import FreeWheelCore

// CROSS-PLATFORM SYNC: This view mirrors freewheel/.../compose/screens/WheelSettingsScreen.kt.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Top bar with back button (WheelSettingsView only; WheelSettingsContent is embedded)
//  2. Dynamic sections from WheelSettingsConfig.sections(wheelType)
//  3. Control rendering: Toggle, Segmented, Picker, Slider, DangerousButton, DangerousToggle
//  4. Confirmation dialogs for dangerous actions (calibrate, power off, lock)
//  5. Empty state when no settings available for wheel type
//  Note: iOS has reusable WheelSettingsContent embedded in SettingsView;
//        Android has standalone WheelSettingsScreen + SectionCard component

// MARK: - Embeddable Wheel Settings Content

struct WheelSettingsContent: View {
    @EnvironmentObject var wheelManager: WheelManager

    // Local state for write-only toggles and sliders. Cleared whenever the
    // connected wheel's address changes so reconnecting to a different wheel
    // doesn't leak pending overrides from the previous wheel into the new
    // wheel's UI.
    @State private var toggleStates: [String: Bool] = [:]
    @State private var sliderValues: [String: Double] = [:]

    // Confirmation alert
    @State private var pendingAction: ControlSpec? = nil
    @State private var showConfirmation = false

    var body: some View {
        sectionsView
            .onChange(of: wheelManager.connectionState.connectedAddress) { _ in
                toggleStates.removeAll()
                sliderValues.removeAll()
            }
            .alert(
                confirmationTitle,
                isPresented: $showConfirmation,
                presenting: pendingAction
            ) { action in
                Button(CommonLabels.shared.CANCEL, role: .cancel) { pendingAction = nil }
                Button(CommonLabels.shared.CONFIRM, role: .destructive) {
                    executeAction(action)
                    pendingAction = nil
                }
            } message: { action in
                Text(confirmationMessage(for: action))
            }
    }

    @ViewBuilder
    private var sectionsView: some View {
        let sections = WheelSettingsConfig.shared.sections(wheelType: wheelManager.identity.wheelType, capabilities: wheelManager.capabilities)
        ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
            Section(section.title) {
                ForEach(Array(section.controls.enumerated()), id: \.offset) { _, control in
                    // Capability gating: skip controls the wheel reports as
                    // unsupported (e.g., Veteran 3-step Pedals Mode vs continuous
                    // Pedal Hardness mutual exclusion). See KMP
                    // SettingsCommandId.isAvailable KDoc.
                    if control.commandId.isAvailable(settings: wheelManager.wheelSettings) {
                        renderControl(control)
                    }
                }
            }
        }
    }

    // MARK: - Control Rendering

    @ViewBuilder
    private func renderControl(_ control: ControlSpec) -> some View {
        // Note: Without SKIE, sealed class exhaustiveness is not enforced by Swift.
        // If a new ControlSpec subclass is added in KMP, add a case here.
        if let toggle = control as? ControlSpec.Toggle {
            renderToggle(toggle)
        } else if let segmented = control as? ControlSpec.Segmented {
            renderSegmented(segmented)
        } else if let picker = control as? ControlSpec.Picker {
            renderPicker(picker)
        } else if let slider = control as? ControlSpec.Slider {
            renderSlider(slider)
        } else if let button = control as? ControlSpec.DangerousButton {
            renderDangerousButton(button)
        } else if let toggle = control as? ControlSpec.DangerousToggle {
            renderDangerousToggle(toggle)
        } else {
            Text("Unsupported control type")
                .foregroundColor(.red)
        }
    }

    @ViewBuilder
    private func renderToggle(_ control: ControlSpec.Toggle) -> some View {
        let key = control.commandId.name
        let readback = readBool(control.commandId)
        let effective = toggleStates[key] ?? readback
        // Disable the toggle until we have a real value — flipping an unread toggle
        // would commit a state we can't reconcile against the wheel.
        let isKnown = effective != nil
        Toggle(control.label, isOn: Binding(
            get: { effective ?? false },
            set: { newValue in
                toggleStates[key] = newValue
                executeCommand(control.commandId, boolValue: newValue)
            }
        ))
        .disabled(!isKnown)
    }

    @ViewBuilder
    private func renderSegmented(_ control: ControlSpec.Segmented) -> some View {
        let readback = readInt(control.commandId)
        let key = control.commandId.name
        // Mirror the Toggle pattern: leave the row deselected and disabled until
        // either readback arrives or the user has tapped a segment. Defaulting to
        // index 0 would misrepresent unread state on first connect.
        let pending: Int? = sliderValues[key].map { Int($0) }
        let effective = pending ?? readback
        let isKnown = effective != nil

        VStack(alignment: .leading) {
            Picker(control.label, selection: Binding(
                get: { effective ?? -1 },
                set: { newValue in
                    sliderValues[key] = Double(newValue)
                    executeCommand(control.commandId, intValue: Int32(newValue))
                }
            )) {
                ForEach(Array(control.options.enumerated()), id: \.offset) { index, label in
                    Text(label).tag(index)
                }
            }
            .pickerStyle(.segmented)
            .disabled(!isKnown)
        }
    }

    @ViewBuilder
    private func renderPicker(_ control: ControlSpec.Picker) -> some View {
        let readback = readInt(control.commandId)
        let key = control.commandId.name
        let pending: Int? = sliderValues[key].map { Int($0) }
        let effective = pending ?? readback
        let isKnown = effective != nil

        Picker(control.label, selection: Binding(
            get: {
                guard let val = effective else { return -1 }
                return min(val, control.options.count - 1)
            },
            set: { newValue in
                sliderValues[key] = Double(newValue)
                executeCommand(control.commandId, intValue: Int32(newValue))
            }
        )) {
            ForEach(Array(control.options.enumerated()), id: \.offset) { index, label in
                Text(label).tag(index)
            }
        }
        .disabled(!isKnown)
    }

    @ViewBuilder
    private func renderSlider(_ control: ControlSpec.Slider) -> some View {
        // Check visibility gating
        if let gate = control.visibleWhen {
            let gateKey = gate.name
            let gateOn = toggleStates[gateKey] ?? readBool(gate) ?? false
            if gateOn {
                sliderContent(control)
            }
        } else {
            sliderContent(control)
        }
    }

    @ViewBuilder
    private func sliderContent(_ control: ControlSpec.Slider) -> some View {
        let key = control.commandId.name
        let readback = readInt(control.commandId)
        // Slider fallback cache is scoped to the connected wheel's MAC. Without a connection
        // there is no persisted fallback — using a global key would let one wheel's last value
        // bleed into another wheel's UI.
        let persistKey: String? = wheelManager.connectionState.connectedAddress.map {
            PreferenceKeys.shared.wheelSliderKey(mac: $0, commandName: key)
        }
        let persisted: Double? = persistKey.flatMap { pk in
            UserDefaults.standard.object(forKey: pk) != nil
                ? UserDefaults.standard.double(forKey: pk)
                : nil
        }
        let initial = readback.map { Double($0) } ?? persisted ?? Double(control.defaultValue)
        let useMph = wheelManager.useMph

        SliderRow(
            label: control.label,
            value: Binding(
                get: { sliderValues[key] ?? initial },
                set: { newValue in
                    sliderValues[key] = newValue
                }
            ),
            range: Double(control.min)...Double(control.max),
            unit: control.displayUnit(useMph: useMph),
            step: Double(control.step),
            displayDivisor: Int(control.displayDivisor),
            unitCategory: control.unitCategory,
            useMph: useMph,
            onEditingChanged: { editing in
                if !editing, let value = sliderValues[key] {
                    if let pk = persistKey {
                        UserDefaults.standard.set(value, forKey: pk)
                    }
                    executeCommand(control.commandId, intValue: Int32(value))
                    // Clear local override so readback from wheel takes precedence.
                    // The persisted value serves as fallback until readback arrives.
                    sliderValues.removeValue(forKey: key)
                }
            }
        )
    }

    @ViewBuilder
    private func renderDangerousButton(_ control: ControlSpec.DangerousButton) -> some View {
        Button(control.label) {
            pendingAction = control
            showConfirmation = true
        }
        .foregroundColor(.red)
    }

    @ViewBuilder
    private func renderDangerousToggle(_ control: ControlSpec.DangerousToggle) -> some View {
        let key = control.commandId.name
        Toggle(control.label, isOn: Binding(
            get: { toggleStates[key] ?? false },
            set: { newValue in
                if newValue {
                    pendingAction = control
                    showConfirmation = true
                } else {
                    toggleStates[key] = false
                    executeCommand(control.commandId, boolValue: false)
                }
            }
        ))
    }

    // MARK: - Command Dispatch

    private func executeCommand(_ commandId: SettingsCommandId, intValue: Int32 = 0, boolValue: Bool = false) {
        wheelManager.executeCommand(commandId, intValue: intValue, boolValue: boolValue)
    }

    private func executeAction(_ action: ControlSpec) {
        if let button = action as? ControlSpec.DangerousButton {
            executeCommand(button.commandId)
        } else if let toggle = action as? ControlSpec.DangerousToggle {
            toggleStates[toggle.commandId.name] = true
            executeCommand(toggle.commandId, boolValue: true)
        }
    }

    // MARK: - State Readback

    private func readInt(_ commandId: SettingsCommandId) -> Int? {
        return commandId.readInt(settings: wheelManager.wheelSettings)?.intValue
    }

    private func readBool(_ commandId: SettingsCommandId) -> Bool? {
        return commandId.readBool(settings: wheelManager.wheelSettings)?.boolValue
    }

    // MARK: - Confirmation Helpers

    private var confirmationTitle: String {
        if let button = pendingAction as? ControlSpec.DangerousButton {
            return button.confirmTitle
        } else if let toggle = pendingAction as? ControlSpec.DangerousToggle {
            return toggle.confirmTitle
        }
        return ""
    }

    private func confirmationMessage(for action: ControlSpec) -> String {
        if let button = action as? ControlSpec.DangerousButton {
            return button.confirmMessage
        } else if let toggle = action as? ControlSpec.DangerousToggle {
            return toggle.confirmMessage
        }
        return ""
    }
}

// MARK: - Full Page Wrapper

struct WheelSettingsView: View {
    var body: some View {
        Form {
            ResetWheelTypeSection()
            WheelSettingsContent()
        }
        .navigationTitle(DashboardLabels.shared.WHEEL_SETTINGS)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Pass 4 "Reset wheel type" surface. Clears the saved per-MAC wheelType so
/// the next connect re-runs detection and lands in the picker if topology +
/// name detection both miss. Display name and other profile fields are
/// preserved.
private struct ResetWheelTypeSection: View {
    @EnvironmentObject var wheelManager: WheelManager
    @State private var showConfirm = false

    private var connectedAddress: String? {
        wheelManager.connectionState.connectedAddress
    }

    private var savedTypeLabel: String {
        let name = wheelManager.identity.wheelType.displayName
        return name.isEmpty ? "Unknown" : name
    }

    var body: some View {
        Section("Wheel type") {
            VStack(alignment: .leading, spacing: 4) {
                Text("Currently saved as \(savedTypeLabel).")
                Text("Reset to re-run detection on next connect (the picker appears if auto-detect can't decide).")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Button("Reset wheel type", role: .destructive) {
                showConfirm = true
            }
            .disabled(connectedAddress == nil)
        }
        .alert("Reset wheel type?", isPresented: $showConfirm) {
            Button(CommonLabels.shared.CANCEL, role: .cancel) {}
            Button(CommonLabels.shared.CONFIRM, role: .destructive) {
                if let address = connectedAddress {
                    wheelManager.resetWheelType(address: address)
                }
            }
        } message: {
            Text("On next connect to this wheel, FreeWheel will re-run detection. If the topology or name doesn't match a known protocol, the picker will appear.")
        }
    }
}

// MARK: - Slider Row

private struct SliderRow: View {
    let label: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let unit: String
    let step: Double
    var displayDivisor: Int = 1
    var unitCategory: UnitCategory = .none
    var useMph: Bool = false
    var onEditingChanged: ((Bool) -> Void)? = nil

    private var displayText: String {
        let converted = unitCategory == .speed
            ? DisplayUtils.shared.convertSpeed(kmh: value, useMph: useMph)
            : value
        let valText: String
        if displayDivisor > 1 {
            let displayed = converted / Double(displayDivisor)
            let decimalPlaces = max(0, Int(ceil(log10(Double(displayDivisor) / step))))
            valText = String(format: "%.\(decimalPlaces)f", displayed)
        } else {
            valText = "\(Int(converted))"
        }
        return unit.isEmpty ? valText : "\(valText) \(unit)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label)
                Spacer()
                Text(displayText)
                    .foregroundColor(.secondary)
            }
            Slider(value: $value, in: range, step: step) { editing in
                onEditingChanged?(editing)
            }
        }
    }
}

// MARK: - Preview

#Preview("KingSong") {
    NavigationStack {
        WheelSettingsView()
            .environmentObject(WheelManager())
    }
}
