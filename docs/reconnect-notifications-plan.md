# Reconnect / Disconnect Push Notifications — Plan

Status: draft (2026-05-11). Open for Codex review.

## Goals

1. Fire a local push notification when the connection to the wheel is lost,
   showing the **typed error code** alongside the human-readable reason.
2. Fire a follow-up push notification when the recovery succeeds (or
   when recovery is given up on, so the user isn't left with a stale
   "disconnected" notification).
3. Make the error code **trackable** on the Swift side — a Swift enum
   mirror of `ConnectionIssueCode` so callers can `switch` exhaustively
   instead of string-comparing.

Non-goals:

- Remote push (APNs / FCM). All notifications are local
  (`UNUserNotificationCenter`).
- Notification UI design beyond title/body/userInfo.
- Diagnostics/analytics surface for tracking issue rates over time —
  the connection error CSV already captures everything needed, and a
  UI surface can come later if useful.

## Background

- `ConnectionIssue` (in `core/.../service/ConnectionIssue.kt`) carries a
  `ConnectionIssueCode` enum (16 codes), a `message`, and a
  `RecoveryDisposition`. Already plumbed through
  `ConnectionState.ConnectionLost` and `ConnectionState.Failed`.
- iOS Swift already mirrors this as `ConnectionIssueContext { code: String,
  isRecoverable: Bool }` inside `ConnectionStateWrapper.connectionLost` and
  `.failed`.
- `BackgroundManager.postConnectionLostNotification(wheelName:)` exists
  but: gates on `isInBackground`, ignores the issue code, and uses a
  per-timestamp identifier so notifications stack rather than replace.
- The recovery episode lifecycle is already tracked via
  `recoveryEpisodeAddress` in `WheelManager.swift`. We can hang
  notification firing off the same signal.

## Approach

**One notification per recovery episode, replaced as state changes.**

For each connection-lost → recovered (or exhausted) sequence:

1. On `.connectionLost`: if `isInBackground`, post a notification with
   identifier `connection_episode_<address>`, title `FreeWheel`, body
   `Lost <wheelName>: <issue.message>`, and `userInfo` carrying the
   structured fields (see below). Stash the episode metadata
   (`address`, `wheelName`, the full `ConnectionIssueContext`,
   `startedAtMs`) on a new `notificationEpisode` field — see
   "Episode bookkeeping" below.
2. On `.connected` *when `notificationEpisode?.address == address`*:
   replace the same identifier with a "Reconnected to <wheelName>"
   notification, carrying the stashed original issue forward into the
   payload. Clear `notificationEpisode`.
3. On terminal `.failed` mid-recovery, ride-pause timeout, or
   different-wheel `.connected`: if `notificationEpisode` is set,
   replace with "Recovery gave up: <reason>" carrying the stashed
   original issue plus the new failure context. Clear
   `notificationEpisode`.

The stable identifier means notification center never accumulates a
backlog from a single ride.

### Episode bookkeeping — why a separate field from `recoveryEpisodeAddress`

`recoveryEpisodeAddress` (already in `WheelManager.swift`) tracks the
recovery state machine and is set on **every** `.connectionLost`,
foregrounded or not. Using it as the gate for the "restored"
notification would post a restored banner even when no "lost" banner
preceded it — e.g., app foregrounded when the link drops, then user
backgrounds the app during recovery: `recoveryEpisodeAddress != nil`
even though we never posted "lost".

Track a separate `notificationEpisode` that is **only** set when we
actually post the "lost" notification. The "restored" / "given_up"
notifications fire only when `notificationEpisode != nil` and the
address matches. This decouples notification lifecycle from recovery
lifecycle.

Stashing the full `ConnectionIssueContext` (not just the address) on
the episode means follow-up notifications can carry the original
`issueCode` / `isRecoverable` into their payloads even after the live
`ConnectionState` has moved on — see "Structured payload" below.

### Foreground vs. background

Match the existing `postConnectionLostNotification` policy: only post
"lost" when `backgroundManager.isInBackground`. Foregrounded users
already see the `ConnectionBanner` view; an extra OS banner is
redundant. (Override via `UNUserNotificationCenterDelegate.willPresent`
is out of scope.) Because `notificationEpisode` is only set inside the
backgrounded "lost" branch, restored / given_up automatically inherit
the same gate without an explicit foreground check.

## File-level changes

### `iosApp/FreeWheel/Bridge/BackgroundManager.swift`

Replace the existing `postConnectionLostNotification` with:

```swift
func postConnectionLostNotification(
    wheelName: String,
    address: String,
    issue: ConnectionIssueContext
)

// Restored / given_up take the SAME issue context — the one stashed on
// `notificationEpisode` at the time the lost notification was posted.
// This guarantees `userInfo` is shape-consistent across all three kinds:
// every payload carries the original disconnect's issueCode / isRecoverable.
//
// Given_up additionally carries the new failure context that ended the
// recovery so the body can read "Recovery gave up: <reason>" while
// userInfo retains both the original cause and the terminal cause.

func postConnectionRestoredNotification(
    wheelName: String,
    address: String,
    originalIssue: ConnectionIssueContext,
    recoveryDurationMs: Int64
)

func postRecoveryGivenUpNotification(
    wheelName: String,
    address: String,
    originalIssue: ConnectionIssueContext,
    terminalIssue: ConnectionIssueContext?,
    reason: String
)
```

