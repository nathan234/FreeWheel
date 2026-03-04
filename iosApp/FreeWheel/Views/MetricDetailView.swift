import SwiftUI
import Charts
import FreeWheelCore

struct MetricDetailView: View {
    @EnvironmentObject var wheelManager: WheelManager
    let metricId: String

    private var chartSamples: [TelemetrySample] {
        if wheelManager.telemetryHistory.timeRange == .fiveMinutes {
            return wheelManager.telemetryBuffer.samples
        } else {
            return wheelManager.telemetryHistory.samples
        }
    }

    private var axisStride: Calendar.Component {
        switch wheelManager.telemetryHistory.timeRange {
        case .fiveMinutes: return .second
        case .oneHour: return .minute
        case .twentyFourHours: return .hour
        default: return .second
        }
    }

    private var axisStrideCount: Int {
        switch wheelManager.telemetryHistory.timeRange {
        case .fiveMinutes: return 10
        case .oneHour: return 5
        case .twentyFourHours: return 2
        default: return 10
        }
    }

    private var axisFormat: Date.FormatStyle {
        if wheelManager.telemetryHistory.timeRange == .fiveMinutes {
            return .dateTime.minute().second()
        } else {
            return .dateTime.hour().minute()
        }
    }

    private var metric: MetricType {
        MetricType.entries.first { $0.name.lowercased() == metricId.lowercased() } ?? .speed
    }

    private var displayUnit: String {
        DisplayUtils.shared.metricUnit(metric: metric, useMph: wheelManager.useMph, useFahrenheit: wheelManager.useFahrenheit)
    }

    private func convertValue(_ raw: Double) -> Double {
        DisplayUtils.shared.convertMetricValue(value: raw, metric: metric, useMph: wheelManager.useMph, useFahrenheit: wheelManager.useFahrenheit)
    }

    private var chartColor: Color {
        metricSwiftColor(metric)
    }

    var body: some View {
        let samples = chartSamples

        ScrollView {
            VStack(spacing: 16) {
                // Time range picker
                Picker("Range", selection: Binding(
                    get: { wheelManager.telemetryHistory.timeRange },
                    set: { wheelManager.telemetryHistory.setTimeRange($0) }
                )) {
                    Text(ChartTimeRange.fiveMinutes.label).tag(ChartTimeRange.fiveMinutes)
                    Text(ChartTimeRange.oneHour.label).tag(ChartTimeRange.oneHour)
                    Text(ChartTimeRange.twentyFourHours.label).tag(ChartTimeRange.twentyFourHours)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)

                // Current value
                let currentRaw = samples.last.map { metric.extractValue(sample: $0) } ?? 0
                let currentValue = convertValue(currentRaw)

                VStack(spacing: 4) {
                    Text(formatValue(currentValue))
                        .font(.system(size: 48, weight: .bold, design: .rounded))
                        .foregroundColor(chartColor)
                    Text(displayUnit)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
                .padding(.top)

                // Chart
                if samples.count >= 2 {
                    Chart {
                        ForEach(samples) { sample in
                            let raw = metric.extractValue(sample: sample)
                            let converted = convertValue(raw)
                            LineMark(
                                x: .value("Time", sample.timestamp),
                                y: .value(metric.label, converted)
                            )
                            .foregroundStyle(chartColor)
                        }
                    }
                    .chartXAxis {
                        AxisMarks(values: .stride(by: axisStride, count: axisStrideCount)) { _ in
                            AxisGridLine()
                            AxisValueLabel(format: axisFormat)
                        }
                    }
                    .chartYAxis {
                        AxisMarks { _ in
                            AxisGridLine()
                            AxisValueLabel()
                        }
                    }
                    .frame(height: 280)
                    .padding(.horizontal)
                }

                // Stats — use buffer for 5m, history for longer ranges
                let stats: MetricStats = {
                    if wheelManager.telemetryHistory.timeRange == .fiveMinutes {
                        return wheelManager.telemetryBuffer.buffer.statsFor(metric: metric)
                    } else {
                        return wheelManager.telemetryHistory.statsForRange(metric: metric)
                    }
                }()
                HStack(spacing: 0) {
                    StatBlock(label: CommonLabels.shared.MIN, value: formatValue(convertValue(stats.min)), unit: displayUnit)
                    Divider().frame(height: 40)
                    StatBlock(label: CommonLabels.shared.AVG, value: formatValue(convertValue(stats.avg)), unit: displayUnit)
                    Divider().frame(height: 40)
                    StatBlock(label: CommonLabels.shared.MAX, value: formatValue(convertValue(stats.max)), unit: displayUnit)
                }
                .padding()
                .background(Color(UIColor.secondarySystemGroupedBackground))
                .cornerRadius(12)
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .background(Color(UIColor.systemGroupedBackground))
        .navigationTitle(metric.label)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func formatValue(_ value: Double) -> String {
        metric.formatValue(value: value)
    }
}

private struct StatBlock: View {
    let label: String
    let value: String
    let unit: String

    var body: some View {
        VStack(spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
            Text(value)
                .font(.headline)
                .fontWeight(.bold)
            Text(unit)
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

func metricSwiftColor(_ metric: MetricType) -> Color {
    switch metric {
    case .speed: return .blue
    case .battery: return .green
    case .power: return .orange
    case .pwm: return .purple
    case .temperature: return .red
    case .gpsSpeed: return .cyan
    default: return .gray
    }
}

#Preview {
    NavigationStack {
        MetricDetailView(metricId: "speed")
            .environmentObject(WheelManager())
    }
}
