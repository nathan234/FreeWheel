import SwiftUI

struct ContentView: View {
    @EnvironmentObject var wheelManager: WheelManager

    var body: some View {
        TabView {
            NavigationStack {
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
            }
            .tabItem {
                Label("Devices", systemImage: "antenna.radiowaves.left.and.right")
            }

            // Rides tab (Feature 3)
            NavigationStack {
                RidesView()
            }
            .tabItem {
                Label("Rides", systemImage: "road.lanes")
            }

            NavigationStack {
                SettingsView()
            }
            .tabItem {
                Label("Settings", systemImage: "gearshape")
            }
        }
    }
}

#Preview {
    ContentView()
        .environmentObject(WheelManager())
}