All three use identifier `"connection_episode_\(address)"` so they
replace each other. See "Structured payload" below for the
`userInfo` schema.

### Structured payload

Every notification posted by these APIs carries a `userInfo` dictionary
with this shape:

```swift
[
    "kind": "lost" | "restored" | "given_up",
    "address": String,
    "wheelName": String,
    // Original disconnect cause — present on all three kinds because
    // restored/given_up inherit it from notificationEpisode.
    "issueCode": String,            // e.g. "CONNECTION_TIMED_OUT"
    "isRecoverable": Bool,
    // Optional fields by kind:
    "recoveryDurationMs": Int64?,   // restored only
    "terminalIssueCode": String?,   // given_up only, when a typed terminal
                                    // failure ended the recovery (e.g.
                                    // PERIPHERAL_NOT_FOUND). Nil when the
                                    // episode ended on pause-timeout or a
                                    // user disconnect.
    "terminalReason": String?,      // given_up only, free-form display text
                                    // mirroring the notification body
]
```

`issueCode` matches the `ConnectionIssueCode` enum (see "ConnectionIssueKind"
below). Analytics / tap-handlers should treat unknown raw codes as
`UNKNOWN` for forward-compat with future KMP additions.

### `iosApp/FreeWheel/Bridge/WheelManager.swift`

Add a new field:

```swift
private struct NotificationEpisode {
    let address: String
    let wheelName: String
    let issue: ConnectionIssueContext  // original disconnect cause
    let startedAtMs: Int64
}

private var notificationEpisode: NotificationEpisode?
```

**Episode lifecycle rule (single source of truth).** Every terminal
path must follow the same three steps in order:

1. **Read** `notificationEpisode` into a local.
2. **Act** — post the appropriate notification (or call
   `removeDeliveredNotifications(withIdentifiers:)` for the
   user-disconnect path; see below).
3. **Clear** `notificationEpisode = nil`.

`clearReconnectRecovery()` must NOT touch `notificationEpisode` —
notification lifecycle is owned by the terminal handlers below, not
by the recovery cleanup helper. If a future caller adds a new
recovery-clear path, they must make an explicit decision about the
episode (post / remove / leave) rather than inheriting silent
cleanup.

In `handleConnectionStateChange`:

- `.connectionLost` case: capture the `issue` (currently discarded as
  `_`). If `backgroundManager.isInBackground`, post the lost
  notification AND set
  `notificationEpisode = NotificationEpisode(address: address,
  wheelName: identity.displayName, issue: issue, startedAtMs: now)`.
  If foregrounded, post nothing and leave `notificationEpisode` nil so
  the follow-up notifications won't fire either.
- `.connected` case: consume the episode **before** calling
  `clearReconnectRecovery()` — the recovery helper might one day grow
  side effects, and the doc rule above forbids relying on its order
  with respect to notifications. Two sub-cases:
  - **Same-address recovery** (`notificationEpisode?.address == address`):
    snapshot the episode, call `postConnectionRestoredNotification(
    wheelName:, address:, originalIssue: episode.issue,
    recoveryDurationMs: now - episode.startedAtMs)`, then set
    `notificationEpisode = nil`. Don't re-check `isInBackground` —
    the episode's existence already proves we were backgrounded when
    "lost" was posted, and the OS will deliver the replacement
    regardless of current foreground state.
  - **Different-address connect with an open episode**
    (`notificationEpisode != nil && notificationEpisode.address !=
    address`): the user (or auto-connect) bound to a different wheel
    while the prior wheel's recovery was still notional, so the
    original recovery is implicitly abandoned. Call
    `postRecoveryGivenUpNotification(wheelName: episode.wheelName,
    address: episode.address, originalIssue: episode.issue,
    terminalIssue: nil, reason: "Connected to a different wheel")` so
    the stale "Lost" banner is replaced with a coherent terminal
    state, then clear `notificationEpisode`. The replacement uses
    the **episode's** identifier (`connection_episode_<episode.address>`),
    not the new connection's, since the banner being replaced belongs
    to the prior wheel.
- Terminal `.failed` (the non-preserved branch) and pause-timeout
  exhaustion (in `ridePauseTimeoutTask`): same read → act → clear
  pattern. If `notificationEpisode` is set, call
  `postRecoveryGivenUpNotification(...)` passing the stashed
  `episode.issue` as `originalIssue` and the `.failed` state's issue
  (or `nil` for the pause-timeout path) as `terminalIssue`. Then clear.
