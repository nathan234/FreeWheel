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
            // Veteran lock/unlock prompt — driven entirely by
            // wheelManager.lockPromptState; renders nothing when .idle.
            .lockPrompt(wheelManager)
            // Veteran password-management dialog (set/modify/clear/auto-lock).
            // Driven by wheelManager.passwordManagementState; renders nothing
            // when .idle. Mirror of the Compose PasswordManagementDialog.
            .passwordManagement(wheelManager)
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
        // Veteran-only password-management entry. Mirrors the Compose
        // VeteranPasswordCard; renders nothing for non-Veteran wheels or
        // before the first subtype-5 readback (when bit 6 is unknown).
        VeteranPasswordSection(wheelManager: wheelManager)
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

// MARK: - Veteran lock prompt
//
// Inlined here (rather than a separate Views/LockPromptView.swift) because
// the Xcode project uses explicit file references — adding a new .swift
// file requires a pbxproj edit. The Compose equivalent lives in
// freewheel/.../components/LockPromptDialog.kt.

/// View modifier that mounts the Veteran lock-prompt dialog driven by
/// `WheelManager.lockPromptState`. Mirrors the Compose `LockPromptDialog`:
///   .idle  → no UI
///   .awaitingPassword → input alert with optional "remember password" toggle
///   .sending → progress alert (auto-clears when state moves to .sent)
///   .sent → auto-dismisses; the next subtype-5 readback reconciles the toggle
///   .error → error alert with retry/close
struct LockPromptModifier: ViewModifier {
    @ObservedObject var wheelManager: WheelManager

    @State private var passwordInput: String = ""
    @State private var rememberPassword: Bool = false

    func body(content: Content) -> some View {
        content
            .alert(
                awaitingTitle,
                isPresented: awaitingBinding,
                presenting: wheelManager.lockPromptState as? LockPromptState.AwaitingPassword
            ) { state in
                SecureField("Password", text: $passwordInput)
                    .keyboardType(.numberPad)
                if state.canPersistPassword {
                    Toggle("Remember password", isOn: $rememberPassword)
                }
                Button(state.action == LockPromptState.LockAction.lock ? "Lock" : "Unlock") {
                    wheelManager.submitLockPassword(passwordInput, rememberPassword: rememberPassword)
                }
                .disabled(passwordInput.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("Cancel", role: .cancel) {
                    wheelManager.dismissLockPrompt()
                }
            } message: { state in
                Text(state.action == LockPromptState.LockAction.lock
                     ? "Enter your wheel password to lock the motor."
                     : "Enter your wheel password to unlock the motor.")
            }
            .alert(
                sendingTitle,
                isPresented: sendingBinding,
                presenting: wheelManager.lockPromptState as? LockPromptState.Sending
            ) { _ in
                // SwiftUI alerts can't render a spinner; the ViewModel
                // transitions to Sent or Error within the same call so the
                // binding flips to false immediately after.
            } message: { _ in
                Text("Sending command to the wheel.")
            }
            .alert(
                errorTitle,
                isPresented: errorBinding,
                presenting: wheelManager.lockPromptState as? LockPromptState.Error
            ) { state in
                if state.reason != LockPromptState.ErrorReason.notConnected {
                    Button("Try again") { wheelManager.requestLock(action: state.action) }
                }
                Button("Close", role: .cancel) { wheelManager.dismissLockPrompt() }
            } message: { state in
                Text(errorMessage(for: state.reason))
            }
            .onReceive(wheelManager.$lockPromptState) { newValue in
                // Auto-dismiss on Sent so the prompt clears cleanly; subtype-5
                // readback reconciles the toggle visual.
                if newValue is LockPromptState.Sent {
                    wheelManager.dismissLockPrompt()
                }
                // Reset scratch input on close; prefill from AwaitingPassword
                // on open so the saved password (if any) appears.
                if let awaiting = newValue as? LockPromptState.AwaitingPassword {
                    passwordInput = awaiting.prefilledPassword ?? ""
                    // Default the "remember" toggle on when persistence is
                    // available AND a password is already saved (mirrors the
                    // Compose dialog's default-true rule).
                    rememberPassword = awaiting.canPersistPassword && awaiting.prefilledPassword != nil
                } else if newValue is LockPromptState.Idle {
                    passwordInput = ""
                    rememberPassword = false
                }
            }
    }

    private var awaitingBinding: Binding<Bool> {
        Binding(
            get: { wheelManager.lockPromptState is LockPromptState.AwaitingPassword },
            set: { isShowing in if !isShowing { wheelManager.dismissLockPrompt() } }
        )
    }

    private var sendingBinding: Binding<Bool> {
        Binding(
            get: { wheelManager.lockPromptState is LockPromptState.Sending },
            set: { _ in /* not user-dismissable */ }
        )
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { wheelManager.lockPromptState is LockPromptState.Error },
            set: { isShowing in if !isShowing { wheelManager.dismissLockPrompt() } }
        )
    }

