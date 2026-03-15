package org.freewheel.core.replay

import org.freewheel.core.domain.WheelType
import org.freewheel.core.logging.BlePacketDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleCaptureReaderTest {

    private val reader = BleCaptureReader()

    private val headerLines = listOf(
        "# FreeWheel BLE Capture",
        "# wheel_type: INMOTION_V2",
        "# wheel_name: V14",
        "# firmware: 2.3.7",
        "# capture_start: 2025_01_15_10_30_00",
        "# app_version: 1.2.3",
        "timestamp_ms,direction,length,hex_data,marker"
    )

    private val validHeader = headerLines.joinToString("\n")

    /** Build a CSV string with the standard header followed by the given data lines. */
    private fun csv(vararg dataLines: String): String {
        return (headerLines + dataLines.toList()).joinToString("\n")
    }

    @Test
    fun parseValidCaptureWithPacketsAndMarkers() {
        val csvContent = csv(
            "1000,RX,4,AABBCCDD,",
            "1050,TX,2,1122,",
            "1100,,,,toggled light",
            "1200,RX,3,DDEEFF,"
        )

        val capture = reader.parse(csvContent)
        assertNotNull(capture)

        // Header
        assertEquals(WheelType.INMOTION_V2, capture.header.wheelType)
        assertEquals("INMOTION_V2", capture.header.wheelTypeName)
        assertEquals("V14", capture.header.wheelName)
        assertEquals("2.3.7", capture.header.firmware)
        assertEquals("1.2.3", capture.header.appVersion)

        // Entries
        assertEquals(4, capture.entries.size)

        // First packet (RX)
        val entry0 = capture.entries[0] as CaptureEntry.Packet
        assertEquals(1000L, entry0.packet.timestampMs)
        assertEquals(BlePacketDirection.RX, entry0.packet.direction)
        assertEquals(4, entry0.packet.data.size)

        // Second packet (TX)
        val entry1 = capture.entries[1] as CaptureEntry.Packet
        assertEquals(1050L, entry1.packet.timestampMs)
        assertEquals(BlePacketDirection.TX, entry1.packet.direction)
        assertEquals(2, entry1.packet.data.size)

        // Marker
        val entry2 = capture.entries[2] as CaptureEntry.Marker
        assertEquals(1100L, entry2.marker.timestampMs)
        assertEquals("toggled light", entry2.marker.label)

        // Third packet (RX)
        val entry3 = capture.entries[3] as CaptureEntry.Packet
        assertEquals(1200L, entry3.packet.timestampMs)
        assertEquals(BlePacketDirection.RX, entry3.packet.direction)

        // Duration
        assertEquals(200L, capture.durationMs) // 1200 - 1000
    }

    @Test
    fun parseHeaderOnly() {
        val header = reader.parseHeader(validHeader)
        assertNotNull(header)
        assertEquals(WheelType.INMOTION_V2, header.wheelType)
        assertEquals("INMOTION_V2", header.wheelTypeName)
        assertEquals("V14", header.wheelName)
        assertEquals("2.3.7", header.firmware)
        assertEquals("1.2.3", header.appVersion)
    }

    @Test
    fun parseHeaderOnlyFromFullCsv() {
        val csvContent = csv(
            "1000,RX,4,AABBCCDD,",
            "1050,TX,2,1122,"
        )

        val header = reader.parseHeader(csvContent)
        assertNotNull(header)
        assertEquals(WheelType.INMOTION_V2, header.wheelType)
    }

    @Test
    fun malformedCsvNoHeader() {
        val csvContent = listOf(
            "timestamp_ms,direction,length,hex_data,marker",
            "1000,RX,4,AABBCCDD,"
        ).joinToString("\n")

        val result = reader.parse(csvContent)
        assertNull(result)
    }

    @Test
    fun malformedCsvEmptyContent() {
        assertNull(reader.parse(""))
        assertNull(reader.parseHeader(""))
    }

    @Test
    fun emptyCaptureHeaderNoDataRows() {
        val capture = reader.parse(validHeader)
        assertNotNull(capture)
        assertEquals(0, capture.entries.size)
        assertEquals(0L, capture.durationMs)
        assertEquals(WheelType.INMOTION_V2, capture.header.wheelType)
    }

    @Test
    fun markerRowParsing() {
        val csvContent = csv(
            "5000,,,,connection established",
            "5100,,,,firmware version read"
        )

        val capture = reader.parse(csvContent)
        assertNotNull(capture)
        assertEquals(2, capture.entries.size)

        val m0 = capture.entries[0] as CaptureEntry.Marker
        assertEquals("connection established", m0.marker.label)

        val m1 = capture.entries[1] as CaptureEntry.Marker
        assertEquals("firmware version read", m1.marker.label)
    }

    @Test
    fun unknownWheelType() {
        val csvContent = listOf(
            "# FreeWheel BLE Capture",
            "# wheel_type: UNKNOWN_BRAND",
            "# wheel_name: TestWheel",
            "# firmware: 1.0",
            "# capture_start: 2025_01_01_00_00_00",
            "# app_version: 1.0.0",
            "timestamp_ms,direction,length,hex_data,marker"
        ).joinToString("\n")

        val header = reader.parseHeader(csvContent)
        assertNotNull(header)
        assertEquals(WheelType.Unknown, header.wheelType)
        assertEquals("UNKNOWN_BRAND", header.wheelTypeName)
    }

    @Test
    fun diagnosticFooterIgnored() {
        val csvContent = csv(
            "1000,RX,4,AABBCCDD,",
            "# ---- Diagnostic Snapshot ----",
            "# wheel_type: INMOTION_V2",
            "# model: V14"
        )

        val capture = reader.parse(csvContent)
        assertNotNull(capture)
        assertEquals(1, capture.entries.size)
    }

    @Test
    fun invalidLineSkipped() {
        val csvContent = csv(
            "1000,RX,4,AABBCCDD,",
            "not_a_number,RX,2,1122,",
            "2000,BADDIR,2,1122,",
            "3000,RX,4,DDEEFF00,"
        )

        val capture = reader.parse(csvContent)
        assertNotNull(capture)
        // Only valid lines: first and last packets
        assertEquals(2, capture.entries.size)
    }

    @Test
    fun singlePacketDurationIsZero() {
        val csvContent = csv("1000,RX,4,AABBCCDD,")

        val capture = reader.parse(csvContent)
        assertNotNull(capture)
        assertEquals(1, capture.entries.size)
        assertEquals(0L, capture.durationMs)
    }

    @Test
    fun kingsongWheelType() {
        val csvContent = listOf(
            "# FreeWheel BLE Capture",
            "# wheel_type: KINGSONG",
            "# wheel_name: KS-S22",
            "# firmware: 3.1",
            "# capture_start: 2025_06_01_12_00_00",
            "# app_version: 2.0.0",
            "timestamp_ms,direction,length,hex_data,marker",
            "1000,RX,20,AA55001000000000000000000000000000000000,"
        ).joinToString("\n")

        val capture = reader.parse(csvContent)
        assertNotNull(capture)
        assertEquals(WheelType.KINGSONG, capture.header.wheelType)
        assertEquals("KS-S22", capture.header.wheelName)
    }

    @Test
    fun headerWithMissingOptionalFields() {
        val csvContent = listOf(
            "# FreeWheel BLE Capture",
            "# wheel_type: GOTWAY",
            "timestamp_ms,direction,length,hex_data,marker"
        ).joinToString("\n")

        val header = reader.parseHeader(csvContent)
        assertNotNull(header)
        assertEquals(WheelType.GOTWAY, header.wheelType)
        assertEquals("", header.wheelName)
        assertEquals("", header.firmware)
        assertEquals("", header.appVersion)
    }
}
