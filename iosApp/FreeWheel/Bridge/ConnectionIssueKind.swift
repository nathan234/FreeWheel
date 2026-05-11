import Foundation

/// Swift mirror of the KMP `ConnectionIssueCode` enum
/// (core/src/commonMain/.../service/ConnectionIssue.kt).
///
/// The source of truth is in KMP. When a new code is added there, the raw
/// string flows through `ConnectionIssueContext.code` and lands here as
/// `.unknown` until the corresponding case is added to this file. There is
/// no compile-time check for drift — at the moment we don't have a Swift
/// test target — so the safety net is the runtime fallback plus the
/// notification's `userInfo["issueCode"]` (which always carries the raw
/// KMP string, even when this enum can't resolve it).
enum ConnectionIssueKind: String, CaseIterable {
    case unknown                            = "UNKNOWN"
    case bluetoothUnavailable               = "BLUETOOTH_UNAVAILABLE"
    case bluetoothPoweredOff                = "BLUETOOTH_POWERED_OFF"
    case bluetoothStateChanged              = "BLUETOOTH_STATE_CHANGED"
    case peripheralNotFound                 = "PERIPHERAL_NOT_FOUND"
    case connectionTimedOut                 = "CONNECTION_TIMED_OUT"
    case connectionFailed                   = "CONNECTION_FAILED"
    case peripheralDisconnected             = "PERIPHERAL_DISCONNECTED"
    case invalidHandle                      = "INVALID_HANDLE"
    case peerRemovedPairing                 = "PEER_REMOVED_PAIRING"
    case encryptionTimedOut                 = "ENCRYPTION_TIMED_OUT"
    case previousConnectionStillDraining    = "PREVIOUS_CONNECTION_STILL_DRAINING"
    case serviceDiscoveryTimedOut           = "SERVICE_DISCOVERY_TIMED_OUT"
    case serviceDiscoveryFailed             = "SERVICE_DISCOVERY_FAILED"
    case noSupportedServices                = "NO_SUPPORTED_SERVICES"
    case requiredCharacteristicMissing      = "REQUIRED_CHARACTERISTIC_MISSING"

    init(rawCode: String) {
        self = ConnectionIssueKind(rawValue: rawCode) ?? .unknown
    }
}

extension ConnectionIssueContext {
    /// Typed view of the underlying KMP `ConnectionIssueCode`. Falls back to
    /// `.unknown` when KMP introduces a code that this Swift mirror hasn't
    /// adopted yet — the raw string remains available via `code`.
    var kind: ConnectionIssueKind {
        ConnectionIssueKind(rawCode: code)
    }
}
