package org.freewheel.core.protocol

import org.freewheel.core.domain.BmsState
import org.freewheel.core.domain.TelemetryState
import org.freewheel.core.domain.WheelIdentity
import org.freewheel.core.domain.WheelSettings
import org.freewheel.core.domain.WheelState

/**
 * Convert a hex string (with optional spaces) to a ByteArray.
 * Handles both uppercase and lowercase hex digits.
 * Example: "55 AA 17 70".hexToByteArray()
 */
internal fun String.hexToByteArray(): ByteArray {
    val hex = this.replace(" ", "").uppercase()
    return ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}

/**
 * Encode a Short as a 2-byte big-endian array.
 */
internal fun shortToBytesBE(value: Short): ByteArray = byteArrayOf(
    ((value.toInt() shr 8) and 0xFF).toByte(),
    (value.toInt() and 0xFF).toByte()
)

/**
 * Encode an Int as a 2-byte big-endian array (delegates to Short overload).
 */
internal fun shortToBytesBE(value: Int): ByteArray = shortToBytesBE(value.toShort())

// --- Domain piece assertion helpers (Phase 2 decoder migration) ---

internal fun DecodedData.assertTelemetry(): TelemetryState =
    telemetry ?: error("Expected telemetry update")

internal fun DecodedData.assertSettings(): WheelSettings =
    settings ?: error("Expected settings update")

internal fun DecodedData.assertIdentity(): WheelIdentity =
    identity ?: error("Expected identity update")

internal fun DecodedData.assertBms(): BmsState =
    bms ?: error("Expected BMS update")

/**
 * Build a [DecoderState] by applying non-null domain pieces from this
 * [DecodedData] onto [currentState]. Used in tests that chain multiple
 * decode calls and need accumulated state for subsequent calls.
 */
internal fun DecodedData.decoderStateFrom(currentState: DecoderState): DecoderState =
    DecoderState(
        telemetry = telemetry ?: currentState.telemetry,
        identity = identity ?: currentState.identity,
        bms = bms ?: currentState.bms,
        settings = settings ?: currentState.settings
    )

/**
 * Reconstruct a [WheelState] by applying non-null domain pieces from this
 * [DecodedData] onto [currentState]. Used in tests that need to assert on
 * individual WheelState fields after chaining decode calls.
 */
internal fun DecodedData.stateFrom(currentState: DecoderState): WheelState {
    val ds = decoderStateFrom(currentState)
    return ds.toWheelState()
}

/**
 * Compose a flat [WheelState] from this [DecoderState].
 * Test-only utility for assertions on individual WheelState fields.
 */
internal fun DecoderState.toWheelState(): WheelState =
    WheelState.compose(telemetry, identity, bms, settings)
