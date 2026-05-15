package org.freewheel.core.service

import org.freewheel.core.domain.identity.ProtocolFamily
import org.freewheel.core.domain.profile.WheelProfile
import org.freewheel.core.domain.identity.WheelType

/**
 * Speculative hint passed to [WheelConnectionManager.connect] about which
 * protocol family to bias detection toward.
 *
 * Distinct from confirmed wheel-type identity (`WheelState.identity.wheelType`).
 * The reducer stashes it on `WcmState.connectionHint`, then [reduceServicesDiscovered]
 * consumes it once during the Ambiguous detection branch and clears it — a
 * subsequent reconnect or re-detection should not silently re-use a stale guess.
 */
data class ConnectionHint(
    val suggestedProtocol: ProtocolFamily,
    val source: HintSource,
    val rawName: String? = null,
)

/**
 * Where a [ConnectionHint] originated. Recorded for diagnostics and to let the
 * detector apply different precedence rules per source if needed.
 */
enum class HintSource {
    /** Derived from the advertised name observed during scanning (iOS today). */
    SCAN_NAME,

    /** Loaded from a per-MAC profile saved on the device (Android). */
    SAVED_PROFILE,

    /** Caller passed an explicit [WheelType] (e.g., legacy API or user picker). */
    EXPLICIT_API,

    /** OS-driven auto-reconnect carrying forward the prior session's identity. */
    AUTO_RECONNECT,
}

/**
 * Build a SAVED_PROFILE hint from a saved [WheelProfile], or null when the
 * stored type is missing or non-protocol-bearing
 * ([WheelType.Unknown], [WheelType.GOTWAY_VIRTUAL]).
 */
fun WheelProfile.toSavedHint(): ConnectionHint? {
    if (wheelTypeName.isBlank()) return null
    val type = WheelType.fromString(wheelTypeName)
    val family = ProtocolFamily.fromWheelType(type) ?: return null
    return ConnectionHint(family, HintSource.SAVED_PROFILE)
}
