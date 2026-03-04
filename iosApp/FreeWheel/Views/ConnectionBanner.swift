import SwiftUI
import FreeWheelCore

struct ConnectionBanner: View {
    @EnvironmentObject var wheelManager: WheelManager

    var body: some View {
        if shouldShowBanner {
            HStack(spacing: 8) {
                statusIcon
                Text(bannerText)
                    .font(.subheadline)
                    .fontWeight(.medium)
                Spacer()

                // Cancel button for reconnection (Feature 2)
                if isReconnecting {
                    Button(CommonLabels.shared.CANCEL) {
                        wheelManager.stopReconnecting()
                    }
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .foregroundColor(.white)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 10)
            .background(bannerColor)
            .foregroundColor(.white)
            .transition(.move(edge: .top).combined(with: .opacity))
            .animation(.spring(response: 0.3), value: shouldShowBanner)
        }
    }

    private var shouldShowBanner: Bool {
        switch wheelManager.connectionState {
        case .disconnected:
            return isReconnecting
        case .scanning:
            return false
        default:
            return true
        }
    }

    private var isReconnecting: Bool {
        wheelManager.reconnectState != .idle
    }

    private var bannerText: String {
        // Feature 2: Show reconnect info when reconnecting
        switch wheelManager.reconnectState {
        case .waiting(let attempt, let nextRetryMs):
            let seconds = max(0, Int(nextRetryMs / 1000))
            return "Reconnecting in \(seconds)s... (attempt \(attempt))"
        case .attempting(let attempt):
            return "Reconnecting... (attempt \(attempt))"
        case .idle:
            return wheelManager.connectionState.statusText
        }
    }

    @ViewBuilder
    private var statusIcon: some View {
        if isReconnecting {
            ProgressView()
                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                .scaleEffect(0.8)
        } else {
            switch wheelManager.connectionState {
            case .connecting, .discoveringServices:
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    .scaleEffect(0.8)
            case .connected:
                Image(systemName: "checkmark.circle.fill")
            case .connectionLost:
                Image(systemName: "exclamationmark.triangle.fill")
            case .failed:
                Image(systemName: "xmark.circle.fill")
            default:
                EmptyView()
            }
        }
    }

    private var bannerColor: Color {
        if isReconnecting {
            return .orange
        }
        switch wheelManager.connectionState {
        case .connecting, .discoveringServices:
            return .blue
        case .connected:
            return .green
        case .connectionLost:
            return .orange
        case .failed:
            return .red
        default:
            return .gray
        }
    }
}

#Preview {
    VStack {
        ConnectionBanner()
            .environmentObject(WheelManager())
        Spacer()
    }
}
