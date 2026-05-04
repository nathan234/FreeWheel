import Foundation

/// Build-time constants pulled from the app bundle. The support email is a
/// placeholder until a real forwarding alias is configured — see the
/// commit history for the rationale.
enum BuildConstants {
    static let versionName: String =
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"

    static let versionCode: String =
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"

    /// Diagnostics destination. Swap to a real forwarding alias before
    /// release. Read at runtime so changing it doesn't require rebuilding
    /// every Swift file that uses it — but it's still embedded in the
    /// binary, so don't put a real personal address here.
    static let supportEmail: String = "support@freewheel.invalid"
}
