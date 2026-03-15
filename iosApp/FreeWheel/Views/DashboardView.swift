import SwiftUI
import FreeWheelCore

// CROSS-PLATFORM SYNC: This view mirrors freewheel/.../compose/screens/DashboardScreen.kt.
// Layout is now driven by DashboardLayout (configurable per-wheel).
// Fixed sections remain unchanged: alarm banner, speed picker, controls, record/chart/BMS, disconnect.

struct DashboardView: View {
    @EnvironmentObject var wheelManager: WheelManager
    @State private var showChart = false
    @State private var showBms = false
    @State private var selectedMetric: String?
    @State private var showEditDashboard = false

    var body: some View {
        VStack(spacing: 0) {
            DashboardContentView(
                layout: wheelManager.dashboardLayout,
                selectedMetric: $selectedMetric,
                showChart: $showChart,
                showBms: $showBms,
                showEditDashboard: $showEditDashboard
            )

            if wheelManager.isReplayMode {
                ReplayControlsView()
            }
        }
        .navigationTitle(wheelManager.wheelState.displayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                if wheelManager.connectionState.isConnected && !wheelManager.isMockMode && !wheelManager.isTestMode && !wheelManager.isReplayMode {
                    Button(action: { wheelManager.wheelBeep() }) {
                        Image(systemName: "speaker.wave.2.fill")
                    }
                    Button(action: { wheelManager.toggleLight() }) {
                        Image(systemName: wheelManager.isLightOn ? "lightbulb.fill" : "lightbulb")
                            .foregroundColor(wheelManager.isLightOn ? .yellow : nil)
                    }
                }
                if wheelManager.connectionState.isConnected {
                    Button(action: {
                        if wheelManager.isLogging {
                            wheelManager.stopLogging()
                        } else {
                            wheelManager.startLogging()
                        }
                    }) {
                        Image(systemName: wheelManager.isLogging ? "stop.circle.fill" : "record.circle")
                            .foregroundColor(wheelManager.isLogging ? .red : nil)
                    }
                }
                Button(action: { showEditDashboard = true }) {
                    Image(systemName: "square.and.pencil")
                }
            }
        }
        .navigationDestination(isPresented: $showChart) {
            TelemetryChartView()
        }
        .navigationDestination(isPresented: $showBms) {
            SmartBmsView()
        }
        .navigationDestination(isPresented: showMetricBinding) {
            MetricDetailView(metricId: selectedMetric ?? "speed")
        }
        .sheet(isPresented: $showEditDashboard) {
            NavigationStack {
                DashboardEditView()
            }
        }
    }

    private var showMetricBinding: Binding<Bool> {
        Binding(
            get: { selectedMetric != nil },
            set: { if !$0 { selectedMetric = nil } }
        )
    }
}

// MARK: - Alarm Banner

struct AlarmBannerView: View {
    let activeAlarms: Set<AlarmType>

    @State private var isPulsing = false

    private var alarmText: String {
        let types = activeAlarms.sorted { $0.value < $1.value }
        return types.map { $0.displayName }.joined(separator: ", ")
    }

    var body: some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill")
            Text("\(CommonLabels.shared.ALARM_PREFIX)\(alarmText)")
                .fontWeight(.bold)
        }
        .font(.subheadline)
        .foregroundColor(.white)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(
            isPulsing ? Color.red : Color.orange
        )
        .cornerRadius(8)
        .padding(.horizontal)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.5).repeatForever(autoreverses: true)) {
                isPulsing = true
            }
        }
    }
}

struct StatCard: View {
    let title: String
    let value: String
    let icon: String
    let color: Color

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.title)
                .foregroundColor(color)

            Text(value)
                .font(.title2)
                .fontWeight(.bold)

            Text(title)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .cornerRadius(12)
    }
}

struct StatRow: View {
    let label: String
    let value: String
    var valueColor: Color? = nil

    var body: some View {
        HStack {
            Text(label)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .fontWeight(.medium)
                .foregroundColor(valueColor ?? .primary)
        }
    }
}

#Preview {
    NavigationStack {
        DashboardView()
            .environmentObject(WheelManager())
    }
}
