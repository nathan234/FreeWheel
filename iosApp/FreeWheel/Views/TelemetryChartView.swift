import SwiftUI
import Charts
import FreeWheelCore

// CROSS-PLATFORM SYNC: This view mirrors freewheel/.../compose/screens/ChartScreen.kt.
// When adding, removing, or reordering sections, update the counterpart.
//
// Shared sections (in order):
//  1. Time range picker: 5 min / 1 hr / 24 hr
//  2. Metric toggle chips: Speed, GPS, Current, Power, Temperature
//  3. Main telemetry chart (multi-series, overlaid)
//  4. Voltage chart (single series, below main chart)
//  5. Empty state when no samples
//  Note: iOS uses Swift Charts with gesture annotation overlay; Android uses Vico charts

struct TelemetryChartView: View {
    @EnvironmentObject var wheelManager: WheelManager

    @State private var showSpeed = true
    @State private var showGpsSpeed = false
    @State private var showCurrent = true
    @State private var showPower = false
    @State private var showTemperature = false
    @State private var selectedSample: TelemetrySample?
    @State private var selectedDate: Date?
    @State private var mainChartDomain: TimeInterval = 0
    @State private var mainChartBaseDomain: TimeInterval = 0
    @State private var voltageChartDomain: TimeInterval = 0
    @State private var voltageChartBaseDomain: TimeInterval = 0

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

    private func displaySpeed(_ kmh: Double) -> Double {
        displaySpeed(kmh, useMph: wheelManager.useMph)
    }

    private var speedUnit: String {
        DisplayUtils.shared.speedUnit(useMph: wheelManager.useMph)
    }

    private func displayTemp(_ celsius: Double) -> Double {
        displayTemp(celsius, useFahrenheit: wheelManager.useFahrenheit)
    }

    private var tempUnit: String {
        DisplayUtils.shared.temperatureUnit(useFahrenheit: wheelManager.useFahrenheit)
    }

    private var mainChartYAxisUnit: String {
        var units: [String] = []
        if showSpeed || showGpsSpeed { units.append(speedUnit) }
        if showCurrent { units.append("A") }
        if showPower { units.append("W") }
        if showTemperature { units.append(tempUnit) }
        return units.joined(separator: " · ")
    }

    var body: some View {
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

                // Toggle chips
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ToggleChip(label: MetricType.speed.label, color: .blue, isOn: $showSpeed)
                        ToggleChip(label: ChartLabels.shared.GPS, color: .cyan, isOn: $showGpsSpeed)
                        ToggleChip(label: ChartLabels.shared.CURRENT, color: .orange, isOn: $showCurrent)
                        ToggleChip(label: MetricType.power.label, color: .green, isOn: $showPower)
                        ToggleChip(label: MetricType.temperature.label, color: .red, isOn: $showTemperature)
                    }
                    .padding(.horizontal)
                }

                if chartSamples.isEmpty {
                    VStack(spacing: 12) {
                        Spacer()
                        Image(systemName: "chart.xyaxis.line")
                            .font(.system(size: 48))
                            .foregroundColor(.secondary)
                        Text(ChartLabels.shared.WAITING)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        Spacer()
                    }
                    .frame(maxWidth: .infinity, minHeight: 300)
                } else {
                    // Main telemetry chart
                    ZStack(alignment: .topTrailing) {
                        mainChartContent
                        ChartResetButton(visibleDomain: mainChartDomain, samples: chartSamples) {
                            let domain = chartFullDomain(samples: chartSamples)
                            mainChartDomain = domain
                            mainChartBaseDomain = domain
                        }
                        .padding(4)
                    }
                    .overlay(alignment: .topLeading) {
                        if !mainChartYAxisUnit.isEmpty {
                            Text(mainChartYAxisUnit)
                                .font(.caption2)
                                .foregroundColor(.secondary)
                                .padding(.leading, 4)
                                .padding(.top, 2)
                        }
                    }
                    .frame(height: 250)
                    .padding(.horizontal)
                    .onChange(of: chartSamples.count) { _ in resetChartZoom() }

                    // Voltage chart
                    VoltageChartView(samples: chartSamples, axisStride: axisStride, axisStrideCount: axisStrideCount, axisFormat: axisFormat)
                }
            }
            .padding(.vertical)
        }
        .navigationTitle(ChartLabels.shared.TITLE)
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var mainChartContent: some View {
        let chart = Chart {
            if showSpeed {
                ForEach(chartSamples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("Speed", displaySpeed(sample.speed)),
                        series: .value("Series", "Speed")
                    )
                    .foregroundStyle(.blue)
                }
            }
            if showGpsSpeed {
                ForEach(chartSamples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("GPS", displaySpeed(sample.gpsSpeed)),
                        series: .value("Series", "GPS")
                    )
                    .foregroundStyle(.cyan)
                }
            }
            if showCurrent {
                ForEach(chartSamples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("Current", sample.current),
                        series: .value("Series", "Current")
                    )
                    .foregroundStyle(.orange)
                }
            }
            if showPower {
                ForEach(chartSamples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("Power", sample.power),
                        series: .value("Series", "Power")
                    )
                    .foregroundStyle(.green)
                }
            }
            if showTemperature {
                ForEach(chartSamples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("Temp", displayTemp(sample.temperature)),
                        series: .value("Series", "Temp")
                    )
                    .foregroundStyle(.red)
                }
            }

            if let selected = selectedSample {
                RuleMark(x: .value("Time", selected.timestamp))
                    .foregroundStyle(.gray.opacity(0.5))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    .annotation(position: chartAnnotationPosition(for: selected, in: chartSamples), spacing: 8) {
                        ChartAnnotationContent(
                            sample: selected,
                            visibleSeries: mainChartVisibleSeries(for: selected)
                        )
                    }
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

        if #available(iOS 17, *) {
            chart
                .chartXSelection(value: $selectedDate)
                .zoomableChart(samples: chartSamples, visibleDomain: $mainChartDomain, baseDomain: $mainChartBaseDomain)
                .onChange(of: selectedDate) { _, newDate in
                    selectedSample = newDate.flatMap { nearestSample(to: $0, in: chartSamples) }
                }
        } else {
            chart
                .chartOverlay { proxy in
                    GeometryReader { geometry in
                        Rectangle()
                            .fill(Color.clear)
                            .contentShape(Rectangle())
                            .gesture(chartSelectionOverlay(proxy: proxy, geometry: geometry, samples: chartSamples) { selectedSample = $0 })
                    }
                }
        }
    }

    private func resetChartZoom() {
        let domain = chartFullDomain(samples: chartSamples)
        mainChartDomain = domain
        mainChartBaseDomain = domain
        voltageChartDomain = domain
        voltageChartBaseDomain = domain
    }

    private func mainChartVisibleSeries(for selected: TelemetrySample) -> [(label: String, color: Color, value: String)] {
        var series: [(label: String, color: Color, value: String)] = []
        if showSpeed {
            series.append(("Speed", .blue, String(format: "%.1f %@", displaySpeed(selected.speed), speedUnit)))
        }
        if showGpsSpeed {
            series.append(("GPS", .cyan, String(format: "GPS %.1f %@", displaySpeed(selected.gpsSpeed), speedUnit)))
        }
        if showCurrent {
            series.append(("Current", .orange, String(format: "%.1f A", selected.current)))
        }
        if showPower {
            series.append(("Power", .green, String(format: "%.0f W", selected.power)))
        }
        if showTemperature {
            series.append(("Temp", .red, String(format: "%.0f%@", displayTemp(selected.temperature), tempUnit)))
        }
        return series
    }
}

