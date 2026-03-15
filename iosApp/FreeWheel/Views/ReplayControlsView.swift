import SwiftUI

struct ReplayControlsView: View {
    @EnvironmentObject var wheelManager: WheelManager

    var body: some View {
        VStack(spacing: 8) {
            // Timeline slider + time labels
            HStack {
                Text(formatTimeMs(wheelManager.replayCurrentTimeMs))
                    .font(.caption2)
                    .monospacedDigit()

                Slider(
                    value: Binding(
                        get: { wheelManager.replayProgress },
                        set: { wheelManager.seekReplay(progress: $0) }
                    ),
                    in: 0...1
                )

                Text(formatTimeMs(wheelManager.replayTotalDurationMs))
                    .font(.caption2)
                    .monospacedDigit()
            }

            // Controls: play/pause, stop, speed picker
            HStack {
                // Play/Pause
                Button(action: {
                    if wheelManager.replayStateName == "PLAYING" {
                        wheelManager.pauseReplay()
                    } else {
                        wheelManager.resumeReplay()
                    }
                }) {
                    Image(systemName: wheelManager.replayStateName == "PLAYING" ? "pause.fill" : "play.fill")
                        .font(.title2)
                }

                // Stop
                Button(action: { wheelManager.stopReplay() }) {
                    Image(systemName: "stop.fill")
                        .font(.title2)
                }
                .padding(.leading, 4)

                Spacer()

                // Speed picker
                Picker("Speed", selection: Binding(
                    get: { speedToIndex(wheelManager.replaySpeed) },
                    set: { wheelManager.setReplaySpeed(indexToSpeed($0)) }
                )) {
                    Text("0.5x").tag(0)
                    Text("1x").tag(1)
                    Text("2x").tag(2)
                    Text("4x").tag(3)
                }
                .pickerStyle(.segmented)
                .frame(width: 200)
            }

            // Packet progress
            Text("Packet \(wheelManager.replayPacketIndex) / \(wheelManager.replayTotalPackets)")
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    private func formatTimeMs(_ ms: Int64) -> String {
        let totalSeconds = ms / 1000
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }

    private func speedToIndex(_ speed: Float) -> Int {
        switch speed {
        case ...0.75: return 0
        case 0.76...1.5: return 1
        case 1.51...3.0: return 2
        default: return 3
        }
    }

    private func indexToSpeed(_ index: Int) -> Float {
        switch index {
        case 0: return 0.5
        case 1: return 1.0
        case 2: return 2.0
        case 3: return 4.0
        default: return 1.0
        }
    }
}
