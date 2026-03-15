import SwiftUI
import Charts
import FreeWheelCore

struct TripDetailView: View {
    @EnvironmentObject var wheelManager: WheelManager

    let ride: RideMetadata

    @State private var samples: [TelemetrySample] = []
    @State private var isLoading = true
    @State private var errorMessage: String?

    @State private var showSpeed = true
    @State private var showGpsSpeed = false
    @State private var showCurrent = true
    @State private var showPower = false
    @State private var showTemperature = false
    @State private var showPwm = false
    @State private var selectedSample: TelemetrySample?
    @State private var selectedDate: Date?
    @State private var mainChartDomain: TimeInterval = 0
    @State private var mainChartBaseDomain: TimeInterval = 0

    @State private var replayController: RideReplayController?
    private var isReplaying: Bool { replayController != nil }

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
        if showPwm { units.append("%") }
        return units.joined(separator: " · ")
    }

    var body: some View {
        Group {
            if isLoading {
                ProgressView(RidesLabels.shared.LOADING)
            } else if let error = errorMessage {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "chart.xyaxis.line")
                        .font(.system(size: 48))
                        .foregroundColor(.secondary)
                    Text(error)
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
            } else {
                VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 16) {
                        if let controller = replayController {
                            ReplayStatsView(controller: controller, useMph: wheelManager.useMph, useFahrenheit: wheelManager.useFahrenheit)
                        } else {
                            summaryCard
                        }
                        toggleChips
                        if !samples.isEmpty {
                            ZStack(alignment: .topTrailing) {
                                mainChart
                                ChartResetButton(visibleDomain: mainChartDomain, samples: samples) {
                                    let domain = chartFullDomain(samples: samples)
                                    mainChartDomain = domain
                                    mainChartBaseDomain = domain
                                }
                                .padding(.trailing, 20)
                                .padding(.top, 4)
                            }
                            .overlay(alignment: .topLeading) {
                                if !mainChartYAxisUnit.isEmpty {
                                    Text(mainChartYAxisUnit)
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                        .padding(.leading, 20)
                                        .padding(.top, 4)
                                }
                            }
                            voltageChart
                        }
                    }
                    .padding(.vertical)
                }

                if let controller = replayController {
                    RideReplayControlsView(controller: controller)
                }
                }
            }
        }
        .navigationTitle(isReplaying ? RidesLabels.shared.REPLAY : titleDate)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !isReplaying && !samples.isEmpty {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        replayController = RideReplayController(samples: samples)
                    } label: {
                        Image(systemName: "play.fill")
                    }
                }
            }
            if isReplaying {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        replayController?.stop()
                        replayController = nil
                    } label: {
                        Image(systemName: "xmark")
                    }
                }
            }
        }
        .task {
            await loadCsvData()
        }
    }

    // MARK: - Title

    private var titleDate: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d, h:mm a"
        return formatter.string(from: ride.startDate)
    }

    // MARK: - Summary Card

    private var summaryCard: some View {
        VStack(spacing: 8) {
            HStack {
                summaryItem(label: RidesLabels.shared.DURATION, value: DisplayUtils.shared.formatDurationCompact(seconds: Int32(ride.duration)))
                Spacer()
                summaryItem(label: RidesLabels.shared.DISTANCE, value: DisplayUtils.shared.formatDistance(km: ride.distance, useMph: wheelManager.useMph, decimals: 2))
            }
            HStack {
                summaryItem(label: RidesLabels.shared.MAX_SPEED, value: DisplayUtils.shared.formatSpeed(kmh: ride.maxSpeed, useMph: wheelManager.useMph, decimals: 0))
                Spacer()
                summaryItem(label: RidesLabels.shared.AVG_SPEED, value: DisplayUtils.shared.formatSpeed(kmh: ride.avgSpeed, useMph: wheelManager.useMph, decimals: 0))
            }
            if ride.maxPower > 0 || ride.consumptionWhPerKm > 0 {
                HStack {
                    if ride.maxPower > 0 {
                        summaryItem(label: RidesLabels.shared.MAX_POWER, value: "\(Int(ride.maxPower)) W")
                    }
                    Spacer()
                    if ride.consumptionWhPerKm > 0 {
                        summaryItem(label: RidesLabels.shared.ENERGY, value: DisplayUtils.shared.formatEnergyConsumption(whPerKm: ride.consumptionWhPerKm, useMph: wheelManager.useMph, decimals: 1))
                    }
                }
            }
        }
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .cornerRadius(12)
        .padding(.horizontal)
    }

    private func summaryItem(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
            Text(value)
                .font(.body)
                .fontWeight(.medium)
        }
    }

    // MARK: - Toggle Chips

    private var toggleChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ToggleChip(label: MetricType.speed.label, color: .blue, isOn: $showSpeed)
                ToggleChip(label: ChartLabels.shared.GPS, color: .cyan, isOn: $showGpsSpeed)
                ToggleChip(label: ChartLabels.shared.CURRENT, color: .orange, isOn: $showCurrent)
                ToggleChip(label: MetricType.power.label, color: .green, isOn: $showPower)
                ToggleChip(label: MetricType.temperature.label, color: .red, isOn: $showTemperature)
                ToggleChip(label: MetricType.pwm.label, color: .pink, isOn: $showPwm)
            }
            .padding(.horizontal)
        }
    }

    // MARK: - Main Chart

    @ViewBuilder
    private var mainChart: some View {
        let chart = Chart {
            if showSpeed {
                ForEach(samples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("Speed", displaySpeed(sample.speed)),
                        series: .value("Series", "Speed")
                    )
                    .foregroundStyle(.blue)
                }
            }
            if showGpsSpeed {
                ForEach(samples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("GPS", displaySpeed(sample.gpsSpeed)),
                        series: .value("Series", "GPS")
                    )
                    .foregroundStyle(.cyan)
                }
            }
            if showCurrent {
                ForEach(samples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("Current", sample.current),
                        series: .value("Series", "Current")
                    )
                    .foregroundStyle(.orange)
                }
            }
            if showPower {
                ForEach(samples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("Power", sample.power),
                        series: .value("Series", "Power")
                    )
                    .foregroundStyle(.green)
                }
            }
            if showTemperature {
                ForEach(samples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("Temp", displayTemp(sample.temperature)),
                        series: .value("Series", "Temp")
                    )
                    .foregroundStyle(.red)
                }
            }
            if showPwm {
                ForEach(samples) { sample in
                    LineMark(
                        x: .value("Time", sample.timestamp),
                        y: .value("PWM", sample.pwmPercent),
                        series: .value("Series", "PWM")
                    )
                    .foregroundStyle(.pink)
                }
            }

            if let selected = selectedSample {
                RuleMark(x: .value("Time", selected.timestamp))
                    .foregroundStyle(.gray.opacity(0.5))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    .annotation(position: chartAnnotationPosition(for: selected, in: samples), spacing: 8) {
                        ChartAnnotationContent(
                            sample: selected,
                            visibleSeries: mainChartVisibleSeries(for: selected)
                        )
                    }
            }
        }
        .chartXAxis {
            AxisMarks(values: .stride(by: .minute, count: axisStrideCount)) { _ in
                AxisGridLine()
                AxisValueLabel(format: .dateTime.hour().minute())
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
                .zoomableChart(samples: samples, visibleDomain: $mainChartDomain, baseDomain: $mainChartBaseDomain)
                .onChange(of: selectedDate) { _, newDate in
                    selectedSample = newDate.flatMap { nearestSample(to: $0, in: samples) }
                }
                .frame(height: 250)
                .padding(.horizontal)
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
                .frame(height: 250)
                .padding(.horizontal)
        }
    }

    private var axisStrideCount: Int {
        let durationMinutes = ride.duration / 60
        if durationMinutes < 10 { return 1 }
        if durationMinutes < 60 { return 5 }
        return 15
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
        if showPwm {
            series.append(("PWM", .pink, String(format: "%.1f%%", selected.pwmPercent)))
        }
        return series
    }

    // MARK: - Voltage Chart

    private var voltageChart: some View {
        VoltageChartView(
            samples: samples,
            axisStride: .minute,
            axisStrideCount: axisStrideCount,
            axisFormat: .dateTime.hour().minute()
        )
    }

    // MARK: - Data Loading

    private func loadCsvData() async {
        let fileURL = wheelManager.rideStore.fileURL(for: ride)
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            errorMessage = "CSV file not found"
            isLoading = false
            return
        }

        do {
            let csvContent = try String(contentsOf: fileURL, encoding: .utf8)
            let kmpSamples = CsvParser.shared.parse(csvContent: csvContent)
            let parsed = kmpSamples.compactMap { $0 }
            if parsed.isEmpty {
                errorMessage = "No data in CSV file"
            } else {
                samples = parsed
            }
        } catch {
            errorMessage = "Failed to parse ride: \(error.localizedDescription)"
        }

        isLoading = false
    }

}

