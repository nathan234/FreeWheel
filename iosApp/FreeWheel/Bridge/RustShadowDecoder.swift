import Foundation
import FreeWheelCore
import EucProtocols

/// Experimental shadow-parity harness for the Rust `euc-protocols` crate.
///
/// Receives every RX BLE packet (via the WCM capture callback) and feeds it
/// to the Rust Gotway decoder in parallel with the KMP decoder. Every
/// `logInterval` packets it writes one snapshot of both telemetry states to
/// Diagnostics (`RUST_SHADOW_MATCH` / `RUST_SHADOW_DIVERGE`), visible in the
/// in-app Diagnostics screen and exported logs.
///
/// Read-only by design: never sends commands and never touches app state.
/// Non-Gotway packets buffer harmlessly inside the Rust decoder.
@MainActor
final class RustShadowDecoder {
    private let session: GotwaySession
    private let kmpTelemetry: () -> FreeWheelCore.TelemetryState?
    private var rxPackets = 0

    /// ~every 5-10 s at typical Gotway frame rates.
    private static let logInterval = 50

    /// Divergence thresholds absorb one-frame async skew between the two
    /// pipelines (KMP publishes through flows; the shadow decodes inline)
    /// while still catching real decode errors — a wrong voltage scaler is a
    /// >20% jump, not a 1 V flicker.
    private static let voltageToleranceCentivolts: Int32 = 100 // 1 V
    private static let speedToleranceCentiKmh: Int32 = 300 // 3 km/h
    /// The odometer advances ~3-4 m per 0x04 frame at speed, and the KMP
    /// value in a snapshot can be one frame stale (flow → main-actor hop
    /// races the shadow's inline decode). Verified on the 2026-07-25 ride:
    /// all 19 flagged divergences were 1-4 m, speed-correlated, absent at
    /// standstill — measurement skew, not decode divergence.
    private static let totalDistanceToleranceMeters: Int64 = 10

    init(
        kmpConfig: FreeWheelCore.DecoderConfig,
        kmpTelemetry: @escaping () -> FreeWheelCore.TelemetryState?
    ) {
        self.kmpTelemetry = kmpTelemetry
        session = GotwaySession(config: EucProtocols.DecoderConfig(
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
        ))
    }

    func feedRx(_ bytes: KotlinByteArray) {
        var data = Data(count: Int(bytes.size))
        for i in 0..<Int(bytes.size) {
            data[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
        }
        _ = session.decode(data: data)
        rxPackets += 1
        if rxPackets % Self.logInterval == 0 {
            logSnapshot()
        }
    }

    private func logSnapshot() {
        guard let kmp = kmpTelemetry() else { return }
        let rust = session.currentState().telemetry
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
