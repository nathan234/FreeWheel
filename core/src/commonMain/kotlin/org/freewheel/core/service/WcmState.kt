package org.freewheel.core.service

import org.freewheel.core.ble.WheelConnectionInfo
import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.CapabilitySet
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.logging.BlePacketDirection
import org.freewheel.core.protocol.DecoderConfig
import org.freewheel.core.protocol.DecoderState
import org.freewheel.core.protocol.WheelCommand
import org.freewheel.core.protocol.WheelDecoder

/**
 * Single source of truth for all [WheelConnectionManager] state.
 *
 * Primary domain state is held as separate types ([telemetry], [identity],
 * [bms], [settings]) so only the changed domain is copied per BLE frame.
 */
data class WcmState(
    // Primary domain state
    val telemetry: TelemetryState = TelemetryState(),
    val identity: WheelIdentity = WheelIdentity(),
    val bms: BmsState = BmsState(),
    val settings: WheelSettings = WheelSettings.None,
    // Connection / decoder metadata
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val capabilities: CapabilitySet = CapabilitySet(),
    val consecutiveDecodeErrors: Int = 0,
    val consecutiveBleErrors: Int = 0,
    // Internal — not exposed as public flows
    val decoder: WheelDecoder? = null,
    val decoderConfig: DecoderConfig = DecoderConfig(),
    val connectionInfo: WheelConnectionInfo? = null,
) {
    /** Lightweight decoder input — avoids full state composition per frame. */
    val decoderState: DecoderState get() = DecoderState(telemetry, identity, bms, settings)
}

/**
 * Side effects produced by the reducer. Executed after the state transition.
 */
sealed class WcmEffect {
    class BleConnect(val address: String) : WcmEffect()
    data object BleDisconnect : WcmEffect()
    class DispatchCommands(
        val commands: List<WheelCommand>,
        val decoder: WheelDecoder? = null
    ) : WcmEffect()
    class StartKeepAlive(val intervalMs: Long) : WcmEffect()
    class StartDataTimeout(val address: String, val timeoutMs: Long) : WcmEffect()
    data object StopTimers : WcmEffect()
    data object CancelBleConnect : WcmEffect()
    data object CancelCommands : WcmEffect()
    class CapturePacket(val data: ByteArray, val direction: BlePacketDirection, val annotation: String = "") : WcmEffect()
    class NotifyUnhandled(val reason: String, val frameData: ByteArray) : WcmEffect()
    class ResetDecoder(val decoder: WheelDecoder) : WcmEffect()
    class ConfigureBle(
        val readServiceUuid: String,
        val readCharUuid: String,
        val writeServiceUuid: String,
        val writeCharUuid: String
    ) : WcmEffect()
}

/**
 * Output of the reducer: new state + side effects to execute.
 */
data class WcmTransition(
    val state: WcmState,
    val effects: List<WcmEffect> = emptyList()
)
