package org.freewheel.core.service

import org.freewheel.core.ble.BleAdvertisement
import org.freewheel.core.ble.DiscoveredServices
import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.domain.telemetry.BmsState
import org.freewheel.core.domain.identity.CapabilitySet
import org.freewheel.core.domain.events.EventLogEntry
import org.freewheel.core.domain.telemetry.TelemetryState
import org.freewheel.core.domain.identity.WheelIdentity
import org.freewheel.core.domain.settings.WheelSettings
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.logging.ConnectionErrorEvent
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.protocol.WheelCommand
import org.freewheel.core.protocol.WheelDecoder
import org.freewheel.core.validation.TelemetryThrottleState

/**
 * Single source of truth for all [WheelConnectionManager] state.
 *
 * Primary domain state is held as separate types ([telemetry], [identity],
 * [bms], [settings]) so only the changed domain is copied per BLE frame.
 *
 * [connectionInfo] is non-null whenever [connectionState] is past [ConnectionState.Scanning] —
 * the reducer is responsible for enforcing this invariant at the transition boundary.
 *
 * [capabilities] are populated during service discovery. Callers should gate on
 * [connectionState] being [ConnectionState.Connected] before trusting capability values.
 *
 */
data class WcmState(
    // Primary domain state
    val telemetry: TelemetryState? = null,
    val identity: WheelIdentity = WheelIdentity(),
    val bms: BmsState = BmsState(),
    val settings: WheelSettings = WheelSettings.None,
    // Connection / decoder metadata
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val connectionInfo: WheelConnectionInfo? = null,
    val capabilities: CapabilitySet = CapabilitySet(),
    val consecutiveDecodeErrors: Int = 0,
    val consecutiveBleErrors: Int = 0,
    // Event log download (accumulated across frames)
    val eventLogEntries: List<EventLogEntry> = emptyList(),
    // Per-field throttle state for telemetry bounds validator (reducer stays pure
    // by carrying this across frames instead of mutating a field-side cache).
    val telemetryThrottleState: TelemetryThrottleState = TelemetryThrottleState(),
    // Speculative connection hint passed at connect() time (e.g. derived from
    // the advertised name on iOS scan, a saved per-MAC profile on Android, or
    // an OS-driven auto-reconnect carrying the prior identity forward). Biases
    // service-discovery's Ambiguous branch toward [ConnectionHint.suggestedProtocol]
    // instead of falling back to GOTWAY_VIRTUAL. Cleared once consumed by
    // reduceServicesDiscovered. Distinct from `identity.wheelType`, which is
    // CONFIRMED state populated only from successful detection or decoded data.
    val connectionHint: ConnectionHint? = null,
    // Scan-time advertisement evidence captured at connect() time. Read by the
    // topology fingerprinting matcher (Pass 2). Cleared by reduceDisconnect to
    // avoid stale carry-over across sessions.
    val lastAdvertisement: BleAdvertisement? = null,
    // Full GATT topology captured at the most recent ServicesDiscovered event.
    // Surfaced to the UI so the unrecognized-wheel report (Pass 3b) can attach
    // the complete service+characteristic dump to the GitHub issue. Cleared by
    // reduceDisconnect.
    val lastDiscoveredServices: DiscoveredServices? = null,
    // Monotonic counter of connect attempts; incremented only by reduceConnect.
    // Used to mint a fresh [currentAttemptId] every time a new connect event is
    // accepted, so events emitted by a prior session can be detected as stale.
    val attemptCounter: Long = 0L,
    // ID of the in-flight connection attempt, or null when Disconnected.
    // Events stamped with a different attemptId are dropped by the reducer to
    // prevent stale-session callbacks (the OS BLE stack can deliver
    // ServicesDiscovered / BleDisconnected / DataReceived from the previous
    // session well after disconnect → reconnect) from corrupting the new state.
    val currentAttemptId: Long? = null,
    // Whether the platform BLE layer has confirmed that notifications are
    // active on the configured read characteristic. Reset to false on any
    // transition out of an active session (disconnect, failed, connection
    // lost). Commit 1 of the Kingsong BLE parity plan — later commits gate
    // transport warmups and heartbeats on this flag flipping true so
    // post-connect traffic cannot race the OS BLE stack.
    val isBleReady: Boolean = false,
    // Internal — not exposed as public flows
    val decoder: WheelDecoder? = null,
    val decoderConfig: DecoderConfig = DecoderConfig(),
) {
    /** Lightweight decoder input — avoids full state composition per frame. */
    val decoderState: DecoderState
        get() = DecoderState(telemetry ?: TelemetryState(), identity, bms, settings)

    /**
     * Wheel-family transport profile currently in effect.
     *
     * Sourced from [connectionInfo] when one is bound (post service
     * discovery); falls back to [WheelTransportProfile.Default] before then.
     * The reducer captures this into [WcmEffect.DispatchCommands] so each
     * dispatched sequence runs against a stable transport policy, even if
     * the connection info changes mid-sequence.
     */
    val activeTransportProfile: WheelTransportProfile
        get() = connectionInfo?.transportProfile ?: WheelTransportProfile.Default
}

/**
 * Side effects produced by the reducer. Executed after the state transition.
 *
 * All variants are data classes or data objects for structural equality,
 * copy(), and meaningful toString() — important for packet capture and
 * connection error logging.
 */
sealed class WcmEffect {
    data class BleConnect(val address: String, val attemptId: Long) : WcmEffect()

    data object BleDisconnect : WcmEffect()

