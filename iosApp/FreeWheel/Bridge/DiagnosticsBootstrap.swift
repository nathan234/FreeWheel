import Foundation
import FreeWheelCore

/// One-shot setup for the FreeWheelCore Diagnostics facade. Called from
/// FreeWheelApp.init so events emitted during app launch are persisted.
enum DiagnosticsBootstrap {

    private static let dirName = "diagnostics"

    static func initialize() {
        let dir = libraryLogsURL().appendingPathComponent(dirName, isDirectory: true)
        try? FileManager.default.createDirectory(
            at: dir, withIntermediateDirectories: true
        )
        let store = DiagnosticLogStore()
        store.configure(dirPath: dir.path, maxBytes: 5 * 1024 * 1024)
        Diagnostics.shared.doInit(store: store)
    }

    /// `~/Library/Logs/` is the idiomatic iOS location for log files —
    /// included in sysdiagnose, hidden from user file pickers.
    static func libraryLogsURL() -> URL {
        FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Logs", isDirectory: true)
    }

    static func diagnosticsDirURL() -> URL {
        libraryLogsURL().appendingPathComponent(dirName, isDirectory: true)
    }
}