    private var awaitingTitle: String {
        guard let state = wheelManager.lockPromptState as? LockPromptState.AwaitingPassword else { return "" }
        return state.action == LockPromptState.LockAction.lock ? "Lock wheel" : "Unlock wheel"
    }

    private var sendingTitle: String {
        guard let state = wheelManager.lockPromptState as? LockPromptState.Sending else { return "" }
        return state.action == LockPromptState.LockAction.lock ? "Locking…" : "Unlocking…"
    }

    private var errorTitle: String {
        guard let state = wheelManager.lockPromptState as? LockPromptState.Error else { return "" }
        return state.action == LockPromptState.LockAction.lock ? "Couldn't lock" : "Couldn't unlock"
    }

    private func errorMessage(for reason: LockPromptState.ErrorReason) -> String {
        switch reason {
        case LockPromptState.ErrorReason.emptyPassword:
            return "Password can't be empty."
        case LockPromptState.ErrorReason.invalidFormat:
            return "Password must be a number up to 16777215."
        case LockPromptState.ErrorReason.notConnected:
            return "The wheel disconnected. Reconnect and try again."
        default:
            return "An unknown error occurred."
        }
    }
}

extension View {
    /// Attach the Veteran lock prompt to this view. Renders nothing when the
    /// prompt is in `.idle`.
    func lockPrompt(_ wheelManager: WheelManager) -> some View {
        modifier(LockPromptModifier(wheelManager: wheelManager))
    }

    /// Attach the Veteran password-management dialog to this view. Renders
    /// nothing when the state is `.idle`.
    func passwordManagement(_ wheelManager: WheelManager) -> some View {
        modifier(PasswordManagementModifier(wheelManager: wheelManager))
    }
}

// MARK: - Veteran password-management entry section
//
// Mirrors the Compose VeteranPasswordCard. Visibility is driven by
// WheelSettings.Veteran.lockState bit 6:
//   - lockState < 0 (unknown) → renders nothing
//   - bit 6 clear (no password) → only "Set lock password" row
//   - bit 6 set  (has password) → "Change password", "Clear password",
//                                  and the Auto-lock toggle

private struct VeteranPasswordSection: View {
    @ObservedObject var wheelManager: WheelManager

    var body: some View {
        if let veteran = wheelManager.wheelSettings as? WheelSettings.Veteran,
           veteran.lockState >= 0 {
            let lockState = Int(veteran.lockState)
            let hasPassword = (lockState & 0x40) != 0
            let autoLockOn = (lockState & 0x20) != 0
            Section("Lock password") {
                if !hasPassword {
                    Button("Set lock password") {
                        wheelManager.requestPasswordManagement(PasswordManagementState.Operation.set)
                    }
                } else {
                    Button("Change password") {
                        wheelManager.requestPasswordManagement(PasswordManagementState.Operation.modify)
                    }
                    Button("Clear password", role: .destructive) {
                        wheelManager.requestPasswordManagement(PasswordManagementState.Operation.clear)
                    }
                    Toggle("Auto-lock", isOn: Binding(
                        get: { autoLockOn },
                        // Trigger the management flow on toggle; the dialog
                        // collects the current password and the wheel ack
                        // applies the visual change.
                        set: { newValue in
                            let op = newValue
                                ? PasswordManagementState.Operation.autoLockOn
                                : PasswordManagementState.Operation.autoLockOff
                            wheelManager.requestPasswordManagement(op)
                        }
                    ))
                }
            }
        }
    }
}

