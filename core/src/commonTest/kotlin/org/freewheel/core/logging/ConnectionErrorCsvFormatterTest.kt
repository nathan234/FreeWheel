package org.freewheel.core.logging

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class ConnectionErrorCsvFormatterTest {

    @Test
    fun `headerComment includes wheel info and CSV header`() {
        val result = ConnectionErrorCsvFormatter.headerComment(
            wheelType = "veteran",
            wheelName = "Patton",
            address = "AA:BB:CC:DD:EE:FF",
            connectTimeMs = 1710600000000L
        )
        val lines = result.lines()

        assertTrue(lines[0].startsWith("# FreeWheel Connection Error Log"))
        assertTrue(lines[1].contains("wheel_type=veteran"))
        assertTrue(lines[1].contains("wheel_name=Patton"))
        assertTrue(lines[1].contains("address=AA:BB:CC:DD:EE:FF"))
        assertTrue(lines[2].startsWith("# connect_time="))
        assertEquals("timestamp,elapsed_ms,event_type,detail", lines.last())
    }

    @Test
    fun `footerComment includes disconnect info`() {
        val result = ConnectionErrorCsvFormatter.footerComment(
            disconnectTimeMs = 1710600060000L,
            disconnectReason = "Too many BLE errors",
            totalFramesDecoded = 500,
            unpackerStats = null
        )
        val lines = result.lines()

        assertTrue(lines[0].startsWith("# disconnect_time="))
        assertTrue(lines[1].contains("disconnect_reason=Too many BLE errors"))
        assertTrue(lines[2].contains("total_frames=500"))
        // No unpacker stats when null
        assertFalse(lines[2].contains("unpacker_error_resets"))
    }

    @Test
    fun `footerComment includes unpacker stats when provided`() {
        val stats = org.freewheel.core.protocol.UnpackerStats(errorResets = 3, bytesDiscarded = 48)
        val result = ConnectionErrorCsvFormatter.footerComment(
            disconnectTimeMs = 1710600060000L,
            disconnectReason = "No data received",
            totalFramesDecoded = 1000,
            unpackerStats = stats
        )

        assertTrue(result.contains("unpacker_error_resets=3"))
        assertTrue(result.contains("unpacker_bytes_discarded=48"))
    }

    @Test
    fun `formatEvent BleError`() {
        val event = ConnectionErrorEvent.BleError(
            timestampMs = 1710600010000L,
            consecutiveCount = 5
        )
        val row = ConnectionErrorCsvFormatter.formatEvent(event, sessionStartMs = 1710600000000L)

        assertTrue(row.contains(",10000,"))
        assertTrue(row.contains("ble_error"))
        assertTrue(row.contains("consecutive_count=5"))
    }

    @Test
    fun `formatEvent DataTimeout`() {
        val event = ConnectionErrorEvent.DataTimeout(
            timestampMs = 1710600030000L,
            address = "AA:BB:CC"
        )
        val row = ConnectionErrorCsvFormatter.formatEvent(event, sessionStartMs = 1710600000000L)

        assertTrue(row.contains(",30000,"))
        assertTrue(row.contains("data_timeout"))
        assertTrue(row.contains("address=AA:BB:CC"))
    }

    @Test
    fun `formatEvent DecodeException escapes commas`() {
        val event = ConnectionErrorEvent.DecodeException(
            timestampMs = 1710600005000L,
            message = "ArrayIndexOutOfBounds, index 10"
        )
        val row = ConnectionErrorCsvFormatter.formatEvent(event, sessionStartMs = 1710600000000L)

        assertTrue(row.contains("decode_exception"))
        // Comma in message should be escaped
        assertTrue(row.contains("ArrayIndexOutOfBounds; index 10"))
    }

    @Test
    fun `formatEvent UnhandledFrame`() {
        val event = ConnectionErrorEvent.UnhandledFrame(
            timestampMs = 1710600002000L,
            errorClass = "CHECKSUM_MISMATCH",
            detail = "0xa4"
        )
        val row = ConnectionErrorCsvFormatter.formatEvent(event, sessionStartMs = 1710600000000L)

        assertTrue(row.contains(",2000,"))
        assertTrue(row.contains("unhandled_frame"))
        assertTrue(row.contains("error_class=CHECKSUM_MISMATCH:0xa4"))
    }

    @Test
    fun `formatEvent StateTransition`() {
        val event = ConnectionErrorEvent.StateTransition(
            timestampMs = 1710600060000L,
            from = "Connected to Patton",
            to = "Connection lost: Too many BLE errors",
            reason = "Too many BLE errors"
        )
        val row = ConnectionErrorCsvFormatter.formatEvent(event, sessionStartMs = 1710600000000L)

        assertTrue(row.contains(",60000,"))
        assertTrue(row.contains("state_transition"))
        assertTrue(row.contains("from=Connected to Patton"))
        assertTrue(row.contains("to=Connection lost: Too many BLE errors"))
    }

    @Test
    fun `footerComment escapes commas in disconnect reason`() {
        val result = ConnectionErrorCsvFormatter.footerComment(
            disconnectTimeMs = 1710600060000L,
            disconnectReason = "Error code 8, connection lost",
            totalFramesDecoded = 100,
            unpackerStats = null
        )
        assertTrue(result.contains("disconnect_reason=Error code 8; connection lost"))
    }
}