// MARK: - Replay Stats Panel

private struct ReplayStatsView: View {
    @ObservedObject var controller: RideReplayController
    let useMph: Bool
    let useFahrenheit: Bool

    var body: some View {
        if let sample = controller.currentSample {
            VStack(spacing: 8) {
                HStack {
                    replayStatItem(
                        label: RidesLabels.shared.SPEED_LABEL,
                        value: DisplayUtils.shared.formatSpeed(kmh: sample.speedKmh, useMph: useMph, decimals: 0)
                    )
                    Spacer()
                    replayStatItem(
                        label: RidesLabels.shared.VOLTAGE_LABEL,
                        value: String(format: "%.1f V", sample.voltageV)
                    )
                    Spacer()
                    replayStatItem(
                        label: RidesLabels.shared.BATTERY_LABEL,
                        value: String(format: "%.0f%%", sample.batteryPercent)
                    )
                }
                HStack {
                    replayStatItem(
                        label: RidesLabels.shared.CURRENT_LABEL,
                        value: String(format: "%.1f A", sample.currentA)
                    )
                    Spacer()
                    replayStatItem(
                        label: RidesLabels.shared.POWER_LABEL,
                        value: String(format: "%.0f W", sample.powerW)
                    )
                    Spacer()
                    replayStatItem(
                        label: RidesLabels.shared.TEMP_LABEL,
                        value: DisplayUtils.shared.formatTemperature(celsius: sample.temperatureC, useFahrenheit: useFahrenheit, decimals: 0)
                    )
                }
            }
            .padding()
            .background(Color(.secondarySystemGroupedBackground))
            .cornerRadius(12)
            .padding(.horizontal)
        }
    }

