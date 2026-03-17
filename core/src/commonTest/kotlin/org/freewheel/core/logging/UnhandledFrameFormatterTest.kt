package org.freewheel.core.logging

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class UnhandledFrameFormatterTest {

    @Test
    fun `empty entries returns null`() {
        val result = UnhandledFrameFormatter.format(
            entries = emptyList(),
            wheelType = "KINGSONG",
            model = "S18",
            firmware = "1.0",
            platform = "android"
        )
        assertNull(result)
    }

    @Test
    fun `single entry produces CSV with metadata headers`() {
        val entry = UnhandledFrameEntry(
            frameHex = "AA55140002",
            frameSize = 5,
            reason = "unknown frame type 0x14",
            firstSeenMs = 1710600000000L,
            lastSeenMs = 1710600001000L,
            count = 3
        )

        val result = UnhandledFrameFormatter.format(
            entries = listOf(entry),
            wheelType = "KINGSONG",
            model = "S18",
            firmware = "2.0.1",
            platform = "android"
        )

        assertNotNull(result)
        val lines = result.lines()

        // Metadata headers
        assertTrue(lines[0].startsWith("# FreeWheel Unhandled Frames"))
        assertTrue(lines.any { it == "# wheel_type: KINGSONG" })
        assertTrue(lines.any { it == "# model: S18" })
        assertTrue(lines.any { it == "# firmware: 2.0.1" })
        assertTrue(lines.any { it == "# platform: android" })
        assertTrue(lines.any { it == "# unique_frames: 1" })
        assertTrue(lines.any { it == "# total_occurrences: 3" })

        // CSV header
        assertTrue(lines.any { it == "count,first_seen,last_seen,length,reason,hex_data" })

        // Data row
        val dataLine = lines.last()
        assertTrue(dataLine.startsWith("3,"))
        assertTrue(dataLine.endsWith(",AA55140002"))
        assertTrue(dataLine.contains(",5,"))
        assertTrue(dataLine.contains("unknown frame type 0x14"))
    }

    @Test
    fun `empty model and firmware are omitted from headers`() {
        val entry = UnhandledFrameEntry(
            frameHex = "01",
            frameSize = 1,
            reason = "r",
            firstSeenMs = 0L,
            lastSeenMs = 0L,
            count = 1
        )

        val result = UnhandledFrameFormatter.format(
            entries = listOf(entry),
            wheelType = "GOTWAY",
            model = "",
            firmware = "",
            platform = "ios"
        )

        assertNotNull(result)
        assertFalse(result.contains("# model:"))
        assertFalse(result.contains("# firmware:"))
        assertTrue(result.contains("# wheel_type: GOTWAY"))
        assertTrue(result.contains("# platform: ios"))
    }

    @Test
    fun `multiple entries produce multiple data rows`() {
        val entries = listOf(
            UnhandledFrameEntry("AA", 1, "reason A", 1000L, 1000L, 1),
            UnhandledFrameEntry("BB", 1, "reason B", 2000L, 2000L, 5),
            UnhandledFrameEntry("CC", 1, "reason C", 3000L, 3000L, 10)
        )

        val result = UnhandledFrameFormatter.format(
            entries = entries,
            wheelType = "VETERAN",
            model = "Sherman Max",
            firmware = "3.0",
            platform = "android"
        )

        assertNotNull(result)
        assertTrue(result.contains("# unique_frames: 3"))
        assertTrue(result.contains("# total_occurrences: 16"))

        // Count data rows (lines after CSV header that don't start with #)
        val dataRows = result.lines().filter { !it.startsWith("#") && it != "count,first_seen,last_seen,length,reason,hex_data" }
        assertEquals(3, dataRows.size)
        assertTrue(dataRows[0].startsWith("1,"))
        assertTrue(dataRows[0].endsWith(",AA"))
        assertTrue(dataRows[1].startsWith("5,"))
        assertTrue(dataRows[2].startsWith("10,"))
    }

    @Test
    fun `commas in reason are escaped`() {
        val entry = UnhandledFrameEntry(
            frameHex = "FF",
            frameSize = 1,
            reason = "bad header, unexpected",
            firstSeenMs = 0L,
            lastSeenMs = 0L,
            count = 1
        )

        val result = UnhandledFrameFormatter.format(
            entries = listOf(entry),
            wheelType = "TEST",
            model = "",
            firmware = "",
            platform = "test"
        )

        assertNotNull(result)
        // Comma in reason should be replaced with semicolon
        val dataLine = result.lines().last()
        assertTrue(dataLine.contains("bad header; unexpected"))
        assertFalse(dataLine.contains("bad header, unexpected"))
    }
}
