# iOS Manual Verification Checklist

The iOS unit tests in `iosApp/FreeWheelTests/` cover the pure / value-level
pieces (`RideMetadata` Codable backward-compat and the dashboard-layout
migration). The platform-heavy paths below are intentionally NOT unit-tested —
they're tied to UIKit, AVFoundation, `UNUserNotificationCenter`, KMP observer
teardown, or app-lifecycle events. Verify them by hand in the simulator before
shipping changes that touch these surfaces.

## `WheelManager.shutdown()` — terminate-mid-ride safety

- Enable demo mode → start a ride (recording on)
- Force-quit the app (Cmd-Shift-H twice in sim, swipe up the app)
- Reopen → previous ride should appear in Rides with distance/duration
  metadata populated
- If a connection error log was active, the `connection_errors.csv` footer
  line should be present (not a truncated tail)

## `AlarmManager` — firing, throttling, phone-vs-wheel routing

- Settings → set Speed Alarm 1 to a low value (e.g. 10 km/h)
- Demo mode → push speed above threshold; confirm one haptic + one tone
- Hold speed above threshold continuously; confirm subsequent fires are
  throttled (~2/sec ceiling, not a stream)
- Switch alarm action between Phone Only / Phone + Wheel; with demo, the
  wheel-beep callback should only fire on the Phone+Wheel variants (no-op
  since demo has no wheel, but no crash)
- Disable alarms — confirm `activeAlarms` clears immediately

## `BackgroundManager` — connection-episode notifications

- Connect to a wheel (real or demo)
- Background the app
- Force a disconnect (move out of range, or toggle BT off briefly on real
  hardware)
- Notification Center: "Lost <wheel>: <reason>" should appear *once*
- Restore signal → notification should *replace* (not stack) with
  "Reconnected to <wheel>"
- Repeat but let recovery time out → final notification replaces to
  "Recovery gave up: <reason>"
- Repeat: while in recovery, connect to a *different* wheel → terminal
  given-up banner with `terminalIssueCode` in `userInfo`
- Repeat: while in recovery, tap Disconnect → no banner should linger

## Running the unit tests

```
xcodebuild -project iosApp/FreeWheel.xcodeproj \
  -scheme FreeWheel \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test
```
