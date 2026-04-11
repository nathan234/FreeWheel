import Foundation
import FreeWheelCore

/// Module-level typealias so other Swift files can use `TelemetrySample`
/// without importing FreeWheelCore directly.
typealias TelemetrySample = FreeWheelCore.TelemetrySample
typealias MetricType = FreeWheelCore.MetricType
typealias MetricStats = FreeWheelCore.MetricStats
typealias TripStats = FreeWheelCore.TripStats
typealias ChartDataPrep = FreeWheelCore.ChartDataPrep
typealias ReplayPlaybackState = FreeWheelCore.ReplayPlaybackState
typealias ReplayPlaybackReducer = FreeWheelCore.ReplayPlaybackReducer

/// Swift wrapper around the KMP TelemetryBuffer.
/// Exposes samples as a Published array for SwiftUI observation.
@MainActor
class TelemetryBuffer: ObservableObject {

    @Published var samples: [TelemetrySample] = []

    private let kmpBuffer = FreeWheelCore.TelemetryBuffer(sampleIntervalMs: 500, maxAgeMs: 60_000)

    nonisolated init() {}

    func addSampleIfNeeded(sample: FreeWheelCore.TelemetrySample) {
        if kmpBuffer.addSampleIfNeeded(sample: sample) {
            syncSamples()
        }
    }

    func clear() {
        kmpBuffer.clear()
        samples.removeAll()
    }

    /// Access the underlying KMP buffer for stats/values queries.
    var buffer: FreeWheelCore.TelemetryBuffer { kmpBuffer }

    private func syncSamples() {
        samples = kmpBuffer.samples.compactMap { $0 }
    }
}

/// Convenience extension to bridge KMP TelemetrySample for SwiftUI Identifiable conformance.
extension FreeWheelCore.TelemetrySample: @retroactive Identifiable {
    public var id: Int64 { timestampMs }

    // Convenience accessors matching old Swift field names
    var timestamp: Date { Date(timeIntervalSince1970: Double(timestampMs) / 1000.0) }
    var speed: Double { speedKmh }
    var gpsSpeed: Double { gpsSpeedKmh }
    var voltage: Double { voltageV }
    var current: Double { currentA }
    var power: Double { powerW }
    var temperature: Double { temperatureC }
    var battery: Double { batteryPercent }
}
