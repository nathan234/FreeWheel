import SwiftUI
import FreeWheelCore

/// Stateless dashboard body driven by DashboardLayout.
/// Extracted from DashboardView for testability and preview support.
struct DashboardContentView: View {
    @EnvironmentObject var wheelManager: WheelManager
    let layout: DashboardLayout
    var showControls: Bool = true
    @Binding var selectedMetric: String?
    @Binding var showChart: Bool
    @Binding var showBms: Bool
    @Binding var showEditDashboard: Bool

    private var effectiveLayout: DashboardLayout {
        layout.filteredFor(wheelType: wheelManager.wheelState.wheelType)
    }

    private var displaySpeed: Double {
        DisplayUtils.shared.convertSpeed(kmh: wheelManager.wheelState.speedKmh, useMph: wheelManager.useMph)
    }

    private var speedUnit: String {
        DisplayUtils.shared.speedUnit(useMph: wheelManager.useMph)
    }

    private var maxSpeed: Double {
        DisplayUtils.shared.maxSpeedDefault(useMph: wheelManager.useMph)
    }

    private var gpsKmh: Double {
        let gpsSpeedRaw = wheelManager.locationManager.currentLocation?.speed ?? 0
        return ByteUtils.shared.metersPerSecondToKmh(speedMs: max(0, gpsSpeedRaw))
    }

    private var gpsDisplaySpeed: Double {
        DisplayUtils.shared.convertSpeed(kmh: gpsKmh, useMph: wheelManager.useMph)
    }

    private func sparklineFor(_ metric: DashboardMetric) -> [Double] {
        guard let metricType = metric.sparklineKey else { return [] }
        let values = wheelManager.telemetryBuffer.buffer.valuesFor(metric: metricType)
        let arr = values.compactMap { ($0 as NSNumber).doubleValue }
        return Array(arr.suffix(20))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Alarm banner
                if !wheelManager.activeAlarms.isEmpty {
                    AlarmBannerView(activeAlarms: wheelManager.activeAlarms)
                }

                // Hero metric rendering (nil = tiles-only layout, skip hero gauge)
                if let heroMetric = effectiveLayout.heroMetric {
                    let isSpeedHero = heroMetric == .speed || heroMetric == .gpsSpeed

                    if isSpeedHero {
                        // Speed display mode picker (only on main dashboard)
                        if showControls {
                            Picker("Speed Source", selection: $wheelManager.speedDisplayMode) {
                                Text(DashboardLabels.shared.SPEED_SOURCE_SPEED).tag(SpeedDisplayMode.wheel)
                                Text(DashboardLabels.shared.SPEED_SOURCE_GPS).tag(SpeedDisplayMode.gps)
                                Text(DashboardLabels.shared.SPEED_SOURCE_BOTH).tag(SpeedDisplayMode.both)
                            }
                            .pickerStyle(.segmented)
                            .padding(.horizontal)
                        }

                        // Speed gauge
                        Button(action: { selectedMetric = "speed" }) {
                            SpeedGaugeView(
                                speed: displaySpeed,
                                maxSpeed: maxSpeed,
                                unitLabel: speedUnit,
                                gpsSpeed: gpsDisplaySpeed,
                                mode: wheelManager.speedDisplayMode
                            )
                            .frame(height: 250)
                            .padding(.top)
                        }
                        .buttonStyle(.plain)
                    } else {
                        // Generic hero gauge for non-speed metrics
                        let heroRawValue = heroMetric.extractValue(state: wheelManager.wheelState)?.doubleValue ?? 0.0
                        let heroDisplayValue = DisplayUtils.shared.convertMetricValue(value: heroRawValue, metric: heroMetric, useMph: wheelManager.useMph, useFahrenheit: wheelManager.useFahrenheit)
                        let heroUnit = DisplayUtils.shared.metricUnit(metric: heroMetric, useMph: wheelManager.useMph, useFahrenheit: wheelManager.useFahrenheit)
                        let heroMax = heroMetric.maxValue > 0 ? heroMetric.maxValue : max(abs(heroRawValue), 1.0)

                        Button(action: { selectedMetric = heroMetric.name.lowercased() }) {
                            HeroGaugeView(
                                value: heroDisplayValue,
                                maxValue: heroMax,
                                unitLabel: heroUnit,
                                label: heroMetric.label,
                                metric: heroMetric
                            )
                            .frame(height: 250)
                            .padding(.top)
                        }
                        .buttonStyle(.plain)
                    }
                }

                // Gauge tile grid — driven by layout
                let columns = [GridItem(.flexible()), GridItem(.flexible())]
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(Array(effectiveLayout.tiles), id: \.name) { metric in
                        MetricGaugeTile(
                            metric: metric,
                            wheelState: wheelManager.wheelState,
                            gpsSpeed: gpsKmh,
                            useMph: wheelManager.useMph,
                            useFahrenheit: wheelManager.useFahrenheit,
                            sparklineData: sparklineFor(metric)
                        ) {
                            let metricType = metric.sparklineKey
                            selectedMetric = metricType?.name.lowercased() ?? metric.name.lowercased()
                        }
                    }
                }
                .padding(.horizontal)