// MARK: - Password-management modifier
//
// Inlined in this file (rather than a separate Views/PasswordManagementView.swift)
// because the Xcode project uses explicit file references — adding a new
// .swift file requires a pbxproj edit. The Compose equivalent lives in
// freewheel/.../components/PasswordManagementDialog.kt.

/// View modifier that mounts the Veteran password-management dialog driven
/// by `WheelManager.passwordManagementState`. Mirrors the Compose
/// `PasswordManagementDialog`:
///   .idle        → no UI
///   .editing     → input alert with operation-specific fields
///   .submitting  → progress alert
///   .pendingAck  → progress alert (waiting for wheel confirmation)
///   .confirmed   → success alert that auto-dismisses
///   .failed      → error alert with retry/close
struct PasswordManagementModifier: ViewModifier {
    @ObservedObject var wheelManager: WheelManager

    @State private var oldPassword: String = ""
    @State private var newPassword: String = ""
    @State private var confirmPassword: String = ""
    @State private var rememberPassword: Bool = false

    func body(content: Content) -> some View {
        content
            .alert(
                editingTitle,
                isPresented: editingBinding,
                presenting: wheelManager.passwordManagementState as? PasswordManagementState.Editing
            ) { state in
                editingFields(for: state)
                Button(confirmLabel(for: state.operation)) {
                    let input = PasswordManagementInput(
                        oldPassword: oldPassword,
                        newPassword: newPassword,
                        confirmationPassword: confirmPassword,
                        rememberNewPassword: rememberPassword
                    )
                    wheelManager.submitPasswordManagement(input: input)
                }
                .disabled(!isSubmitEnabled(for: state))
                Button("Cancel", role: .cancel) {
                    wheelManager.dismissPasswordManagement()
                }
            } message: { state in
                Text(descriptionMessage(for: state.operation))
            }
            .alert(
                waitingTitle,
                isPresented: waitingBinding,
                presenting: waitingOperation
            ) { _ in
                // Progress alert. The state advances to .confirmed or
                // .failed via the WheelManager ack listener; the binding
                // flips false automatically.
            } message: { _ in
                Text("Waiting for wheel confirmation.")
            }
            .alert(
                confirmedTitle,
                isPresented: confirmedBinding,
                presenting: wheelManager.passwordManagementState as? PasswordManagementState.Confirmed
            ) { _ in
                Button("Done") { wheelManager.dismissPasswordManagement() }
            } message: { state in
                Text(successMessage(for: state.operation))
            }
            .alert(
                failedTitle,
                isPresented: failedBinding,
                presenting: wheelManager.passwordManagementState as? PasswordManagementState.Failed
            ) { state in
                if state.reason != PasswordManagementState.FailureReason.notConnected {
                    Button("Try again") {
                        wheelManager.requestPasswordManagement(state.operation)
                    }
                }
                Button("Close", role: .cancel) { wheelManager.dismissPasswordManagement() }
            } message: { state in
                Text(failureMessage(for: state.reason))
            }
            .onReceive(wheelManager.$passwordManagementState) { newValue in
                if let editing = newValue as? PasswordManagementState.Editing {
                    // Prefill current/old password from the store; reset the
                    // remember toggle default per the same rule as the lock
                    // prompt (default-on iff a saved password already exists).
                    let needsOld =
                        editing.operation != PasswordManagementState.Operation.set
                    oldPassword = needsOld ? (editing.priorStoredPassword ?? "") : ""
                    newPassword = ""
                    confirmPassword = ""
                    rememberPassword = editing.canPersistPassword && editing.priorStoredPassword != nil
                } else if newValue is PasswordManagementState.Idle {
                    oldPassword = ""
                    newPassword = ""
                    confirmPassword = ""
                    rememberPassword = false
                }
            }
    }

