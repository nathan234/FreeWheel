import SwiftUI
import UIKit
import FreeWheelCore

/// Maps a NavigationTab's iconName to the corresponding SF Symbol.
/// Used by ContentView, NavigationEditView, and CustomTabView.
func sfSymbol(for iconName: String) -> String {
    switch iconName {
    case "bluetooth": return "antenna.radiowaves.left.and.right"
    case "show_chart": return "chart.xyaxis.line"
    case "battery_full": return "battery.100"
    case "route": return "road.lanes"
    case "tune": return "slider.horizontal.3"
    case "settings": return "gearshape"
    case "dashboard": return "gauge"
    case "speed": return "speedometer"
    case "star": return "star.fill"
    case "favorite": return "heart.fill"
    case "bolt": return "bolt.fill"
    case "visibility": return "eye"
    case "thermostat": return "thermometer.medium"
    default: return "questionmark"
    }
}

/// UIActivityViewController wrapper for sharing plain text from SwiftUI.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

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
