import SwiftUI
import FreeWheelCore

// Settings screen structure is driven by AppSettingsConfig (KMP shared).
// Both Android and iOS render from the same config to prevent drift.

struct SettingsView: View {
    @EnvironmentObject var wheelManager: WheelManager

    var body: some View {
        let sections = AppSettingsConfig.shared.sections()
        let state = buildVisibilityState()

        Form {
            ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                if AppSettingVisibilityEvaluator.shared.isVisible(condition: section.visibility, state: state) {

                    // Wheel settings placeholder: delegate to existing WheelSettingsConfig
                    if section.title == AppSettingsConfig.shared.WHEEL_SETTINGS_TITLE {
                        if wheelManager.connectionState.isConnected {
                            WheelSettingsContent()
                        }
                    }
                    // Close app action button
                    else if let action = section.controls.first as? AppSettingSpec.ActionButton {
                        Section {
                            Button(action: {
                                if action.actionId == AppSettingsActions.shared.CLOSE_APP {
                                    if wheelManager.isLogging { wheelManager.stopLogging() }
                                    exit(0)
                                }
                            }) {
                                HStack {
                                    Spacer()
                                    Text(action.label)
                                        .fontWeight(.medium)
                                    Spacer()
                                }
                            }
                            .foregroundColor(action.isDestructive ? .red : .primary)
                        }
                    }
                    // Standard section
                    else {
                        let visibleControls = section.controls.filter {
                            AppSettingVisibilityEvaluator.shared.isVisible(condition: $0.visibility, state: state)
                        }
                        if !visibleControls.isEmpty || section.footer != nil {
                            Section {
                                ForEach(Array(visibleControls.enumerated()), id: \.offset) { _, control in
                                    renderAppControl(control)
                                }
                            } header: {
                                if !section.title.isEmpty {
                                    Text(section.title)
                                }
                            } footer: {
                                if let footer = section.footer {
                                    Text(footer)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(SettingsLabels.shared.TITLE)
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Visibility State

    /// Builds the visibility state by iterating every [AppSettingId] and routing
    /// through the same bindings the controls render with. New ids are picked up
    /// automatically — no hand-maintained subset that can silently drift behind
    /// new visibility rules in shared config.
    private func buildVisibilityState() -> AppSettingsState {
        var bools: [AppSettingId: KotlinBoolean] = [:]
        var ints: [AppSettingId: KotlinInt] = [:]
        for id in AppSettingId.entries {
            if id.isBool {
                bools[id] = KotlinBoolean(value: boolBinding(id).wrappedValue)
            } else {
                ints[id] = KotlinInt(value: Int32(intBinding(id).wrappedValue))
            }
        }
        return AppSettingsState(
            boolValues: bools,
            intValues: ints,
            isConnected: wheelManager.connectionState.isConnected,
            wheelType: wheelManager.identity.wheelType
        )
    }

    // MARK: - Control Rendering

    @ViewBuilder
    private func renderAppControl(_ control: AppSettingSpec) -> some View {
        if let toggle = control as? AppSettingSpec.Toggle {
            renderToggle(toggle)
        } else if let picker = control as? AppSettingSpec.Picker {
            renderPicker(picker)
        } else if let slider = control as? AppSettingSpec.Slider {
            renderSlider(slider)
        } else if let navLink = control as? AppSettingSpec.NavLink {
            renderNavLink(navLink)
        } else if let staticInfo = control as? AppSettingSpec.StaticInfo {
            renderStaticInfo(staticInfo)
        } else if let externalLink = control as? AppSettingSpec.ExternalLink {
            renderExternalLink(externalLink)
        } else if control is AppSettingSpec.ActionButton {
            // Handled at section level (standalone button rendering)
            EmptyView()
        }
    }

    @ViewBuilder
    private func renderToggle(_ spec: AppSettingSpec.Toggle) -> some View {
        Toggle(spec.label, isOn: boolBinding(spec.settingId))
    }

    @ViewBuilder
    private func renderPicker(_ spec: AppSettingSpec.Picker) -> some View {
        let selection = intBinding(spec.settingId)
        Picker(spec.label, selection: selection) {
            ForEach(Array(spec.options.enumerated()), id: \.offset) { index, label in
                Text(label).tag(index)
            }
        }
    }

    @ViewBuilder
    private func renderSlider(_ spec: AppSettingSpec.Slider) -> some View {
        let intSource = intBinding(spec.settingId)
        let doubleSource = Binding<Double>(
            get: { Double(intSource.wrappedValue) },
            set: { intSource.wrappedValue = Int($0) }
        )
        let useMph = wheelManager.useMph
        let useFahrenheit = wheelManager.useFahrenheit
        let displayVal = spec.displayValue(storedValue: Int32(intSource.wrappedValue),
                                           useMph: useMph,
                                           useFahrenheit: useFahrenheit)
        let unitText = spec.displayUnit(useMph: useMph, useFahrenheit: useFahrenheit)

        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(spec.label)
                Spacer()
                Text("\(displayVal) \(unitText)")
                    .foregroundColor(.secondary)
            }
            Slider(value: doubleSource,
                   in: Double(spec.min)...Double(spec.max),
                   step: 1)
        }
    }

    @ViewBuilder
    private func renderNavLink(_ spec: AppSettingSpec.NavLink) -> some View {
        switch spec.destinationId {
        case AppSettingsDestinations.shared.CUSTOMIZE_NAVIGATION:
            NavigationLink("Customize Navigation") {
                NavigationEditView()
            }
        case AppSettingsDestinations.shared.BLE_CAPTURE:
            NavigationLink("BLE Capture") {
                BleCaptureView()
            }
        case AppSettingsDestinations.shared.CONNECTION_ERROR_LOG:
            NavigationLink("Connection Error Log") {
                ConnectionErrorLogView()
            }
        case AppSettingsDestinations.shared.WHEEL_EVENT_LOG:
            NavigationLink("Wheel Event Log") {
                EventLogView(manager: wheelManager)
            }
        case AppSettingsDestinations.shared.DIAGNOSTICS:
            NavigationLink("Diagnostics") {
                DiagnosticsView()
            }
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private func renderStaticInfo(_ spec: AppSettingSpec.StaticInfo) -> some View {
        HStack {
            Text(spec.label)
            Spacer()
            Text(resolveValue(spec.valueId))
                .foregroundColor(.secondary)
        }
    }

    @ViewBuilder
    private func renderExternalLink(_ spec: AppSettingSpec.ExternalLink) -> some View {
        if let url = URL(string: spec.url) {
            Link(spec.label, destination: url)
        }
    }

    // MARK: - Value Resolution

    private func resolveValue(_ valueId: String) -> String {
        switch valueId {
        case AppSettingsValueIds.shared.APP_VERSION:
            return appVersion
        case AppSettingsValueIds.shared.BUILD_DATE:
            return buildDate
        default:
            return ""
        }
    }

    private var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }

    private var buildDate: String {
        // iOS doesn't have a build date constant like Android's BuildConfig.BUILD_DATE.
        // Return empty to skip display, or implement via Info.plist if desired.
        return ""
    }

    // MARK: - Binding Helpers (bridge AppSettingId to WheelManager @Published properties)

    /// Returns a bool binding for a known id. Hits assertionFailure for unmapped ids
    /// so a new shared AppSettingId without an iOS wire-up surfaces in DEBUG instead
    /// of silently dropping writes from the rendered control.
    private func boolBinding(_ id: AppSettingId) -> Binding<Bool> {
        switch id {
        case .useMph: return $wheelManager.useMph
        case .useFahrenheit: return $wheelManager.useFahrenheit
        case .alarmsEnabled: return $wheelManager.alarmsEnabled
        case .pwmBasedAlarms: return $wheelManager.pwmBasedAlarms
        case .alarmWheel: return $wheelManager.alarmWheel
        case .autoReconnect: return $wheelManager.autoReconnect
        case .showUnknownDevices: return $wheelManager.showUnknownDevices
        case .autoLog: return $wheelManager.autoStartLogging
        case .logLocationData: return $wheelManager.logGPS
        case .autoCapture: return $wheelManager.autoCapture
        case .autoTorchEnabled: return $wheelManager.autoTorchEnabled
        case .autoTorchUseSunset: return $wheelManager.autoTorchUseSunset
        default:
            assertionFailure("boolBinding missing for AppSettingId \(id)")
            return .constant(false)
        }
    }

    /// Returns an int binding for a known id. Sliders and pickers both store ints
    /// in shared config, so this is the single binding helper for non-bool controls.
    /// assertionFailure mirrors [boolBinding].
    private func intBinding(_ id: AppSettingId) -> Binding<Int> {
        switch id {
        case .alarmAction: return Binding(
            get: { Int(wheelManager.alarmAction.value) },
            set: { wheelManager.alarmAction = FreeWheelCore.AlarmAction.companion.fromValue(value: Int32($0)) }
        )
        case .alarmFactor1: return doubleAsInt(\.alarmFactor1)
        case .alarmFactor2: return doubleAsInt(\.alarmFactor2)
        case .warningSpeed: return doubleAsInt(\.warningSpeed)
        case .warningPwm: return doubleAsInt(\.warningPwm)
        case .warningSpeedPeriod: return doubleAsInt(\.warningSpeedPeriod)
        case .alarm1Speed: return doubleAsInt(\.alarm1Speed)
        case .alarm1Battery: return doubleAsInt(\.alarm1Battery)
        case .alarm2Speed: return doubleAsInt(\.alarm2Speed)
        case .alarm2Battery: return doubleAsInt(\.alarm2Battery)
        case .alarm3Speed: return doubleAsInt(\.alarm3Speed)
        case .alarm3Battery: return doubleAsInt(\.alarm3Battery)
        case .alarmCurrent: return doubleAsInt(\.alarmCurrent)
        case .alarmPhaseCurrent: return doubleAsInt(\.alarmPhaseCurrent)
        case .alarmTemperature: return doubleAsInt(\.alarmTemperature)
        case .alarmMotorTemperature: return doubleAsInt(\.alarmMotorTemperature)
        case .alarmBattery: return doubleAsInt(\.alarmBattery)
        case .autoTorchSpeedThreshold: return doubleAsInt(\.autoTorchSpeedThreshold)
        default:
            assertionFailure("intBinding missing for AppSettingId \(id)")
            return .constant(0)
        }
    }

    /// Bridge a Double-typed @Published on WheelManager to an Int binding. The
    /// underlying storage is int (via AppSettingsStore), so the Double round-trip
    /// is a UI artifact only.
    private func doubleAsInt(_ keyPath: ReferenceWritableKeyPath<WheelManager, Double>) -> Binding<Int> {
        Binding(
            get: { Int(wheelManager[keyPath: keyPath]) },
            set: { wheelManager[keyPath: keyPath] = Double($0) }
        )
    }
}

#Preview {
    NavigationStack {
        SettingsView()
            .environmentObject(WheelManager())
    }
}
