import Foundation
import Combine

/// Observes a KMP StateFlow by polling at a fixed interval.
/// This is a simple MVP approach that avoids complex Flow collection bridges.
class StateFlowObserver<T: Equatable>: ObservableObject {
    @Published private(set) var value: T
    private var timer: Timer?
    private let pollInterval: TimeInterval

    /// Create a new StateFlow observer.
    /// - Parameters:
    ///   - initial: Initial value before first poll
    ///   - pollInterval: How often to poll (default: 0.1s = 10Hz)
    ///   - poll: Closure that returns the current value from the StateFlow
    init(initial: T, pollInterval: TimeInterval = 0.1, poll: @escaping () -> T?) {
        self.value = initial
        self.pollInterval = pollInterval

        timer = Timer.scheduledTimer(withTimeInterval: pollInterval, repeats: true) { [weak self] _ in
            if let newValue = poll(), newValue != self?.value {
                DispatchQueue.main.async {
                    self?.value = newValue
                }
            }
        }
        if let t = timer { RunLoop.current.add(t, forMode: .common) }
    }

    deinit {
        stop()
    }

    /// Stop polling and release the timer.
    func stop() {
        timer?.invalidate()
        timer = nil
    }
}

/// Non-equatable version that always updates on poll.
class StateFlowObserverAny<T>: ObservableObject {
    @Published private(set) var value: T
    private var timer: Timer?
    private let pollInterval: TimeInterval

    init(initial: T, pollInterval: TimeInterval = 0.1, poll: @escaping () -> T?) {
        self.value = initial
        self.pollInterval = pollInterval

        timer = Timer.scheduledTimer(withTimeInterval: pollInterval, repeats: true) { [weak self] _ in
            if let newValue = poll() {
                DispatchQueue.main.async {
                    self?.value = newValue
                }
            }
        }
        if let t = timer { RunLoop.current.add(t, forMode: .common) }
    }

    deinit {
        stop()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }
}