// MARK: - Voltage Chart with press-and-hold selection

struct VoltageChartView: View {
    let samples: [TelemetrySample]
    var axisStride: Calendar.Component = .second
    var axisStrideCount: Int = 10
    var axisFormat: Date.FormatStyle = .dateTime.minute().second()

    @State private var selectedSample: TelemetrySample?
    @State private var selectedDate: Date?
    @State private var visibleDomain: TimeInterval = 0
    @State private var baseDomain: TimeInterval = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(ChartLabels.shared.VOLTAGE)
                .font(.subheadline)
                .fontWeight(.semibold)
                .foregroundColor(.purple)
                .padding(.horizontal)

            ZStack(alignment: .topTrailing) {
                voltageChartContent
                ChartResetButton(visibleDomain: visibleDomain, samples: samples) {
                    let domain = chartFullDomain(samples: samples)
                    visibleDomain = domain
                    baseDomain = domain
                }
                .padding(4)
            }
            .overlay(alignment: .topLeading) {
                Text("V")
                    .font(.caption2)
                    .foregroundColor(.secondary)
                    .padding(.leading, 4)
                    .padding(.top, 2)
            }
            .frame(height: 200)
            .padding(.horizontal)
            .onAppear {
                let domain = chartFullDomain(samples: samples)
                visibleDomain = domain
                baseDomain = domain
            }
        }
    }

    @ViewBuilder
    private var voltageChartContent: some View {
        let chart = Chart {
            ForEach(samples) { sample in
                LineMark(
                    x: .value("Time", sample.timestamp),
                    y: .value("Voltage", sample.voltage)
                )
                .foregroundStyle(.purple)
                .interpolationMethod(.catmullRom)
            }

            if let selected = selectedSample {
                RuleMark(x: .value("Time", selected.timestamp))
                    .foregroundStyle(.gray.opacity(0.5))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    .annotation(position: chartAnnotationPosition(for: selected, in: samples), spacing: 8) {
                        ChartAnnotationContent(
                            sample: selected,
                            visibleSeries: [("Voltage", .purple, String(format: "%.2f V", selected.voltage))]
                        )
                    }

                PointMark(
                    x: .value("Time", selected.timestamp),
                    y: .value("Voltage", selected.voltage)
                )
                .foregroundStyle(.purple)
                .symbolSize(60)
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

        if #available(iOS 17, *) {
            chart
                .chartXSelection(value: $selectedDate)
                .zoomableChart(samples: samples, visibleDomain: $visibleDomain, baseDomain: $baseDomain)
                .onChange(of: selectedDate) { _, newDate in
                    selectedSample = newDate.flatMap { nearestSample(to: $0, in: samples) }
                }
        } else {
            chart
                .chartOverlay { proxy in
                    GeometryReader { geometry in
                        Rectangle()
                            .fill(Color.clear)
                            .contentShape(Rectangle())
                            .gesture(chartSelectionOverlay(proxy: proxy, geometry: geometry, samples: samples) { selectedSample = $0 })
                    }
                }
        }
    }
}

// MARK: - Toggle Chip

struct ToggleChip: View {
    let label: String
    let color: Color
    @Binding var isOn: Bool

    var body: some View {
        Button(action: { isOn.toggle() }) {
            Text(label)
                .font(.caption)
                .fontWeight(.medium)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isOn ? color.opacity(0.2) : Color(.systemGray5))
                .foregroundColor(isOn ? color : .secondary)
                .cornerRadius(16)
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(isOn ? color : Color.clear, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }
}

#Preview {
    NavigationStack {
        TelemetryChartView()
            .environmentObject(WheelManager())
    }
}