- **User-initiated disconnect mid-recovery** (handled in
  `.disconnected` when `pendingUserDisconnect` is true). If
  `notificationEpisode` is set, the previously delivered "Lost"
  banner is still in Notification Center and contradicts the user's
  intent. Call
  `backgroundManager.removeConnectionEpisodeNotification(address:
  episode.address)` to drop it, then clear `notificationEpisode`.
  Don't post a replacement — the user knows they tapped Disconnect
  and an extra banner would be noise.

### `iosApp/FreeWheel/Bridge/BackgroundManager.swift` additional API

```swift
func removeConnectionEpisodeNotification(address: String) {
    let identifier = "connection_episode_\(address)"
    UNUserNotificationCenter.current().removeDeliveredNotifications(
        withIdentifiers: [identifier]
    )
    // Also drop any pending (not-yet-delivered) request with the same
    // identifier so a race between post and remove can't strand a
    // "Lost" banner that arrives after the user disconnects.
    UNUserNotificationCenter.current().removePendingNotificationRequests(
        withIdentifiers: [identifier]
    )
}
```

### New: `iosApp/FreeWheel/Bridge/ConnectionIssueKind.swift`

Swift enum mirror of the KMP `ConnectionIssueCode`:

```swift
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
```

`ConnectionIssueContext.code` becomes a computed accessor that returns
this enum. Existing callers that store `code: String` continue to work
because the raw string is preserved via `rawValue`.

Source of truth stays in KMP — if a new code is added to
`ConnectionIssueCode`, Swift will fall back to `.unknown` and a
compile-time `CaseIterable` check (added as a debug assertion or unit
test) catches the drift.

## Tracking surface

Beyond the notification itself, the structured `userInfo` payload means
anyone tapping a notification can route to a diagnostics view that
shows the recent episodes. That tap-routing is out of scope for this
plan, but the data model supports it.

The recovery-episode CSV log (already in place from the previous step)
remains the canonical source for post-mortem analysis. Notifications
are real-time signal; the CSV is the record.

## Decisions locked from review

These were "open questions" in the v1 draft and were resolved during
Codex review on 2026-05-11. Recorded here so we don't relitigate.

- **Foreground policy** — keep the `isInBackground` gate. Foregrounded
  users see `ConnectionBanner`; an OS banner on top is redundant.
- **`given_up` granularity** — one headline ("Recovery gave up: \<reason>")
  with the cause distinguished in the body / `userInfo`. Two headlines
  would force a UX decision we don't have data to make yet.
- **Sound** — `.default` for `lost` and `given_up`; no sound for
  `restored`. Recovery success doesn't need to interrupt.
- **Android parity** — defer until iOS UX is proven. The shared KMP
  recovery model already gives Android everything it needs to repeat
  the pattern when we're ready.

## Test plan

- Unit test on KMP side: confirm `ConnectionIssueCode.entries` matches
  the Swift enum (parsed from the file). Catches drift.
- Manual iOS test scenarios:
  1. **Background → lost → recovered.** Background app, force a
     disconnect (turn wheel off briefly) → expect "Lost" notification
     with `kind=lost`, `issueCode=CONNECTION_TIMED_OUT` (or similar).
     Turn wheel back on → expect "Restored" notification *replacing*
     the lost one (same `connection_episode_<address>` identifier),
     `kind=restored`, `issueCode` echoes the original.
  2. **Background → lost → given up.** Background app, force a
     disconnect, leave wheel off past the pause timeout → expect
     "Given up" notification replacing the "Lost" one,
     `kind=given_up`, `terminalIssueCode=nil` (pause-timeout path),
     `terminalReason` populated.
  3. **Background → lost → terminal failure.** Background app, force a
     disconnect, force a terminal failure (e.g. peripheral removed) →
     expect `kind=given_up`, `terminalIssueCode=PERIPHERAL_NOT_FOUND`
     (or equivalent), with the original `issueCode` preserved.
  4. **Foreground → drop → background → recover.** Drop the link
     while foregrounded (no "lost" notification posted, episode
     remains nil), then background the app while recovery is still
     running. When recovery succeeds, expect **no "restored"
     notification** — the `notificationEpisode` was never set, so the
     follow-up gate stays closed. Regression test for Codex finding #1.
  5. **Foreground throughout** — no notifications at all (foreground
     gate).
  6. **User-initiated disconnect (no recovery)** — no notification
     (existing `pendingUserDisconnect` gate already excludes this).
  7. **User-initiated disconnect mid-recovery.** Background app, force
     a disconnect → "Lost" notification appears in Notification
     Center. While recovery is still trying, foreground the app and
     tap Disconnect. Expect the "Lost" notification to be **removed**
     from Notification Center (via
     `removeConnectionEpisodeNotification`); no replacement banner
     appears.
  8. **Different-wheel connect with open episode.** Background app,
     force a disconnect on wheel A → "Lost" notification appears.
     Foreground and connect to wheel B (different address). Expect
     the wheel-A "Lost" banner to be replaced by a "Given up"
     notification (same `connection_episode_<wheelA-address>`
     identifier) carrying wheel A's original `issueCode` and
     `terminalReason="Connected to a different wheel"`. Connecting
     to wheel B itself triggers no extra notification — that's a
     fresh session, not a recovery.
