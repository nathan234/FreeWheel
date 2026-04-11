import Foundation
import FreeWheelCore

/// SwiftUI-facing wrapper around the shared `ReplayPlaybackReducer` in KMP core.
/// All state transitions are delegated to the reducer — this class only owns the
/// `@Published` state container and the Task that drives the playback clock.
///
/// Mirrors `CsvReplayController` on Android line-for-line: both consume the same
/// reducer and advanceOne contract, so edge behaviour (play-from-finished, skip
/// clamping, speed floor) cannot drift.
class RideReplayController: ObservableObject {
    let samples: [TelemetrySample]

    @Published private var state = ReplayPlaybackState.Companion.shared.initial()
    private var task: Task<Void, Never>?

    var isPlaying: Bool { state.isPlaying }
    var currentIndex: Int32 { state.currentIndex }
    var speedMultiplier: Float { state.speedMultiplier }
    var isFinished: Bool { state.isFinished }

    var currentSample: TelemetrySample? {
        ReplayPlaybackReducer.shared.currentSample(state: state, samples: samples)
    }

    var progress: Float {
        ReplayPlaybackReducer.shared.progress(state: state, samples: samples)
    }

    var totalDurationMs: Int64 {
        ReplayPlaybackReducer.shared.totalDurationMs(samples: samples)
    }

    var elapsedMs: Int64 {
        ReplayPlaybackReducer.shared.elapsedMs(state: state, samples: samples)
    }

    init(samples: [TelemetrySample]) {
        self.samples = samples
    }

    func play() {
        guard !state.isPlaying else { return }
        state = ReplayPlaybackReducer.shared.play(state: state)
        task = Task { @MainActor [weak self] in
            guard let self else { return }
            while !Task.isCancelled && self.state.isPlaying {
                let tick = ReplayPlaybackReducer.shared.advanceOne(state: self.state, samples: self.samples)
                try? await Task.sleep(nanoseconds: UInt64(tick.delayMs) * 1_000_000)
                guard !Task.isCancelled else { return }
                self.state = tick.state
            }
        }
    }

    func pause() {
        task?.cancel()
        task = nil
        state = ReplayPlaybackReducer.shared.pause(state: state)
    }

    func togglePlayPause() {
        if state.isPlaying { pause() } else { play() }
    }

    func seekTo(_ progress: Float) {
        if state.isPlaying { pause() }
        state = ReplayPlaybackReducer.shared.seekTo(state: state, progress: progress, samples: samples)
    }

    func skipForward() {
        let wasPlaying = state.isPlaying
        if wasPlaying { pause() }
        state = ReplayPlaybackReducer.shared.skipForward(state: state, samples: samples)
        if wasPlaying { play() }
    }

    func skipBackward() {
        let wasPlaying = state.isPlaying
        if wasPlaying { pause() }
        state = ReplayPlaybackReducer.shared.skipBackward(state: state, samples: samples)
        if wasPlaying { play() }
    }

    func setSpeed(_ multiplier: Float) {
        let wasPlaying = state.isPlaying
        if wasPlaying { pause() }
        state = ReplayPlaybackReducer.shared.setSpeed(state: state, multiplier: multiplier)
        if wasPlaying { play() }
    }

    func stop() {
        pause()
        state = ReplayPlaybackReducer.shared.stop(state: state)
    }
}