    data class DispatchCommands(
        val commands: List<WheelCommand>,
        val decoder: WheelDecoder? = null,
        val decoderState: DecoderState? = null,
        /**
         * Snapshot of the wheel-family transport profile in effect when this
         * effect was reduced. Captured here (alongside the decoder snapshot)
         * so the dispatch coroutine works against a stable choice even if the
         * connection info changes before execution. Defaults to
         * [WheelTransportProfile.Default] — used by tests and by reducers that
         * dispatch before service discovery has bound a connection info.
         */
        val transportProfile: WheelTransportProfile = WheelTransportProfile.Default,
        /**
         * Commit 5 of `KINGSONG_BLE_PARITY_PLAN.md`. One ticket per semantic
         * [WheelCommand] in [commands], minted by the reducer at effect
         * creation time. The executor uses these to publish lifecycle
         * transitions on
         * [WheelConnectionManagerPort.commandTickets].
         *
         * Invariants when populated:
         *  - `tickets.size == commands.size`
         *  - `tickets[i]` corresponds to `commands[i]`
         *
         * Defaults to empty. Production reducer call sites always populate
         * it; the empty default exists so unit tests and any future reducer
         * that intentionally dispatches anonymous (ticket-less) traffic can
         * still construct the effect. The executor logs a warning when
         * [commands] is non-empty but [tickets] is empty.
         */
        val tickets: List<CommandTicket> = emptyList(),
    ) : WcmEffect()

    data class StartKeepAlive(val intervalMs: Long) : WcmEffect()

    /**
     * Begin transport-driven post-connect traffic for [transportProfile].
     *
     * Commit 3 of the Kingsong BLE parity plan. Emitted by [reduceBleReady] once
     * the platform notify callback has flipped [WcmState.isBleReady] to true.
     * The executor schedules:
     *
     * - one job per [WheelTransportProfile.postConnectWarmups] entry (delayed
     *   by [PostConnectWarmup.delayMs] from BLE-ready), and
     * - if [WheelTransportProfile.keepAlivePolicy] is
     *   [TransportKeepAlivePolicy.FixedFrame], a recurring job firing every
     *   [TransportKeepAlivePolicy.FixedFrame.intervalMs] starting after the
     *   initial interval.
     *
     * Both run on the WCM scope/dispatcher and call `sendBleData` directly,
     * with the [PostConnectWarmup.annotation] /
     * [TransportKeepAlivePolicy.FixedFrame.annotation] forwarded to BLE
     * capture and [BleWriteRequest.annotation]. The executor cancels any
     * pre-existing transport-maintenance jobs first, defensively, so
     * re-entry (e.g. a spurious double BLE-ready) cannot stack timers.
     */
    data class StartTransportMaintenance(
        val transportProfile: WheelTransportProfile,
    ) : WcmEffect()

    /**
     * Cancel all transport-driven post-connect jobs without touching the
     * decoder-driven keepalive or data-timeout watchdog. Emitted by
     * [reduceBleDisconnected] (the BLE-ready scope ends with the link, not
     * with the session — see [StopTimers]) and by [reduceConnect] on the
     * resume path. The next [BleReady] re-emits
     * [StartTransportMaintenance] so heartbeat/warmup replay after every
     * reconnect.
     */
    data object StopTransportMaintenance : WcmEffect()

    data class StartDataTimeout(val address: String, val timeoutMs: Long) : WcmEffect()

    /**
     * Reset the data-timeout watchdog because a fresh frame arrived. Emitted
     * by [WheelConnectionManager.reduceDataReceived] AFTER the staleness
     * guard accepts the frame, so frames from a prior session can no longer
     * keep the new session's timeout alive.
     */
    data object NoteDataReceived : WcmEffect()

    data object StopTimers : WcmEffect()

    data object CancelBleConnect : WcmEffect()

    data object CancelCommands : WcmEffect()

    data class CapturePacket(
        val data: ByteArray,
        val direction: BlePacketDirection,
        val annotation: String = "",
    ) : WcmEffect() {
        // ByteArray breaks structural equality — provide explicit equals/hashCode
        // so CapturePacket behaves consistently as a data class.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CapturePacket) return false
            return data.contentEquals(other.data)
                    && direction == other.direction
                    && annotation == other.annotation
        }
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + direction.hashCode()
            result = 31 * result + annotation.hashCode()
            return result
        }
    }

    /**
     * Republish a BLE write-completion ack to the observation callback.
     * Commit 1 of the Kingsong BLE parity plan — purely informational; later
     * commits build the command-execution state machine on top.
     */
    data class NotifyWriteAck(val ack: BleWriteAck) : WcmEffect()

    data class NotifyUnhandled(
        val reason: String,
        val frameData: ByteArray,
    ) : WcmEffect() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NotifyUnhandled) return false
            return reason == other.reason && frameData.contentEquals(other.frameData)
        }
        override fun hashCode(): Int {
            var result = reason.hashCode()
            result = 31 * result + frameData.contentHashCode()
            return result
        }
    }

    data class ResetDecoder(val decoder: WheelDecoder) : WcmEffect()

    /**
     * Carries the whole [WheelConnectionInfo] (UUIDs + transport profile) into
     * the platform layer. Commit 2 of the Kingsong BLE parity plan: the
     * platform layer can now react to transport-profile fields (e.g.
     * [WheelTransportProfile.requestMaxMtu]) without a second plumbing pass.
     * In Commit 2 every profile is still [WheelTransportProfile.Default], so
     * the platform layer keeps its current unconditional behavior.
     */
    data class ConfigureBle(
        val connectionInfo: WheelConnectionInfo,
    ) : WcmEffect()

    data class LogConnectionError(val event: ConnectionErrorEvent) : WcmEffect()
}

/**
 * Output of the reducer: new state + side effects to execute.
 */
data class WcmTransition(
    val state: WcmState,
    val effects: List<WcmEffect> = emptyList(),
)