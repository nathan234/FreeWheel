import SwiftUI
import FreeWheelCore

/// Renders a DashboardMetric as a GaugeTileView.
/// Stateless — all inputs provided as parameters, no @EnvironmentObject.
struct MetricGaugeTile: View {
    let metric: DashboardMetric
    let wheelState: WheelState
    let gpsSpeed: Double
    let useMph: Bool
    let useFahrenheit: Bool
    let sparklineData: [Double]
    let action: () -> Void
    var onLongPress: (() -> Void)? = nil

    private var rawValue: Double {
        metric.extractValue(state: wheelState)?.doubleValue ?? gpsSpeed
    }

    private var displayValue: Double {
        DisplayUtils.shared.convertMetricValue(value: rawValue, metric: metric, useMph: useMph, useFahrenheit: useFahrenheit)
    }

    private var displayUnit: String {
        DisplayUtils.shared.metricUnit(metric: metric, useMph: useMph, useFahrenheit: useFahrenheit)
    }

    private var maxValue: Double {
        if metric.isSpeedMetric && metric.maxValue > 0 {
            return DisplayUtils.shared.maxSpeedDefault(useMph: useMph)
        }
        return metric.maxValue
    }

    private var progress: Double {
        guard maxValue > 0 else { return 0 }
        return abs(displayValue) / maxValue
    }

    private var color: Color {
        let effMax = metric.effectiveMax(wheelState: wheelState)
        let rawProgress = effMax > 0 ? abs(rawValue) / effMax : 0
        let zone = metric.colorZone(progress: rawProgress)
        switch zone {
        case .green: return .green
        case .orange: return .orange
        case .red: return .red
        default: return .gray
        }
    }

    private var formattedValue: String {
        if metric == .gpsSpeed && rawValue <= 0 { return "\u{2014}" }
        return String(format: "%.\(metric.decimals)f", displayValue)
    }

    var body: some View {
        GaugeTileView(
            label: metric.label,
            value: formattedValue,
            unit: displayUnit,
            progress: progress,
            color: color,
            sparklineData: sparklineData,
            action: action,
            onLongPress: onLongPress
        )
    }
}

/// Renders a DashboardMetric as a StatRow.
/// Stateless — all inputs provided as parameters, no @EnvironmentObject.
struct MetricStatRow: View {
    let metric: DashboardMetric
    let wheelState: WheelState
    let gpsSpeed: Double
    let useMph: Bool
    let useFahrenheit: Bool

    private var rawValue: Double {
        metric.extractValue(state: wheelState)?.doubleValue ?? gpsSpeed
    }

    private var formattedValue: String {
        if metric == .fanStatus {
            return rawValue > 0 ? "On" : "Off"
        }
        if metric.isDistanceMetric {
            return DisplayUtils.shared.formatDistance(km: rawValue, useMph: useMph, decimals: Int32(metric.decimals))
        }
        let converted = DisplayUtils.shared.convertMetricValue(value: rawValue, metric: metric, useMph: useMph, useFahrenheit: useFahrenheit)
        let unit = DisplayUtils.shared.metricUnit(metric: metric, useMph: useMph, useFahrenheit: useFahrenheit)
        return "\(StringUtil.shared.formatDecimal(value: converted, decimals: Int32(metric.decimals))) \(unit)"
    }

    private var valueColor: Color? {
        let effMax = metric.effectiveMax(wheelState: wheelState)
        let rawProgress = effMax > 0 ? abs(rawValue) / effMax : 0
        let zone = metric.colorZone(progress: rawProgress)
        switch zone {
        case .green: return nil
        case .orange: return .orange
        case .red: return .red
        default: return nil
        }
    }

    var body: some View {
        StatRow(label: metric.label, value: formattedValue, valueColor: valueColor)
    }
}
