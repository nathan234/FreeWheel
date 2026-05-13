package org.freewheel.core.service

import org.freewheel.core.domain.TelemetryState

/**
 * Result of a [WheelConnectionManager.powerOff] dispatch.
 *
 * Mirrors the pre-shutdown gate in the official Leaperkim app's
 * `HomepageFragment.onClickShutdown` (decompiled v1.4.8 lines 192–199):
 * the wheel must be stopped and not charging before the close-in-10 command
 * is sent. The app shows a toast on block; FreeWheel returns the reason so
 * callers can decide how to surface it.
 */
sealed class PowerOffOutcome {
    /** The PowerOff command was dispatched to the connected wheel. */
    object Sent : PowerOffOutcome()

    /** PowerOff was suppressed; [reason] explains why. */
    data class Blocked(val reason: Reason) : PowerOffOutcome()

    enum class Reason {
        /** Wheel reports non-zero speed. Shutting down a moving wheel is unsafe. */
        WHEEL_MOVING,

        /** Wheel reports `chargingStatus != 0`. Shutting down while charging risks state corruption. */
        WHEEL_CHARGING,
    }
}

/**
 * Pure predicate for the PowerOff safety gate. Returns null when it is safe to
 * send the command, otherwise the reason it must be blocked.
 *
 * `telemetry == null` returns null (safe) — the gate exists to protect against
 * misuse from a known-bad state, not to require a known-good state. Without
 * telemetry there is nothing to gate on, and refusing every shutdown until
 * telemetry arrives would strand users on wheels that fail to publish a frame.
 */
internal fun powerOffBlockReason(telemetry: TelemetryState?): PowerOffOutcome.Reason? {
    telemetry ?: return null
    if (telemetry.speed != 0) return PowerOffOutcome.Reason.WHEEL_MOVING
    if (telemetry.chargingStatus != 0) return PowerOffOutcome.Reason.WHEEL_CHARGING
    return null
}
