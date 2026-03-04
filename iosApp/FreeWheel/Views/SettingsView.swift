import SwiftUI
import FreeWheelCore

// CROSS-PLATFORM SYNC: This view mirrors freewheel/.../compose/screens/SettingsScreen.kt.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Units: MPH toggle, Fahrenheit toggle
//  2. Alarms: Enable toggle, action picker, PWM-based toggle
//  3. PWM thresholds (if PWM-based): Factor 1, Factor 2
//  4. Pre-warnings (iOS only): Warning Speed, Warning PWM, Warning Period
//  5. Speed alarms (if not PWM-based): Alarm 1/2/3 speed + battery thresholds
//  6. Other alarms: Current, Phase Current, Temperature, Motor Temp, Battery, Wheel Alarm
//  7. Logging: Auto-start, Include GPS
//  8. Connection: Auto Reconnect, Show Unknown Devices
//  9. Wheel settings (config-driven, when connected)
// 10. About: Version, GitHub link
// 11. Close App button

struct SettingsView: View {
    @EnvironmentObject var wheelManager: WheelManager

    var body: some View {
        Form {
            // MARK: - Units
            Section(SettingsLabels.shared.SECTION_UNITS) {
                Toggle(SettingsLabels.shared.USE_MPH, isOn: $wheelManager.useMph)
                Toggle(SettingsLabels.shared.USE_FAHRENHEIT, isOn: $wheelManager.useFahrenheit)
            }

            // MARK: - Speed & Safety Alarms
            Section {
                Toggle(SettingsLabels.shared.ENABLE_ALARMS, isOn: $wheelManager.alarmsEnabled)

                if wheelManager.alarmsEnabled {
                    // Alarm action picker
                    Picker(SettingsLabels.shared.ALARM_ACTION, selection: $wheelManager.alarmAction) {
                        ForEach([FreeWheelCore.AlarmAction.phoneOnly, .phoneAndWheel, .all], id: \.self) { action in
                            Text(action.label).tag(action)
                        }
                    }

                    Toggle(SettingsLabels.shared.PWM_BASED_ALARMS, isOn: $wheelManager.pwmBasedAlarms)
                }
            } header: {
                Text(SettingsLabels.shared.SECTION_ALARMS)
            } footer: {
                if wheelManager.alarmsEnabled {
                    Text(wheelManager.pwmBasedAlarms
                        ? SettingsLabels.shared.PWM_DESCRIPTION
                        : SettingsLabels.shared.DISABLE_HINT)
                }
            }

            // PWM-based alarm settings
            if wheelManager.alarmsEnabled && wheelManager.pwmBasedAlarms {
                Section(SettingsLabels.shared.SECTION_PWM_THRESHOLDS) {
                    alarmSlider(
                        label: SettingsLabels.shared.ALARM_FACTOR_1,
                        value: $wheelManager.alarmFactor1,
                        range: 0...99,
                        displayValue: wheelManager.alarmFactor1,
                        unit: "%"
                    )
                    alarmSlider(
                        label: SettingsLabels.shared.ALARM_FACTOR_2,
                        value: $wheelManager.alarmFactor2,
                        range: 0...99,
                        displayValue: wheelManager.alarmFactor2,
                        unit: "%"
                    )
                }

                Section {
                    alarmSlider(
                        label: SettingsLabels.shared.WARNING_SPEED,
                        value: $wheelManager.warningSpeed,
                        range: 0...120,
                        displayValue: displaySpeed(wheelManager.warningSpeed),
                        unit: DisplayUtils.shared.speedUnit(useMph: wheelManager.useMph)
                    )
                    alarmSlider(
                        label: SettingsLabels.shared.WARNING_PWM,
                        value: $wheelManager.warningPwm,
                        range: 0...99,
                        displayValue: wheelManager.warningPwm,
                        unit: "%"
                    )
                    if wheelManager.warningSpeed > 0 || wheelManager.warningPwm > 0 {
                        alarmSlider(
                            label: SettingsLabels.shared.WARNING_PERIOD,
                            value: $wheelManager.warningSpeedPeriod,
                            range: 0...60,
                            displayValue: wheelManager.warningSpeedPeriod,
                            unit: "sec"
                        )
                    }
                } header: {
                    Text(SettingsLabels.shared.SECTION_PRE_WARNINGS)
                } footer: {
                    Text(SettingsLabels.shared.PRE_WARNING_HINT)
                }
            }

            // Old-style speed alarm settings
            if wheelManager.alarmsEnabled && !wheelManager.pwmBasedAlarms {
                Section(SettingsLabels.shared.SECTION_SPEED_ALARMS) {
                    alarmSlider(
                        label: SettingsLabels.shared.ALARM_1_SPEED,
                        value: $wheelManager.alarm1Speed,
                        range: 0...100,
                        displayValue: displaySpeed(wheelManager.alarm1Speed),
                        unit: DisplayUtils.shared.speedUnit(useMph: wheelManager.useMph)
                    )
                    alarmSlider(
                        label: SettingsLabels.shared.ALARM_1_BATTERY,
                        value: $wheelManager.alarm1Battery,
                        range: 0...100,
                        displayValue: wheelManager.alarm1Battery,
                        unit: "%"
                    )

                    alarmSlider(
                        label: SettingsLabels.shared.ALARM_2_SPEED,
                        value: $wheelManager.alarm2Speed,
                        range: 0...100,
                        displayValue: displaySpeed(wheelManager.alarm2Speed),
                        unit: DisplayUtils.shared.speedUnit(useMph: wheelManager.useMph)
                    )
                    alarmSlider(
                        label: SettingsLabels.shared.ALARM_2_BATTERY,
                        value: $wheelManager.alarm2Battery,
                        range: 0...100,
                        displayValue: wheelManager.alarm2Battery,
                        unit: "%"
                    )

                    alarmSlider(
                        label: SettingsLabels.shared.ALARM_3_SPEED,
                        value: $wheelManager.alarm3Speed,
                        range: 0...100,
                        displayValue: displaySpeed(wheelManager.alarm3Speed),
                        unit: DisplayUtils.shared.speedUnit(useMph: wheelManager.useMph)
                    )
                    alarmSlider(
                        label: SettingsLabels.shared.ALARM_3_BATTERY,
                        value: $wheelManager.alarm3Battery,
                        range: 0...100,
                        displayValue: wheelManager.alarm3Battery,
                        unit: "%"
                    )
                }
            }

            // Always-shown alarm types
            if wheelManager.alarmsEnabled {
                Section(SettingsLabels.shared.SECTION_OTHER_ALARMS) {
                    alarmSlider(
                        label: SettingsLabels.shared.CURRENT_ALARM,
                        value: $wheelManager.alarmCurrent,
                        range: 0...100,
                        displayValue: wheelManager.alarmCurrent,
                        unit: "A"
                    )
                    alarmSlider(
                        label: SettingsLabels.shared.PHASE_CURRENT_ALARM,
                        value: $wheelManager.alarmPhaseCurrent,
                        range: 0...400,
                        displayValue: wheelManager.alarmPhaseCurrent,
                        unit: "A"
                    )
                    alarmSlider(
                        label: SettingsLabels.shared.TEMPERATURE_ALARM,
                        value: $wheelManager.alarmTemperature,
                        range: 0...80,
                        displayValue: displayTemperature(wheelManager.alarmTemperature),
                        unit: DisplayUtils.shared.temperatureUnit(useFahrenheit: wheelManager.useFahrenheit)
                    )
                    alarmSlider(
                        label: SettingsLabels.shared.MOTOR_TEMP_ALARM,
                        value: $wheelManager.alarmMotorTemperature,
                        range: 0...200,
                        displayValue: displayTemperature(wheelManager.alarmMotorTemperature),
                        unit: DisplayUtils.shared.temperatureUnit(useFahrenheit: wheelManager.useFahrenheit)
                    )
                    alarmSlider(
                        label: SettingsLabels.shared.BATTERY_ALARM,
                        value: $wheelManager.alarmBattery,
                        range: 0...100,
                        displayValue: wheelManager.alarmBattery,
                        unit: "%"
                    )
                    Toggle(SettingsLabels.shared.WHEEL_ALARM, isOn: $wheelManager.alarmWheel)
                }
            }

            // MARK: - Logging (Feature 3)
            Section {
                Toggle(SettingsLabels.shared.AUTO_START_LOGGING, isOn: $wheelManager.autoStartLogging)
                Toggle(SettingsLabels.shared.INCLUDE_GPS, isOn: $wheelManager.logGPS)
            } header: {
                Text(SettingsLabels.shared.SECTION_LOGGING)
            } footer: {
                Text(SettingsLabels.shared.GPS_HINT)
            }

            // MARK: - Connection
            Section(SettingsLabels.shared.SECTION_CONNECTION) {
                Toggle(SettingsLabels.shared.AUTO_RECONNECT, isOn: $wheelManager.autoReconnect)
                Toggle(SettingsLabels.shared.SHOW_UNKNOWN_DEVICES, isOn: $wheelManager.showUnknownDevices)
            }

            // MARK: - Wheel Settings
            if wheelManager.connectionState.isConnected {
                WheelSettingsContent()
            }

            // MARK: - About
            Section(SettingsLabels.shared.SECTION_ABOUT) {
                HStack {
                    Text(SettingsLabels.shared.VERSION)
                    Spacer()
                    Text(appVersion)
                        .foregroundColor(.secondary)
                }
                if let url = URL(string: AppConstants.shared.GITHUB_REPO_URL) {
                    Link(SettingsLabels.shared.GITHUB_REPOSITORY, destination: url)
                }
            }

            // MARK: - Close App
            Section {
                Button(action: {
                    if wheelManager.isLogging { wheelManager.stopLogging() }
                    exit(0)
                }) {
                    HStack {
                        Spacer()
                        Text(SettingsLabels.shared.CLOSE_APP)
                            .fontWeight(.medium)
                        Spacer()
                    }
                }
                .foregroundColor(.red)
            }
        }
        .navigationTitle(SettingsLabels.shared.TITLE)
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Alarm Slider Helper

    private func alarmSlider(
        label: String,
        value: Binding<Double>,
        range: ClosedRange<Double>,
        displayValue: Double,
        unit: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(label)
                Spacer()
                Text("\(Int(displayValue)) \(unit)")
                    .foregroundColor(.secondary)
            }
            Slider(value: value, in: range, step: 1)
        }
    }

    // MARK: - Unit Conversion Helpers

    private func displaySpeed(_ kmh: Double) -> Double {
        displaySpeed(kmh, useMph: wheelManager.useMph)
    }

    private func displayTemperature(_ celsius: Double) -> Double {
        displayTemp(celsius, useFahrenheit: wheelManager.useFahrenheit)
    }

    private var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }
}

#Preview {
    NavigationStack {
        SettingsView()
            .environmentObject(WheelManager())
    }
}
