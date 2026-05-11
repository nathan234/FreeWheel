package org.freewheel.core.logging

/**
 * Events logged during a BLE connection session for error analysis.
 *
 * Each event carries a timestamp so the full session can be replayed chronologically.
 * Events are written to CSV files via [ConnectionErrorCsvFormatter].
 */
sealed class ConnectionErrorEvent {
    abstract val timestampMs: Long

    enum class RecoveryStage {
        STARTED,
        ATTEMPTING,
        CANCELLED,
        SUCCEEDED,
        EXHAUSTED,
    }

    /** GATT/CoreBluetooth characteristic update error. */
    data class BleError(
        override val timestampMs: Long,
        val consecutiveCount: Int
    ) : ConnectionErrorEvent()

    /** No data received within the timeout threshold. */
    data class DataTimeout(
        override val timestampMs: Long,
        val address: String
    ) : ConnectionErrorEvent()

    /** Decoder threw an exception during decode(). */
    data class DecodeException(
        override val timestampMs: Long,
        val message: String
    ) : ConnectionErrorEvent()

    /** Unpacker yielded a complete frame that the decoder didn't recognize. */
    data class UnhandledFrame(
        override val timestampMs: Long,
        val errorClass: String,
        val detail: String
    ) : ConnectionErrorEvent()

    /** Connection state changed (Connected → ConnectionLost, etc.). */
    data class StateTransition(
        override val timestampMs: Long,
        val from: String,
        val to: String,
        val reason: String
    ) : ConnectionErrorEvent()

    /** Recovery lifecycle emitted by the app layer around reconnect episodes. */
    data class RecoveryLifecycle(
        override val timestampMs: Long,
        val stage: RecoveryStage,
        val address: String,
        val attempt: Int? = null,
        val detail: String = "",
    ) : ConnectionErrorEvent()

    /**
     * Decoded telemetry field fell outside a physically-impossible bound.
     * Emitted by [org.freewheel.core.validation.TelemetryValidator] — always a decoder bug.
     */
    data class TelemetryOutOfBounds(
        override val timestampMs: Long,
        val field: String,
        val value: Double,
        val min: Double,
        val max: Double
    ) : ConnectionErrorEvent()
}
