package org.freewheel.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RideCsvEditorTest {

    // ==================== Test Data Helpers ====================

    private val headerNoGps =
        "date,time,speed,voltage,phase_current,current,power,torque,pwm," +
            "battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert"

    private val headerGps =
        "date,time,latitude,longitude,gps_speed,gps_alt,gps_heading,gps_distance," +
            "speed,voltage,phase_current,current,power,torque,pwm," +
            "battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert"

    private fun noGpsRow(
        date: String = "2024-01-15",
        time: String = "10:30:00.000",
        speed: String = "25.00",
        voltage: String = "84.00",
        current: String = "12.00",
        power: String = "1000.00",
        pwm: String = "40.00",
        distance: String = "0",
        totalDistance: String = "50000",
    ): String =
        "$date,$time,$speed,$voltage,0.00,$current,$power,0.00,$pwm," +
            "80,$distance,$totalDistance,35,28,2.50,-1.30,Sport,"

    private fun gpsRow(
        date: String = "2024-01-15",
        time: String = "10:30:00.000",
        speed: String = "25.00",
        voltage: String = "84.00",
        current: String = "12.00",
        power: String = "1000.00",
        pwm: String = "40.00",
        distance: String = "0",
        totalDistance: String = "50000",
        lat: String = "37.7749",
        lon: String = "-122.4194",
        gpsSpeed: String = "24.00",
    ): String =
        "$date,$time,$lat,$lon,$gpsSpeed,50.00,90.00,1234," +
            "$speed,$voltage,0.00,$current,$power,0.00,$pwm," +
            "80,$distance,$totalDistance,35,28,2.50,-1.30,Sport,"

    // ==================== computeMetadataFromCsv ====================

    @Test
    fun `computeMetadata returns correct stats for single-row ride`() {
        val csv = "$headerNoGps\n${noGpsRow(distance = "100")}"
        val meta = RideCsvEditor.computeMetadataFromCsv(csv, "test.csv")

        assertEquals("test.csv", meta.fileName)
        assertEquals(1, meta.sampleCount)
        assertEquals(25.0, meta.maxSpeedKmh, 0.01)
        assertEquals(25.0, meta.avgSpeedKmh, 0.01)
        assertEquals(12.0, meta.maxCurrentA, 0.01)
        assertEquals(1000.0, meta.maxPowerW, 0.01)
        assertEquals(40.0, meta.maxPwmPercent, 0.01)
        assertEquals(100L, meta.distanceMeters)
    }

    @Test
    fun `computeMetadata calculates max and avg across multiple rows`() {
        val csv = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", speed = "20.00", current = "10.00", power = "800.00", pwm = "30.00", distance = "0"))
            appendLine(noGpsRow(time = "10:30:01.000", speed = "30.00", current = "15.00", power = "1200.00", pwm = "50.00", distance = "100"))
            appendLine(noGpsRow(time = "10:30:02.000", speed = "25.00", current = "8.00", power = "600.00", pwm = "35.00", distance = "250"))
        }
        val meta = RideCsvEditor.computeMetadataFromCsv(csv, "multi.csv")

        assertEquals(3, meta.sampleCount)
        assertEquals(30.0, meta.maxSpeedKmh, 0.01)
        assertEquals(25.0, meta.avgSpeedKmh, 0.01) // (20+30+25)/3
        assertEquals(15.0, meta.maxCurrentA, 0.01)
        assertEquals(1200.0, meta.maxPowerW, 0.01)
        assertEquals(50.0, meta.maxPwmPercent, 0.01)
        assertEquals(250L, meta.distanceMeters)
        assertEquals(2L, meta.durationSeconds)
    }

    @Test
    fun `computeMetadata calculates energy consumption`() {
        val csv = buildString {
            appendLine(headerNoGps)
            // 2 samples, 1 second apart, power = 3600W → avgPower=3600, duration=1s → 1 Wh
            appendLine(noGpsRow(time = "10:30:00.000", power = "3600.00", distance = "0"))
            appendLine(noGpsRow(time = "10:30:01.000", power = "3600.00", distance = "1000"))
        }
        val meta = RideCsvEditor.computeMetadataFromCsv(csv, "energy.csv")

        assertEquals(1.0, meta.consumptionWh, 0.01) // 3600W * 1s / 3600 = 1Wh
        assertEquals(1.0, meta.consumptionWhPerKm, 0.01) // 1Wh / 1km
    }

    @Test
    fun `computeMetadata uses absolute current for max`() {
        val csv = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", current = "-20.00"))
            appendLine(noGpsRow(time = "10:30:01.000", current = "5.00"))
        }
        val meta = RideCsvEditor.computeMetadataFromCsv(csv, "regen.csv")

        assertEquals(20.0, meta.maxCurrentA, 0.01) // abs(-20)
    }

    @Test
    fun `computeMetadata handles empty CSV`() {
        val csv = headerNoGps
        val meta = RideCsvEditor.computeMetadataFromCsv(csv, "empty.csv")

        assertEquals(0, meta.sampleCount)
        assertEquals(0L, meta.distanceMeters)
        assertEquals(0L, meta.durationSeconds)
    }

    // ==================== Stitch ====================

    @Test
    fun `stitch two non-GPS rides merges rows chronologically`() {
        val csv1 = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", speed = "20.00", distance = "0"))
            appendLine(noGpsRow(time = "10:30:01.000", speed = "25.00", distance = "50"))
        }
        val csv2 = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:35:00.000", speed = "30.00", distance = "0"))
            appendLine(noGpsRow(time = "10:35:01.000", speed = "35.00", distance = "80"))
        }
        val result = RideCsvEditor.stitch(listOf(csv1, csv2), "merged.csv")

        val lines = result.mergedCsv.trimEnd().lines()
        assertEquals(5, lines.size) // header + 4 data rows
        assertEquals(headerNoGps, lines[0])

        // Rows should be in chronological order
        assertTrue(lines[1].contains("10:30:00"))
        assertTrue(lines[2].contains("10:30:01"))
        assertTrue(lines[3].contains("10:35:00"))
        assertTrue(lines[4].contains("10:35:01"))
    }

    @Test
    fun `stitch adjusts distance column for continuation`() {
        val csv1 = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", distance = "0"))
            appendLine(noGpsRow(time = "10:30:01.000", distance = "100"))
        }
        val csv2 = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:35:00.000", distance = "0"))
            appendLine(noGpsRow(time = "10:35:01.000", distance = "80"))
        }
        val result = RideCsvEditor.stitch(listOf(csv1, csv2), "merged.csv")

        val lines = result.mergedCsv.trimEnd().lines()
        val cols3 = lines[3].split(",")
        val cols4 = lines[4].split(",")
        val distIdx = headerNoGps.split(",").indexOf("distance")
        // Second ride's distances should be offset by csv1's last distance (100)
        assertEquals("100", cols3[distIdx])  // 0 + 100
        assertEquals("180", cols4[distIdx])  // 80 + 100
    }

    @Test
    fun `stitch preserves totaldistance as-is`() {
        val csv1 = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", totalDistance = "50000"))
        }
        val csv2 = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:35:00.000", totalDistance = "50200"))
        }
        val result = RideCsvEditor.stitch(listOf(csv1, csv2), "merged.csv")

        val lines = result.mergedCsv.trimEnd().lines()
        val tdIdx = headerNoGps.split(",").indexOf("totaldistance")
        assertEquals("50000", lines[1].split(",")[tdIdx])
        assertEquals("50200", lines[2].split(",")[tdIdx])
    }

    @Test
    fun `stitch two GPS rides preserves GPS columns`() {
        val csv1 = buildString {
            appendLine(headerGps)
            appendLine(gpsRow(time = "10:30:00.000", lat = "37.7749", lon = "-122.4194"))
        }
        val csv2 = buildString {
            appendLine(headerGps)
            appendLine(gpsRow(time = "10:35:00.000", lat = "37.7750", lon = "-122.4190"))
        }
        val result = RideCsvEditor.stitch(listOf(csv1, csv2), "merged.csv")

        val lines = result.mergedCsv.trimEnd().lines()
        assertEquals(headerGps, lines[0])
        assertTrue(lines[1].contains("37.7749"))
        assertTrue(lines[2].contains("37.7750"))
    }

    @Test
    fun `stitch mixed GPS and non-GPS uses GPS header and fills empty GPS columns`() {
        val csv1 = buildString {
            appendLine(headerGps)
            appendLine(gpsRow(time = "10:30:00.000", lat = "37.7749", lon = "-122.4194"))
        }
        val csv2 = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:35:00.000", speed = "30.00"))
        }
        val result = RideCsvEditor.stitch(listOf(csv1, csv2), "merged.csv")

        val lines = result.mergedCsv.trimEnd().lines()
        assertEquals(headerGps, lines[0])
        // First row has GPS data
        assertTrue(lines[1].contains("37.7749"))
        // Second row should have empty GPS columns but valid speed
        val cols = lines[2].split(",")
        // GPS columns (indices 2-7) should be empty
        assertEquals("", cols[2]) // latitude
        assertEquals("", cols[3]) // longitude
        // Speed should still be present at GPS header's speed index (8)
        assertEquals("30.00", cols[8])
    }

    @Test
    fun `stitch computes correct merged metadata`() {
        val csv1 = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", speed = "20.00", current = "10.00", power = "800.00", distance = "0"))
            appendLine(noGpsRow(time = "10:30:01.000", speed = "30.00", current = "5.00", power = "400.00", distance = "100"))
        }
        val csv2 = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:35:00.000", speed = "25.00", current = "15.00", power = "1200.00", distance = "0"))
            appendLine(noGpsRow(time = "10:35:01.000", speed = "35.00", current = "8.00", power = "600.00", distance = "80"))
        }
        val result = RideCsvEditor.stitch(listOf(csv1, csv2), "merged.csv")
        val meta = result.metadata

        assertEquals("merged.csv", meta.fileName)
        assertEquals(4, meta.sampleCount)
        assertEquals(35.0, meta.maxSpeedKmh, 0.01)
        assertEquals(27.5, meta.avgSpeedKmh, 0.01) // (20+30+25+35)/4
        assertEquals(15.0, meta.maxCurrentA, 0.01)
        assertEquals(1200.0, meta.maxPowerW, 0.01)
        // Total distance = csv1 last (100) + csv2 last (80) = 180
        assertEquals(180L, meta.distanceMeters)
    }

    @Test
    fun `stitch sorts rides passed out of chronological order`() {
        val csvEarlier = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000"))
        }
        val csvLater = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:35:00.000"))
        }
        // Pass later first, earlier second
        val result = RideCsvEditor.stitch(listOf(csvLater, csvEarlier), "merged.csv")

        val lines = result.mergedCsv.trimEnd().lines()
        assertTrue(lines[1].contains("10:30:00"))
        assertTrue(lines[2].contains("10:35:00"))
    }

    @Test
    fun `stitch fewer than 2 rides throws IllegalArgumentException`() {
        val csv = "$headerNoGps\n${noGpsRow()}"
        assertFailsWith<IllegalArgumentException> {
            RideCsvEditor.stitch(listOf(csv), "merged.csv")
        }
    }

    @Test
    fun `stitch with header-only ride skips empty file gracefully`() {
        val csv1 = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", distance = "0"))
            appendLine(noGpsRow(time = "10:30:01.000", distance = "50"))
        }
        val csv2 = headerNoGps // header only, no data rows

        val result = RideCsvEditor.stitch(listOf(csv1, csv2), "merged.csv")

        val lines = result.mergedCsv.trimEnd().lines()
        assertEquals(3, lines.size) // header + 2 data rows from csv1
    }

    // ==================== Split ====================

    @Test
    fun `split at midpoint creates two halves`() {
        val csv = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", speed = "20.00", distance = "0"))
            appendLine(noGpsRow(time = "10:30:01.000", speed = "25.00", distance = "50"))
            appendLine(noGpsRow(time = "10:30:02.000", speed = "30.00", distance = "120"))
            appendLine(noGpsRow(time = "10:30:03.000", speed = "35.00", distance = "200"))
        }

        // Parse first row's timestamp for reference, then split between row 2 and 3
        val samples = CsvParser.parse(csv)
        val splitTs = samples[1].timestampMs // split after second row

        val result = RideCsvEditor.split(csv, splitTs, "first.csv", "second.csv")

        val firstLines = result.firstCsv.trimEnd().lines()
        val secondLines = result.secondCsv.trimEnd().lines()

        // First half: header + 2 rows (ts <= splitTs)
        assertEquals(3, firstLines.size)
        assertTrue(firstLines[1].contains("10:30:00"))
        assertTrue(firstLines[2].contains("10:30:01"))

        // Second half: header + 2 rows (ts > splitTs)
        assertEquals(3, secondLines.size)
        assertTrue(secondLines[1].contains("10:30:02"))
        assertTrue(secondLines[2].contains("10:30:03"))
    }

    @Test
    fun `split preserves header format`() {
        val csv = buildString {
            appendLine(headerGps)
            appendLine(gpsRow(time = "10:30:00.000"))
            appendLine(gpsRow(time = "10:30:01.000"))
        }
        val samples = CsvParser.parse(csv)
        val splitTs = samples[0].timestampMs

        val result = RideCsvEditor.split(csv, splitTs, "first.csv", "second.csv")

        assertEquals(headerGps, result.firstCsv.trimEnd().lines()[0])
        assertEquals(headerGps, result.secondCsv.trimEnd().lines()[0])
    }

    @Test
    fun `split resets distance column for second segment`() {
        val csv = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", distance = "0"))
            appendLine(noGpsRow(time = "10:30:01.000", distance = "100"))
            appendLine(noGpsRow(time = "10:30:02.000", distance = "200"))
            appendLine(noGpsRow(time = "10:30:03.000", distance = "350"))
        }
        val samples = CsvParser.parse(csv)
        val splitTs = samples[1].timestampMs

        val result = RideCsvEditor.split(csv, splitTs, "first.csv", "second.csv")

        val distIdx = headerNoGps.split(",").indexOf("distance")

        // First half distances unchanged
        val firstLines = result.firstCsv.trimEnd().lines()
        assertEquals("0", firstLines[1].split(",")[distIdx])
        assertEquals("100", firstLines[2].split(",")[distIdx])

        // Second half distances reset (subtract 200 from each)
        val secondLines = result.secondCsv.trimEnd().lines()
        assertEquals("0", secondLines[1].split(",")[distIdx])
        assertEquals("150", secondLines[2].split(",")[distIdx])
    }

    @Test
    fun `split preserves totaldistance in both halves`() {
        val csv = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", totalDistance = "50000"))
            appendLine(noGpsRow(time = "10:30:01.000", totalDistance = "50100"))
            appendLine(noGpsRow(time = "10:30:02.000", totalDistance = "50200"))
        }
        val samples = CsvParser.parse(csv)
        val splitTs = samples[0].timestampMs

        val result = RideCsvEditor.split(csv, splitTs, "first.csv", "second.csv")

        val tdIdx = headerNoGps.split(",").indexOf("totaldistance")

        assertEquals("50000", result.firstCsv.trimEnd().lines()[1].split(",")[tdIdx])
        assertEquals("50100", result.secondCsv.trimEnd().lines()[1].split(",")[tdIdx])
        assertEquals("50200", result.secondCsv.trimEnd().lines()[2].split(",")[tdIdx])
    }

    @Test
    fun `split computes metadata for each half`() {
        val csv = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000", speed = "20.00", current = "10.00", power = "800.00", distance = "0"))
            appendLine(noGpsRow(time = "10:30:01.000", speed = "30.00", current = "15.00", power = "1200.00", distance = "100"))
            appendLine(noGpsRow(time = "10:30:02.000", speed = "25.00", current = "8.00", power = "600.00", distance = "200"))
            appendLine(noGpsRow(time = "10:30:03.000", speed = "35.00", current = "20.00", power = "1500.00", distance = "350"))
        }
        val samples = CsvParser.parse(csv)
        val splitTs = samples[1].timestampMs

        val result = RideCsvEditor.split(csv, splitTs, "first.csv", "second.csv")

        // First half: rows 0-1
        assertEquals("first.csv", result.firstMetadata.fileName)
        assertEquals(2, result.firstMetadata.sampleCount)
        assertEquals(30.0, result.firstMetadata.maxSpeedKmh, 0.01)
        assertEquals(25.0, result.firstMetadata.avgSpeedKmh, 0.01)
        assertEquals(15.0, result.firstMetadata.maxCurrentA, 0.01)
        assertEquals(100L, result.firstMetadata.distanceMeters)

        // Second half: rows 2-3
        assertEquals("second.csv", result.secondMetadata.fileName)
        assertEquals(2, result.secondMetadata.sampleCount)
        assertEquals(35.0, result.secondMetadata.maxSpeedKmh, 0.01)
        assertEquals(30.0, result.secondMetadata.avgSpeedKmh, 0.01)
        assertEquals(20.0, result.secondMetadata.maxCurrentA, 0.01)
        assertEquals(150L, result.secondMetadata.distanceMeters) // 350-200 = 150
    }

    @Test
    fun `split outside range throws IllegalArgumentException`() {
        val csv = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000"))
            appendLine(noGpsRow(time = "10:30:01.000"))
        }
        val samples = CsvParser.parse(csv)

        // Before first sample
        assertFailsWith<IllegalArgumentException> {
            RideCsvEditor.split(csv, samples[0].timestampMs - 10000, "a.csv", "b.csv")
        }
        // After last sample
        assertFailsWith<IllegalArgumentException> {
            RideCsvEditor.split(csv, samples[1].timestampMs + 10000, "a.csv", "b.csv")
        }
    }

    @Test
    fun `split at first sample puts one row in first half`() {
        val csv = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000"))
            appendLine(noGpsRow(time = "10:30:01.000"))
            appendLine(noGpsRow(time = "10:30:02.000"))
        }
        val samples = CsvParser.parse(csv)
        val splitTs = samples[0].timestampMs

        val result = RideCsvEditor.split(csv, splitTs, "first.csv", "second.csv")

        assertEquals(2, result.firstCsv.trimEnd().lines().size) // header + 1 row
        assertEquals(3, result.secondCsv.trimEnd().lines().size) // header + 2 rows
    }

    @Test
    fun `split at last sample puts one row in second half`() {
        val csv = buildString {
            appendLine(headerNoGps)
            appendLine(noGpsRow(time = "10:30:00.000"))
            appendLine(noGpsRow(time = "10:30:01.000"))
            appendLine(noGpsRow(time = "10:30:02.000"))
        }
        val samples = CsvParser.parse(csv)
        // Split just before last — last row's ts is samples[2].timestampMs
        // rows with ts <= samples[1].timestampMs go to first half
        val splitTs = samples[1].timestampMs

        val result = RideCsvEditor.split(csv, splitTs, "first.csv", "second.csv")

        assertEquals(3, result.firstCsv.trimEnd().lines().size) // header + 2 rows
        assertEquals(2, result.secondCsv.trimEnd().lines().size) // header + 1 row
    }
}
