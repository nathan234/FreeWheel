package org.freewheel.core.logging

/**
 * Events logged during a BLE connection session for error analysis.
 *
 * Each event carries a timestamp so the full session can be replayed chronologically.
 * Events are written to CSV files via [ConnectionErrorCsvFormatter].
 */
sealed class ConnectionErrorEvent {
    abstract val timestampMs: Long

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
}
