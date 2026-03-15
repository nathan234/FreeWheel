import SwiftUI
import UIKit

@main
struct FreeWheelApp: App {
    @StateObject private var wheelManager = WheelManager()
    @StateObject private var chargerManager = ChargerManager()
    @Environment(\.scenePhase) var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(wheelManager)
                .environmentObject(chargerManager)
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.willTerminateNotification)) { _ in
                    if wheelManager.isLogging {
                        wheelManager.stopLogging()
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
