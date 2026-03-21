package org.freewheel.core.logging

import org.freewheel.core.protocol.UnpackerStats
import org.freewheel.core.utils.PlatformDateFormatter

/**
 * Formats [ConnectionErrorEvent] instances as CSV for file-based error logging.
 *
 * Each connection session produces a CSV file with:
 * - `#`-prefixed metadata header (wheel info, connect time)
 * - CSV data rows (one per error event)
 * - `#`-prefixed metadata footer (disconnect info, summary stats)
 *
 * Accessible from Swift as `ConnectionErrorCsvFormatter.shared`.
 */
object ConnectionErrorCsvFormatter {

    private const val CSV_HEADER = "timestamp,elapsed_ms,event_type,detail"

    /**
     * Opening metadata lines written when the session starts.
     */
    fun headerComment(
        wheelType: String,
        wheelName: String,
        address: String,
        connectTimeMs: Long
    ): String = buildString {
        appendLine("# FreeWheel Connection Error Log")
        appendLine("# wheel_type=$wheelType,wheel_name=$wheelName,address=$address")
        appendLine("# connect_time=${PlatformDateFormatter.formatFriendlyDate(connectTimeMs)}")
        append(CSV_HEADER)
    }

    /**
     * Closing metadata lines written when the session ends.
     */
    fun footerComment(
        disconnectTimeMs: Long,
        disconnectReason: String,
        totalFramesDecoded: Int,
        unpackerStats: UnpackerStats?
    ): String = buildString {
        val safeReason = disconnectReason.replace(",", ";")
        appendLine("# disconnect_time=${PlatformDateFormatter.formatFriendlyDate(disconnectTimeMs)}")
        appendLine("# disconnect_reason=$safeReason")
        append("# total_frames=$totalFramesDecoded")
        if (unpackerStats != null) {
            append(",unpacker_error_resets=${unpackerStats.errorResets}")
            append(",unpacker_bytes_discarded=${unpackerStats.bytesDiscarded}")
        }
    }

    /**
     * Format a single error event as one CSV row.
     */
    fun formatEvent(event: ConnectionErrorEvent, sessionStartMs: Long): String {
        val elapsed = event.timestampMs - sessionStartMs
        val timestamp = PlatformDateFormatter.formatFriendlyDate(event.timestampMs)
        val safeTimestamp = timestamp.replace(",", ";")
        return when (event) {
            is ConnectionErrorEvent.BleError ->
                "$safeTimestamp,$elapsed,ble_error,consecutive_count=${event.consecutiveCount}"
            is ConnectionErrorEvent.DataTimeout ->
                "$safeTimestamp,$elapsed,data_timeout,address=${event.address}"
            is ConnectionErrorEvent.DecodeException -> {
                val safeMsg = event.message.replace(",", ";")
                "$safeTimestamp,$elapsed,decode_exception,$safeMsg"
            }
            is ConnectionErrorEvent.UnhandledFrame -> {
                val safeDetail = event.detail.replace(",", ";")
                "$safeTimestamp,$elapsed,unhandled_frame,error_class=${event.errorClass}:$safeDetail"
            }
            is ConnectionErrorEvent.StateTransition -> {
                val safeReason = event.reason.replace(",", ";")
                "$safeTimestamp,$elapsed,state_transition,from=${event.from} to=${event.to} reason=$safeReason"
            }
        }
    }
}
