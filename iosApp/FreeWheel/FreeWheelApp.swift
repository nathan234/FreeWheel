import SwiftUI
import UIKit
import FreeWheelCore

@main
struct FreeWheelApp: App {
    @StateObject private var wheelManager = WheelManager()
    @StateObject private var chargerManager = ChargerManager()
    @Environment(\.scenePhase) var scenePhase
    @State private var showSplash = true

    init() {
        DiagnosticsBootstrap.initialize()
    }

    var body: some Scene {
        WindowGroup {
            ZStack {
                ContentView()
                    .environmentObject(wheelManager)
                    .environmentObject(chargerManager)
                    .onReceive(NotificationCenter.default.publisher(for: UIApplication.willTerminateNotification)) { _ in
                        if wheelManager.isLogging {
                            wheelManager.stopLogging()
                        }
                    }

                if showSplash {
                    SplashView()
                        .transition(.opacity)
                        .zIndex(1)
                }
            }
            .onAppear {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                    withAnimation(.easeOut(duration: 0.4)) {
                        showSplash = false
                    }
                }
            }
        }
        .onChange(of: scenePhase) { newPhase in
            switch newPhase {
            case .background:
                wheelManager.onEnterBackground()
            case .active:
                wheelManager.onEnterForeground()
            default:
                break
            }
        }
    }
}