    // MARK: - Field rendering

    @ViewBuilder
    private func editingFields(for state: PasswordManagementState.Editing) -> some View {
        let needsOld = state.operation != PasswordManagementState.Operation.set
        let needsNew = state.operation == PasswordManagementState.Operation.set
            || state.operation == PasswordManagementState.Operation.modify
        if needsOld {
            SecureField(needsNew ? "Current password" : "Password", text: $oldPassword)
                .keyboardType(.numberPad)
        }
        if needsNew {
            SecureField("New password", text: $newPassword)
                .keyboardType(.numberPad)
            SecureField("Confirm new password", text: $confirmPassword)
                .keyboardType(.numberPad)
            if state.canPersistPassword {
                Toggle("Remember new password", isOn: $rememberPassword)
            }
        }
    }

    // MARK: - Binding helpers

    private var editingBinding: Binding<Bool> {
        Binding(
            get: { wheelManager.passwordManagementState is PasswordManagementState.Editing },
            set: { isShowing in if !isShowing { wheelManager.dismissPasswordManagement() } }
        )
    }

    private var waitingBinding: Binding<Bool> {
        Binding(
            get: {
                wheelManager.passwordManagementState is PasswordManagementState.Submitting
                    || wheelManager.passwordManagementState is PasswordManagementState.PendingAck
            },
            set: { _ in /* not user-dismissable */ }
        )
    }

    private var confirmedBinding: Binding<Bool> {
        Binding(
            get: { wheelManager.passwordManagementState is PasswordManagementState.Confirmed },
            set: { isShowing in if !isShowing { wheelManager.dismissPasswordManagement() } }
        )
    }

    private var failedBinding: Binding<Bool> {
        Binding(
            get: { wheelManager.passwordManagementState is PasswordManagementState.Failed },
            set: { isShowing in if !isShowing { wheelManager.dismissPasswordManagement() } }
        )
    }

    private var waitingOperation: PasswordManagementState.Operation? {
        if let submitting = wheelManager.passwordManagementState as? PasswordManagementState.Submitting {
            return submitting.operation
        }
        if let pending = wheelManager.passwordManagementState as? PasswordManagementState.PendingAck {
            return pending.operation
        }
        return nil
    }

    // MARK: - Submit gate

    private func isSubmitEnabled(for state: PasswordManagementState.Editing) -> Bool {
        let needsOld = state.operation != PasswordManagementState.Operation.set
        let needsNew = state.operation == PasswordManagementState.Operation.set
            || state.operation == PasswordManagementState.Operation.modify
        if needsOld && oldPassword.trimmingCharacters(in: .whitespaces).isEmpty { return false }
        if needsNew && (newPassword.isEmpty || confirmPassword.isEmpty) { return false }
        return true
    }

    // MARK: - Localized strings

    private var editingTitle: String {
        guard let state = wheelManager.passwordManagementState as? PasswordManagementState.Editing else { return "" }
        return title(for: state.operation)
    }

    private var waitingTitle: String {
        guard let op = waitingOperation else { return "" }
        return "\(title(for: op))…"
    }

    private var confirmedTitle: String {
        guard let state = wheelManager.passwordManagementState as? PasswordManagementState.Confirmed else { return "" }
        return successTitle(for: state.operation)
    }

    private var failedTitle: String {
        guard let state = wheelManager.passwordManagementState as? PasswordManagementState.Failed else { return "" }
        return failureTitle(for: state.operation)
    }

