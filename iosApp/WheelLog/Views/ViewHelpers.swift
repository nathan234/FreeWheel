import SwiftUI
import WheelLogCore

/// Shared unit conversion helpers used across multiple views.
/// Replaces private displaySpeed/displayTemp helpers in SettingsView, TelemetryChartView, TripDetailView.
extension View {
    func displaySpeed(_ kmh: Double, useMph: Bool) -> Double {
        DisplayUtils.shared.convertSpeed(kmh: kmh, useMph: useMph)
    }

    func displayTemp(_ celsius: Double, useFahrenheit: Bool) -> Double {
        DisplayUtils.shared.convertTemp(celsius: celsius, useFahrenheit: useFahrenheit)
    }
}
