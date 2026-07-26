import Foundation
import FreeWheelCore
import EucProtocols

/// Experimental shadow-parity harness for the Rust `euc-protocols` crate.
///
/// Receives every RX BLE packet (via the WCM capture callback) and feeds it
/// to the matching Rust decoder — Gotway or Veteran/Leaperkim — in parallel
/// with the KMP decoder. Every `logInterval` packets it writes one snapshot
/// of both telemetry states to Diagnostics (`RUST_SHADOW_MATCH` /
/// `RUST_SHADOW_DIVERGE`), visible in the in-app Diagnostics screen and
/// exported logs.
///
/// Read-only by design: never sends commands and never touches app state.
/// Wheel types without a Rust port are ignored by the caller.
@MainActor
final class RustShadowDecoder {
    private let gotwaySession: GotwaySession
    private let veteranSession: VeteranSession
    private let kmpTelemetry: () -> FreeWheelCore.TelemetryState?
    private var rxPackets = 0

    /// ~every 5-10 s at typical frame rates.
    private static let logInterval = 50

    /// Divergence thresholds absorb one-frame async skew between the two
    /// pipelines (KMP publishes through flows; the shadow decodes inline)
    /// while still catching real decode errors — a wrong voltage scaler is a
    /// >20% jump, not a 1 V flicker.
    private static let voltageToleranceCentivolts: Int32 = 100 // 1 V
    private static let speedToleranceCentiKmh: Int32 = 300 // 3 km/h
    /// The odometer advances ~3-4 m per frame at speed, and the KMP value in
    /// a snapshot can be one frame stale (flow → main-actor hop races the
    /// shadow's inline decode). Verified on the 2026-07-25 Commander Max ride.
    private static let totalDistanceToleranceMeters: Int64 = 10

    init(
        kmpConfig: FreeWheelCore.DecoderConfig,
        kmpTelemetry: @escaping () -> FreeWheelCore.TelemetryState?
    ) {
        self.kmpTelemetry = kmpTelemetry
        let config = EucProtocols.DecoderConfig(
            useCustomPercents: kmpConfig.useCustomPercents,
            rotationSpeed: kmpConfig.rotationSpeed,
            rotationVoltage: kmpConfig.rotationVoltage,
            powerFactor: kmpConfig.powerFactor,
            wheelPassword: kmpConfig.wheelPassword,
            gotwayNegative: kmpConfig.gotwayNegative,
            useRatio: kmpConfig.useRatio,
            gotwayVoltage: kmpConfig.gotwayVoltage,
            hwPwmEnabled: kmpConfig.hwPwmEnabled,
            ks18lScaler: kmpConfig.ks18LScaler,
            autoVoltage: kmpConfig.autoVoltage
        )
        gotwaySession = GotwaySession(config: config)
        veteranSession = VeteranSession(config: config)

        // The Rust crate is sans-io and never reads a clock; supply the
        // wall-clock for Veteran time-sync/password command timestamps.
        let now = Date()
        let parts = Calendar.current.dateComponents(
            [.year, .month, .day, .hour, .minute, .second], from: now
        )
        veteranSession.setWallClock(clock: WallClock(
            year: Int32(parts.year ?? 2000),
            month: Int32(parts.month ?? 1),
            day: Int32(parts.day ?? 1),
            hour: Int32(parts.hour ?? 0),
            minute: Int32(parts.minute ?? 0),
            second: Int32(parts.second ?? 0),
            tzOffsetHours: Int32(TimeZone.current.secondsFromGMT() / 3600)
        ))
    }

    /// Feed one received BLE packet to the Rust decoder matching the wheel
    /// type. Unported wheel types are the caller's responsibility to filter.
    func feedRx(_ bytes: KotlinByteArray, wheelType: FreeWheelCore.WheelType) {
        var data = Data(count: Int(bytes.size))
        for i in 0..<Int(bytes.size) {
            data[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
        }

        let rust: EucProtocols.TelemetryState
        if wheelType == .gotway {
            _ = gotwaySession.decode(data: data)
            rust = gotwaySession.currentState().telemetry
        } else if wheelType == .veteran {
            _ = veteranSession.decode(data: data)
            rust = veteranSession.currentState().telemetry
        } else {
            return
        }

        rxPackets += 1
        if rxPackets % Self.logInterval == 0 {
            logSnapshot(rust: rust)
        }
    }

    private func logSnapshot(rust: EucProtocols.TelemetryState) {
        guard let kmp = kmpTelemetry() else { return }
        let diverges =
            kmp.batteryLevel != rust.batteryLevel ||
            abs(Int64(kmp.totalDistance) - rust.totalDistance) > Self.totalDistanceToleranceMeters ||
            abs(kmp.voltage - rust.voltage) > Self.voltageToleranceCentivolts ||
            abs(kmp.speed - rust.speed) > Self.speedToleranceCentiKmh
        let message =
            "kmp[v=\(kmp.voltage) s=\(kmp.speed) b=\(kmp.batteryLevel) t=\(kmp.temperature) c=\(kmp.current) td=\(kmp.totalDistance)] " +
            "rust[v=\(rust.voltage) s=\(rust.speed) b=\(rust.batteryLevel) t=\(rust.temperature) c=\(rust.current) td=\(rust.totalDistance)] " +
            "rx=\(rxPackets)"
        Diagnostics.shared.log(event: DiagnosticEvent(
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            level: diverges ? .warn : .info,
            category: .system,
            type: diverges ? "RUST_SHADOW_DIVERGE" : "RUST_SHADOW_MATCH",
            sessionId: nil,
            message: message,
            context: [:]
        ))
    }
}
