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

    // Local state for write-only toggles and sliders
    @State private var toggleStates: [String: Bool] = [:]
    @State private var sliderValues: [String: Double] = [:]

    // Confirmation alert
    @State private var pendingAction: ControlSpec? = nil
    @State private var showConfirmation = false

    var body: some View {
        let sections = WheelSettingsConfig.shared.sections(wheelType: wheelManager.wheelState.wheelType)

        ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
            Section(section.title) {
                ForEach(Array(section.controls.enumerated()), id: \.offset) { _, control in
                    renderControl(control)
                }
            }
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
        Toggle(control.label, isOn: Binding(
            get: { toggleStates[key] ?? readback ?? false },
            set: { newValue in
                toggleStates[key] = newValue
                executeCommand(control.commandId, boolValue: newValue)
            }
        ))
    }

    @ViewBuilder
    private func renderSegmented(_ control: ControlSpec.Segmented) -> some View {
        let readback = readInt(control.commandId)
        let key = control.commandId.name

        VStack(alignment: .leading) {
            Picker(control.label, selection: Binding(
                get: { Int(sliderValues[key] ?? Double(readback ?? 0)) },
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
        }
    }

    @ViewBuilder
    private func renderPicker(_ control: ControlSpec.Picker) -> some View {
        let readback = readInt(control.commandId)
        let key = control.commandId.name

        Picker(control.label, selection: Binding(
            get: {
                let val = Int(sliderValues[key] ?? Double(readback ?? 0))
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
        let persistKey = PreferenceKeys.shared.WHEEL_SLIDER_PREFIX + key
        let persisted: Double? = UserDefaults.standard.object(forKey: persistKey) != nil
            ? UserDefaults.standard.double(forKey: persistKey) : nil
        let initial = readback.map { Double($0) } ?? persisted ?? Double(control.defaultValue)

        SliderRow(
            label: control.label,
            value: Binding(
                get: { sliderValues[key] ?? initial },
                set: { newValue in
                    sliderValues[key] = newValue
                }
            ),
            range: Double(control.min)...Double(control.max),
            unit: control.unit,
            step: Double(control.step),
            onEditingChanged: { editing in
                if !editing, let value = sliderValues[key] {
                    UserDefaults.standard.set(value, forKey: persistKey)
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
        return commandId.readInt(state: wheelManager.wheelState)?.intValue
    }

    private func readBool(_ commandId: SettingsCommandId) -> Bool? {
        return commandId.readBool(state: wheelManager.wheelState)?.boolValue
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
            WheelSettingsContent()
        }
        .navigationTitle(DashboardLabels.shared.WHEEL_SETTINGS)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Slider Row

private struct SliderRow: View {
    let label: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let unit: String
    let step: Double
    var onEditingChanged: ((Bool) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label)
                Spacer()
                Text("\(Int(value))\(unit.isEmpty ? "" : " \(unit)")")
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