    private func title(for operation: PasswordManagementState.Operation) -> String {
        switch operation {
        case PasswordManagementState.Operation.set: return "Set lock password"
        case PasswordManagementState.Operation.modify: return "Change lock password"
        case PasswordManagementState.Operation.clear: return "Clear lock password"
        case PasswordManagementState.Operation.autoLockOn: return "Enable auto-lock"
        case PasswordManagementState.Operation.autoLockOff: return "Disable auto-lock"
        default: return ""
        }
    }

    private func confirmLabel(for operation: PasswordManagementState.Operation) -> String {
        switch operation {
        case PasswordManagementState.Operation.set: return "Set"
        case PasswordManagementState.Operation.modify: return "Change"
        case PasswordManagementState.Operation.clear: return "Clear"
        case PasswordManagementState.Operation.autoLockOn: return "Enable"
        case PasswordManagementState.Operation.autoLockOff: return "Disable"
        default: return "OK"
        }
    }

    private func descriptionMessage(for operation: PasswordManagementState.Operation) -> String {
        switch operation {
        case PasswordManagementState.Operation.set:
            return "Choose a numeric password the wheel will require for lock/unlock."
        case PasswordManagementState.Operation.modify:
            return "Enter your current password, then a new one."
        case PasswordManagementState.Operation.clear:
            return "Enter your current password to remove it from the wheel."
        case PasswordManagementState.Operation.autoLockOn:
            return "Enter your current password to enable auto-lock."
        case PasswordManagementState.Operation.autoLockOff:
            return "Enter your current password to disable auto-lock."
        default: return ""
        }
    }

    private func successTitle(for operation: PasswordManagementState.Operation) -> String {
        switch operation {
        case PasswordManagementState.Operation.set: return "Password set"
        case PasswordManagementState.Operation.modify: return "Password changed"
        case PasswordManagementState.Operation.clear: return "Password cleared"
        case PasswordManagementState.Operation.autoLockOn: return "Auto-lock enabled"
        case PasswordManagementState.Operation.autoLockOff: return "Auto-lock disabled"
        default: return ""
        }
    }

    private func successMessage(for operation: PasswordManagementState.Operation) -> String {
        switch operation {
        case PasswordManagementState.Operation.set:
            return "The wheel will now require this password to lock or unlock."
        case PasswordManagementState.Operation.modify:
            return "Use the new password from now on."
        case PasswordManagementState.Operation.clear:
            return "The wheel no longer requires a password to lock or unlock."
        case PasswordManagementState.Operation.autoLockOn:
            return "The wheel will lock itself automatically."
        case PasswordManagementState.Operation.autoLockOff:
            return "The wheel won't lock itself automatically anymore."
        default: return ""
        }
    }

    private func failureTitle(for operation: PasswordManagementState.Operation) -> String {
        switch operation {
        case PasswordManagementState.Operation.set: return "Couldn't set password"
        case PasswordManagementState.Operation.modify: return "Couldn't change password"
        case PasswordManagementState.Operation.clear: return "Couldn't clear password"
        case PasswordManagementState.Operation.autoLockOn: return "Couldn't enable auto-lock"
        case PasswordManagementState.Operation.autoLockOff: return "Couldn't disable auto-lock"
        default: return ""
        }
    }

    private func failureMessage(for reason: PasswordManagementState.FailureReason) -> String {
        switch reason {
        case PasswordManagementState.FailureReason.emptyPassword:
            return "Password can't be empty."
        case PasswordManagementState.FailureReason.invalidFormat:
            return "Password must be a number up to 16777215."
        case PasswordManagementState.FailureReason.passwordsDoNotMatch:
            return "The confirmation doesn't match the new password."
        case PasswordManagementState.FailureReason.notConnected:
            return "The wheel disconnected. Reconnect and try again."
        case PasswordManagementState.FailureReason.wrongPassword:
            return "The wheel rejected the command — check the current password."
        case PasswordManagementState.FailureReason.timeout:
            return "The wheel didn't confirm in time. Try again."
        default: return "An unknown error occurred."
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
