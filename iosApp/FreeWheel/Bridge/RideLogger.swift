import Foundation
import CoreLocation
import FreeWheelCore

enum LoggingState {
    case idle
    case logging(startDate: Date, filePath: URL)
}

@MainActor
class RideLogger: ObservableObject {

    @Published private(set) var state: LoggingState = .idle

    var isLogging: Bool {
        if case .logging = state { return true }
        return false
    }

    private let kmpLogger = FreeWheelCore.RideLogger(fileWriter: FileWriter())

    nonisolated init() {}

    // MARK: - Start/Stop

    func startLogging(includeGPS: Bool) -> Bool {
        let ridesDir = Self.ridesDirectory()

        // Create rides directory if needed
        do {
            try FileManager.default.createDirectory(at: ridesDir, withIntermediateDirectories: true)
        } catch {
            print("Failed to create rides directory: \(error)")
            return false
        }

        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy_MM_dd_HH_mm_ss"
        let fileName = "\(formatter.string(from: Date())).csv"
        let filePath = ridesDir.appendingPathComponent(fileName)

        let now = Date()
        let currentTimeMs = Int64(now.timeIntervalSince1970 * 1000)

        guard kmpLogger.start(filePath: filePath.path, withGps: includeGPS, currentTimeMs: currentTimeMs) else {
            return false
        }

        state = .logging(startDate: now, filePath: filePath)
        return true
    }

    func stopLogging(currentDistance: Double) -> RideMetadata? {
        guard case .logging(let startDate, let filePath) = state else { return nil }

        let currentTimeMs = Int64(Date().timeIntervalSince1970 * 1000)
        let lastTotalDistanceM = Int64(currentDistance * 1000.0)

        let kmpMeta = kmpLogger.stop(currentTimeMs: currentTimeMs, lastTotalDistance: lastTotalDistanceM)
        state = .idle

        guard let meta = kmpMeta else { return nil }

        // Get file size
        let fileSize: Int64
        if let attrs = try? FileManager.default.attributesOfItem(atPath: filePath.path),
           let size = attrs[.size] as? Int64 {
            fileSize = size
        } else {
            fileSize = 0
        }

        let endDate = Date(timeIntervalSince1970: Double(meta.endTimeMillis) / 1000.0)

        return RideMetadata(
            id: UUID().uuidString,
            fileName: filePath.lastPathComponent,
            startDate: startDate,
            endDate: endDate,
            duration: endDate.timeIntervalSince(startDate),
            distance: Double(meta.distanceMeters) / 1000.0,
            maxSpeed: meta.maxSpeedKmh,
            avgSpeed: meta.avgSpeedKmh,
            sampleCount: Int(meta.sampleCount),
            fileSize: fileSize,
            maxCurrent: meta.maxCurrentA,
            maxPower: meta.maxPowerW,
            maxPwm: meta.maxPwmPercent,
            consumptionWh: meta.consumptionWh,
            consumptionWhPerKm: meta.consumptionWhPerKm
        )
    }

    // MARK: - Live Stats

    func liveStats(currentDistance: Double) -> FreeWheelCore.LiveRideStats? {
        let currentTimeMs = Int64(Date().timeIntervalSince1970 * 1000)
        let totalDistanceM = Int64(currentDistance * 1000.0)
        return kmpLogger.liveStats(currentTimeMs: currentTimeMs, currentTotalDistance: totalDistanceM)
    }

    // MARK: - Pause/Resume

    /// Mark the ride as paused (e.g. on BLE disconnect).
    func pause() {
        let currentTimeMs = Int64(Date().timeIntervalSince1970 * 1000)
        kmpLogger.pause(currentTimeMs: currentTimeMs)
    }

    /// Resume a paused ride (e.g. on BLE reconnect).
    /// Subtracts the paused gap from elapsed time and resets the write throttle.
    func resume() {
        let currentTimeMs = Int64(Date().timeIntervalSince1970 * 1000)
        kmpLogger.resume(currentTimeMs: currentTimeMs)
    }

    // MARK: - Write Sample

    func writeTelemetrySample(telemetry: TelemetryState, modeStr: String, location: CLLocation?, includeGPS: Bool) {
        let currentTimeMs = Int64(Date().timeIntervalSince1970 * 1000)

        let gps: GpsLocation?
        if includeGPS, let loc = location {
            gps = GpsLocation(
                latitude: loc.coordinate.latitude,
                longitude: loc.coordinate.longitude,
                speedKmh: loc.speed >= 0 ? ByteUtils.shared.metersPerSecondToKmh(speedMs: loc.speed) : 0,
                altitude: loc.altitude,
                bearing: loc.course >= 0 ? loc.course : 0,
                cumulativeDistance: 0
            )
        } else {
            gps = nil
        }

        kmpLogger.writeSample(telemetry: telemetry, modeStr: modeStr, gps: gps, currentTimeMs: currentTimeMs)
    }

    // MARK: - Directory

    static func ridesDirectory() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("rides")
    }
}