    private func replayStatItem(label: String, value: String) -> some View {
        VStack(spacing: 2) {
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
            Text(value)
                .font(.title3)
                .fontWeight(.semibold)
        }
    }
}

// MARK: - Replay Controls

private struct RideReplayControlsView: View {
    @ObservedObject var controller: RideReplayController

    var body: some View {
        VStack(spacing: 4) {
            // Scrubber
            HStack {
                Text(DisplayUtils.shared.formatDurationCompact(seconds: Int32(controller.elapsedMs / 1000)))
                    .font(.caption)
                    .monospacedDigit()
                Slider(
                    value: Binding(
                        get: { controller.progress },
                        set: { controller.seekTo($0) }
                    ),
                    in: 0...1
                )
                Text(DisplayUtils.shared.formatDurationCompact(seconds: Int32(controller.totalDurationMs / 1000)))
                    .font(.caption)
                    .monospacedDigit()
            }
            .padding(.horizontal)

            // Controls
            HStack {
                // Skip / play / skip
                HStack(spacing: 16) {
                    Button { controller.skipBackward() } label: {
                        Image(systemName: "gobackward.30")
                            .font(.title3)
                    }
                    Button { controller.togglePlayPause() } label: {
                        Image(systemName: controller.isPlaying ? "pause.fill" : "play.fill")
                            .font(.title2)
                    }
                    Button { controller.skipForward() } label: {
                        Image(systemName: "goforward.30")
                            .font(.title3)
                    }
                }

                Spacer()

                // Speed picker
                HStack(spacing: 4) {
                    ForEach([Float(1), 2, 4, 8], id: \.self) { mult in
                        Button {
                            controller.setSpeed(mult)
                        } label: {
                            Text("\(Int(mult))x")
                                .font(.caption)
                                .fontWeight(controller.speedMultiplier == mult ? .bold : .regular)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(
                                    controller.speedMultiplier == mult
                                    ? Color.accentColor.opacity(0.2)
                                    : Color(.tertiarySystemFill)
                                )
                                .cornerRadius(8)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal)
        }
        .padding(.vertical, 8)
        .background(.bar)
    }

}

