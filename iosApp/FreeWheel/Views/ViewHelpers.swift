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
    case "map": return "map.fill"
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

/// Chart axis configuration shared by TelemetryChartView and MetricDetailView.
extension FreeWheelCore.ChartTimeRange {
    var axisStride: Calendar.Component {
        switch self {
        case .fiveMinutes: return .second
        case .oneHour: return .minute
        case .twentyFourHours: return .hour
        default: return .second
        }
    }

    var axisStrideCount: Int {
        switch self {
        case .fiveMinutes: return 10
        case .oneHour: return 5
        case .twentyFourHours: return 2
        default: return 10
        }
    }

    var axisFormat: Date.FormatStyle {
        if self == .fiveMinutes {
            return .dateTime.minute().second()
        } else {
            return .dateTime.hour().minute()
        }
    }
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