                // Stats section — driven by layout
                if !effectiveLayout.stats.isEmpty {
                    VStack(spacing: 12) {
                        ForEach(Array(effectiveLayout.stats), id: \.name) { metric in
                            MetricStatRow(
                                metric: metric,
                                wheelState: wheelManager.wheelState,
                                gpsSpeed: gpsKmh,
                                useMph: wheelManager.useMph,
                                useFahrenheit: wheelManager.useFahrenheit
                            )
                        }
                    }
                    .padding()
                    .background(Color(UIColor.secondarySystemGroupedBackground))
                    .cornerRadius(12)
                    .padding(.horizontal)
                }

                // Range estimate (shown when available)
                if let range = wheelManager.rangeEstimateKm {
                    VStack(spacing: 12) {
                        StatRow(
                            label: DashboardLabels.shared.ESTIMATED_RANGE,
                            value: DisplayUtils.shared.formatDistance(km: range, useMph: wheelManager.useMph, decimals: 1)
                        )
                    }
                    .padding()
                    .background(Color(UIColor.secondarySystemGroupedBackground))
                    .cornerRadius(12)
                    .padding(.horizontal)
                }

                if showControls {
                    // Wheel settings (conditional)
                    if effectiveLayout.showWheelSettings && wheelManager.wheelState.pedalsMode >= 0 {
                        NavigationLink(destination: WheelSettingsView()) {
                            VStack(spacing: 12) {
                                StatRow(label: DashboardLabels.shared.PEDALS_MODE, value: DisplayUtils.shared.pedalsModeText(mode: wheelManager.wheelState.pedalsMode))
                                StatRow(label: DashboardLabels.shared.TILT_BACK_SPEED, value: DisplayUtils.shared.tiltBackSpeedText(speed: wheelManager.wheelState.tiltBackSpeed, useMph: wheelManager.useMph))
                                if wheelManager.wheelState.alertSpeed > 0 {
                                    StatRow(label: DashboardLabels.shared.ALERT_SPEED, value: DisplayUtils.shared.alertSpeedText(speed: wheelManager.wheelState.alertSpeed, useMph: wheelManager.useMph))
                                }
                                if wheelManager.wheelState.autoOffTime > 0 {
                                    StatRow(label: DashboardLabels.shared.AUTO_OFF_TIME, value: DisplayUtils.shared.autoOffTimeText(seconds: wheelManager.wheelState.autoOffTime))
                                }
                                StatRow(label: DashboardLabels.shared.LIGHT, value: DisplayUtils.shared.lightModeText(mode: wheelManager.wheelState.lightMode))
                                StatRow(label: DashboardLabels.shared.LED_MODE, value: "\(wheelManager.wheelState.ledMode)")
                            }
                            .padding()
                            .background(Color(UIColor.secondarySystemGroupedBackground))
                            .cornerRadius(12)
                            .padding(.horizontal)
                        }
                        .buttonStyle(.plain)
                    }

                    // Wheel info (conditional)
                    if effectiveLayout.showWheelInfo && (!wheelManager.wheelState.name.isEmpty || !wheelManager.wheelState.model.isEmpty) {
                        VStack(spacing: 12) {
                            if !wheelManager.wheelState.name.isEmpty {
                                StatRow(label: DashboardLabels.shared.NAME, value: wheelManager.wheelState.name)
                            }
                            if !wheelManager.wheelState.model.isEmpty {
                                StatRow(label: DashboardLabels.shared.MODEL, value: wheelManager.wheelState.model)
                            }
                            StatRow(label: DashboardLabels.shared.TYPE, value: wheelManager.wheelState.wheelType.name)
                            if !wheelManager.wheelState.version.isEmpty {
                                StatRow(label: DashboardLabels.shared.FIRMWARE, value: wheelManager.wheelState.version)
                            }
                        }
                        .padding()
                        .background(Color(UIColor.secondarySystemGroupedBackground))
                        .cornerRadius(12)
                        .padding(.horizontal)
                    }

                    // Demo/Test mode indicator
                    if wheelManager.isMockMode {
                        HStack {
                            Image(systemName: "info.circle.fill")
                            Text(DashboardLabels.shared.DEMO_MODE_BADGE)
                        }
                        .font(.caption)
                        .foregroundColor(.orange)
                        .padding(.vertical, 8)
                        .padding(.horizontal, 16)
                        .background(Color.orange.opacity(0.15))
                        .cornerRadius(8)
                    } else if wheelManager.isTestMode {
                        HStack {
                            Image(systemName: "testtube.2")
                            Text(DashboardLabels.shared.TEST_MODE_BADGE)
                        }
                        .font(.caption)
                        .foregroundColor(.blue)
                        .padding(.vertical, 8)
                        .padding(.horizontal, 16)
                        .background(Color.blue.opacity(0.15))
                        .cornerRadius(8)
                    }

                    // BMS, Chart row
                    HStack(spacing: 12) {
                        Button(action: { showBms = true }) {
                            HStack {
                                Image(systemName: "battery.100")
                                Text(DashboardLabels.shared.BMS)
                            }
                            .fontWeight(.medium)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.gray)
                            .cornerRadius(12)
                        }

                        Button(action: { showChart = true }) {
                            HStack {
                                Image(systemName: "chart.xyaxis.line")
                                Text(DashboardLabels.shared.CHART)
                            }
                            .fontWeight(.medium)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(Color.purple)
                            .cornerRadius(12)
                        }
                    }
                    .padding(.horizontal)

