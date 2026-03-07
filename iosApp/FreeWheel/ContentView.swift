import SwiftUI
import FreeWheelCore

struct ContentView: View {
    @EnvironmentObject var wheelManager: WheelManager

    private func sfSymbol(for tab: NavigationTab) -> String {
        switch tab.iconName {
        case "bluetooth": return "antenna.radiowaves.left.and.right"
        case "show_chart": return "chart.xyaxis.line"
        case "battery_full": return "battery.100"
        case "route": return "road.lanes"
        case "tune": return "slider.horizontal.3"
        case "settings": return "gearshape"
        default: return "questionmark"
        }
    }

    var body: some View {
        TabView {
            ForEach(Array(wheelManager.navigationConfig.tabs), id: \.name) { tab in
                NavigationStack {
                    tabContent(for: tab)
                }
                .tabItem {
                    Label(tab.label, systemImage: sfSymbol(for: tab))
                }
            }
        }
    }

    @ViewBuilder
    private func tabContent(for tab: NavigationTab) -> some View {
        switch tab {
        case .devices:
            ZStack {
                if wheelManager.connectionState.isConnected {
                    DashboardView()
                } else {
                    ScanView()
                }
                VStack {
                    ConnectionBanner()
                    Spacer()
                }
            }
        case .chart:
            TelemetryChartView()
        case .bms:
            SmartBmsView()
        case .rides:
            RidesView()
        case .wheelSettings:
            WheelSettingsView()
        case .settings:
            SettingsView()
        default:
            Text("Unknown tab")
        }
    }
}

#Preview {
    ContentView()
        .environmentObject(WheelManager())
}
