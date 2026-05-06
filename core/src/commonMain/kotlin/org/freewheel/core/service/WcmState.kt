package org.freewheel.core.service

import org.freewheel.core.ble.BleAdvertisement
import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.EventLogEntry
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelType
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
    // Speculative wheel-type hint passed at connect() time (e.g. derived from
    // the advertised name on iOS scan, or a saved per-MAC profile on Android).
    // The hint biases service-discovery's Ambiguous branch toward this type
    // instead of falling back to GOTWAY_VIRTUAL. Cleared once consumed by
    // reduceServicesDiscovered. Distinct from `identity.wheelType`, which is
    // CONFIRMED state populated only from successful detection or decoded data.
    val wheelTypeHint: WheelType? = null,
    // Scan-time advertisement evidence captured at connect() time. Read by the
    // topology fingerprinting matcher (Pass 2). Cleared by reduceDisconnect to
    // avoid stale carry-over across sessions.
    val lastAdvertisement: BleAdvertisement? = null,
    // Internal — not exposed as public flows
    val decoder: WheelDecoder? = null,
    val decoderConfig: DecoderConfig = DecoderConfig(),
) {
    /** Lightweight decoder input — avoids full state composition per frame. */
    val decoderState: DecoderState
        get() = DecoderState(telemetry ?: TelemetryState(), identity, bms, settings)
}

/**
 * Side effects produced by the reducer. Executed after the state transition.
 *
 * All variants are data classes or data objects for structural equality,
 * copy(), and meaningful toString() — important for packet capture and
 * connection error logging.
 */
sealed class WcmEffect {
    data class BleConnect(val address: String) : WcmEffect()

    data object BleDisconnect : WcmEffect()

    data class DispatchCommands(
        val commands: List<WheelCommand>,
        val decoder: WheelDecoder? = null,
        val decoderState: DecoderState? = null,
    ) : WcmEffect()

    data class StartKeepAlive(val intervalMs: Long) : WcmEffect()

    data class StartDataTimeout(val address: String, val timeoutMs: Long) : WcmEffect()

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

    data class ConfigureBle(
        val readServiceUuid: String,
        val readCharUuid: String,
        val writeServiceUuid: String,
        val writeCharUuid: String,
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