                    // Disconnect button
                    Button(action: {
                        if wheelManager.isMockMode {
                            wheelManager.stopMockMode()
                        } else if wheelManager.isTestMode {
                            wheelManager.stopTestMode()
                        } else {
                            wheelManager.disconnect()
                        }
                    }) {
                        Text(disconnectLabel)
                            .fontWeight(.medium)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(disconnectColor)
                            .cornerRadius(12)
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 20)
                }
            }
        }
        .background(Color(UIColor.systemGroupedBackground))
    }

    private var disconnectLabel: String {
        if wheelManager.isMockMode { return DashboardLabels.shared.STOP_DEMO }
        if wheelManager.isTestMode { return DashboardLabels.shared.STOP_TEST }
        return DashboardLabels.shared.DISCONNECT
    }

    private var disconnectColor: Color {
        if wheelManager.isMockMode { return .orange }
        if wheelManager.isTestMode { return .blue }
        return .red
    }
}

#Preview("Default Layout") {
    NavigationStack {
        DashboardContentView(
            layout: DashboardPresets.shared.default().layout,
            selectedMetric: .constant(nil),
            showChart: .constant(false),
            showBms: .constant(false),
            showEditDashboard: .constant(false)
        )
        .environmentObject(WheelManager())
    }
}

#Preview("Racing Layout") {
    NavigationStack {
        DashboardContentView(
            layout: DashboardPresets.shared.all().first(where: { $0.id == "racing" })!.layout,
            selectedMetric: .constant(nil),
            showChart: .constant(false),
            showBms: .constant(false),
            showEditDashboard: .constant(false)
        )
        .environmentObject(WheelManager())
    }
}

#Preview("Tiles Only Layout") {
    NavigationStack {
        DashboardContentView(
            layout: DashboardPresets.shared.tilesOnly().layout,
            selectedMetric: .constant(nil),
            showChart: .constant(false),
            showBms: .constant(false),
            showEditDashboard: .constant(false)
        )
        .environmentObject(WheelManager())
    }
}

#Preview("Compact Layout") {
    NavigationStack {
        DashboardContentView(
            layout: DashboardPresets.shared.all().first(where: { $0.id == "compact" })!.layout,
            selectedMetric: .constant(nil),
            showChart: .constant(false),
            showBms: .constant(false),
            showEditDashboard: .constant(false)
        )
        .environmentObject(WheelManager())
    }
}
