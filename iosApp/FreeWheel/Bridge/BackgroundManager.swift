import Foundation
import UserNotifications
import UIKit
import FreeWheelCore

@MainActor
class BackgroundManager: ObservableObject {

    @Published var isInBackground: Bool = false

    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid

    nonisolated init() {}

    // MARK: - Notification Permission

    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error = error {
                print("Notification permission error: \(error)")
            }
        }
    }

    // MARK: - Alarm Notification

    func postAlarmNotification(type: AlarmType, value: String) {
        let content = UNMutableNotificationContent()
        content.title = "FreeWheel Alarm"
        content.body = value
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "alarm_\(type.name)_\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil  // Deliver immediately
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Notification error: \(error)")
            }
        }
    }

    // MARK: - Connection Episode Notifications

    /// Stable identifier so lost / restored / given_up replace each other
    /// for the same wheel address instead of stacking in Notification Center.
    private static func episodeIdentifier(address: String) -> String {
        "connection_episode_\(address)"
    }

    /// Post the "Lost" banner for a recovery episode. The structured
    /// `userInfo` payload carries the original disconnect cause; restored
    /// and given_up replacements preserve it via the caller's stashed
    /// NotificationEpisode.
    func postConnectionLostNotification(
        wheelName: String,
        address: String,
        reason: String,
        issue: ConnectionIssueContext
    ) {
        let content = UNMutableNotificationContent()
        content.title = "FreeWheel"
        content.body = "Lost \(wheelName): \(reason)"
        content.sound = .default
        content.userInfo = [
            "kind": "lost",
            "address": address,
            "wheelName": wheelName,
            "issueCode": issue.code.name,
            "isRecoverable": issue.isRecoverable,
        ]

        let request = UNNotificationRequest(
            identifier: Self.episodeIdentifier(address: address),
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Notification error: \(error)")
            }
        }
    }

    /// Replace the prior "Lost" banner with a "Restored" one. Carries the
    /// original disconnect cause forward in `userInfo` so analytics /
    /// tap-handlers see a complete record of the episode.
    func postConnectionRestoredNotification(
        wheelName: String,
        address: String,
        originalIssue: ConnectionIssueContext,
        recoveryDurationMs: Int64
    ) {
        let content = UNMutableNotificationContent()
        content.title = "FreeWheel"
        content.body = "Reconnected to \(wheelName)"
        // Intentionally no sound — recovery is good news.
        content.userInfo = [
            "kind": "restored",
            "address": address,
            "wheelName": wheelName,
            "issueCode": originalIssue.code.name,
            "isRecoverable": originalIssue.isRecoverable,
            "recoveryDurationMs": recoveryDurationMs,
        ]

        let request = UNNotificationRequest(
            identifier: Self.episodeIdentifier(address: address),
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Notification error: \(error)")
            }
        }
    }

    /// Replace the prior "Lost" banner with a terminal "Given up" one when
    /// recovery is abandoned (terminal failure, pause-timeout, or
    /// different-wheel connect). `terminalIssue` is nil when the episode
    /// ended on a non-typed condition (pause-timeout, different-wheel).
    func postRecoveryGivenUpNotification(
        wheelName: String,
        address: String,
        originalIssue: ConnectionIssueContext,
        terminalIssue: ConnectionIssueContext?,
        reason: String
    ) {
        let content = UNMutableNotificationContent()
        content.title = "FreeWheel"
        content.body = "Recovery gave up: \(reason)"
        content.sound = .default
        var info: [AnyHashable: Any] = [
            "kind": "given_up",
            "address": address,
            "wheelName": wheelName,
            "issueCode": originalIssue.code.name,
            "isRecoverable": originalIssue.isRecoverable,
            "terminalReason": reason,
        ]
        if let terminalIssue {
            info["terminalIssueCode"] = terminalIssue.code.name
        }
        content.userInfo = info

        let request = UNNotificationRequest(
            identifier: Self.episodeIdentifier(address: address),
            content: content,
            trigger: nil
        )

        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                print("Notification error: \(error)")
            }
        }
    }

    /// Drop any delivered or pending episode notification for the given
    /// address. Used when the user taps Disconnect mid-recovery — the
    /// "Lost" banner contradicts their intent and shouldn't linger. Also
    /// removes pending requests in case a post-and-remove race could
    /// otherwise strand a banner.
    func removeConnectionEpisodeNotification(address: String) {
        let identifier = Self.episodeIdentifier(address: address)
        UNUserNotificationCenter.current().removeDeliveredNotifications(
            withIdentifiers: [identifier]
        )
        UNUserNotificationCenter.current().removePendingNotificationRequests(
            withIdentifiers: [identifier]
        )
    }

    // MARK: - Background Task

    func beginBackgroundTask() {
        guard backgroundTaskID == .invalid else { return }
        backgroundTaskID = UIApplication.shared.beginBackgroundTask(withName: "FreeWheelBLE") { [weak self] in
            self?.endBackgroundTask()
        }
        isInBackground = true
    }

    func endBackgroundTask() {
        isInBackground = false
        guard backgroundTaskID != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTaskID)
        backgroundTaskID = .invalid
    }
}
