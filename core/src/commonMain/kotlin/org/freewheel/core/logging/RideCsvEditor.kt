package org.freewheel.core.logging

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.abs

/**
 * Operations for stitching (merging) and splitting ride CSV files.
 *
 * Works on raw CSV strings to preserve all columns (unlike [CsvParser] which
 * only extracts charting fields). Platforms handle file I/O — this object
 * is pure string-in/string-out.
 */
object RideCsvEditor {

    data class StitchResult(
        val mergedCsv: String,
        val metadata: RideMetadata,
    )

    data class SplitResult(
        val firstCsv: String,
        val secondCsv: String,
        val firstMetadata: RideMetadata,
        val secondMetadata: RideMetadata,
    )

    /**
     * Merge multiple ride CSVs into one, in chronological order.
     *
     * - Header: GPS format if any ride has GPS columns; non-GPS rows get empty GPS fields.
     * - Distance column: offset to continue from previous ride's last distance.
     * - totaldistance column: preserved as-is (wheel odometer).
     *
     * @param csvContents At least 2 raw CSV file contents.
     * @param mergedFileName File name for the merged ride (without path).
     * @throws IllegalArgumentException if fewer than 2 CSVs.
     */
    fun stitch(csvContents: List<String>, mergedFileName: String): StitchResult {
        require(csvContents.size >= 2) { "Need at least 2 rides to stitch" }

        // Parse each file into header info + data rows
        val parsed = csvContents.map { parseFile(it) }
        val anyGps = parsed.any { it.hasGps }
        val outputHeader = if (anyGps) CsvFormatter.header(true) else CsvFormatter.header(false)
        val outputCols = outputHeader.split(",")
        val distIdx = outputCols.indexOf("distance")

        // Collect all timestamped rows, adjusting GPS columns and tracking file of origin
        data class TimestampedRow(val timestampMs: Long, val columns: MutableList<String>, val fileIndex: Int)

        val allRows = mutableListOf<TimestampedRow>()

        for ((fileIndex, file) in parsed.withIndex()) {
            for (row in file.dataRows) {
                val cols = row.split(",").toMutableList()
                val ts = parseTimestamp(cols[file.dateIdx], cols[file.timeIdx]) ?: continue

                // Normalize to output column layout
                val normalizedCols = if (anyGps && !file.hasGps) {
                    // Insert 6 empty GPS columns after time (index 2)
                    val result = mutableListOf<String>()
                    result.add(cols[0]) // date
                    result.add(cols[1]) // time
                    repeat(6) { result.add("") } // empty GPS columns
                    result.addAll(cols.subList(2, cols.size)) // telemetry columns
                    result
                } else {
                    cols
                }

                allRows.add(TimestampedRow(ts, normalizedCols, fileIndex))
            }
        }

        // Sort chronologically
        allRows.sortBy { it.timestampMs }

        // Determine distance offsets per file by processing files in chronological order
        // of their first row. Each file's distances get offset by the cumulative distance
        // of all earlier files.
        data class FileDistanceInfo(val fileIndex: Int, val lastDistance: Long)

        val fileLastDistances = mutableMapOf<Int, Long>()
        for (file in parsed.withIndex()) {
            val lastRow = file.value.dataRows.lastOrNull() ?: continue
            val cols = lastRow.split(",")
            val dIdx = file.value.headerCols.indexOf("distance")
            if (dIdx >= 0 && dIdx < cols.size) {
                fileLastDistances[file.index] = cols[dIdx].toLongOrNull() ?: 0L
            }
        }

        // Find the chronological order of files by their first row timestamp
        val fileFirstTimestamp = mutableMapOf<Int, Long>()
        for (row in allRows) {
            if (row.fileIndex !in fileFirstTimestamp) {
                fileFirstTimestamp[row.fileIndex] = row.timestampMs
            }
        }
        val filesInOrder = fileFirstTimestamp.entries.sortedBy { it.value }.map { it.key }

        // Build cumulative offset per file
        val fileDistanceOffset = mutableMapOf<Int, Long>()
        var cumulativeOffset = 0L
        for (fileIdx in filesInOrder) {
            fileDistanceOffset[fileIdx] = cumulativeOffset
            cumulativeOffset += fileLastDistances[fileIdx] ?: 0L
        }

        // Apply distance offsets
        if (distIdx >= 0) {
            for (row in allRows) {
                val offset = fileDistanceOffset[row.fileIndex] ?: 0L
                if (offset > 0 && distIdx < row.columns.size) {
                    val origDist = row.columns[distIdx].toLongOrNull() ?: 0L
                    row.columns[distIdx] = (origDist + offset).toString()
                }
            }
        }

        // Build output
        val mergedCsv = buildString {
            appendLine(outputHeader)
            for (row in allRows) {
                appendLine(row.columns.joinToString(","))
            }
        }

        val metadata = computeMetadataFromCsv(mergedCsv, mergedFileName)
        return StitchResult(mergedCsv, metadata)
    }

    /**
     * Split a ride at the given timestamp.
     *
     * - Rows with timestamp <= [splitTimestampMs] go to first half.
     * - Rows with timestamp > [splitTimestampMs] go to second half.
     * - Second half's distance column is reset to start at 0.
     * - totaldistance preserved as-is in both halves.
     *
     * @throws IllegalArgumentException if split point results in an empty half.
     */
    fun split(
        csvContent: String,
        splitTimestampMs: Long,
        firstFileName: String,
        secondFileName: String,
    ): SplitResult {
        val file = parseFile(csvContent)
        val header = file.headerLine

        data class TimestampedRow(val timestampMs: Long, val rawLine: String)

        val rows = file.dataRows.mapNotNull { line ->
            val cols = line.split(",")
            val ts = parseTimestamp(cols[file.dateIdx], cols[file.timeIdx]) ?: return@mapNotNull null
            TimestampedRow(ts, line)
        }

        val firstRows = rows.filter { it.timestampMs <= splitTimestampMs }
        val secondRows = rows.filter { it.timestampMs > splitTimestampMs }

        require(firstRows.isNotEmpty()) { "Split point is before all samples" }
        require(secondRows.isNotEmpty()) { "Split point is after all samples" }

        // First half: distances unchanged
        val firstCsv = buildString {
            appendLine(header)
            for (row in firstRows) {
                appendLine(row.rawLine)
            }
        }

        // Second half: reset distance column
        val distIdx = file.headerCols.indexOf("distance")
        val secondCsv = buildString {
            appendLine(header)
            if (distIdx >= 0) {
                val firstSecondRowCols = secondRows[0].rawLine.split(",")
                val baseDistance = firstSecondRowCols[distIdx].toLongOrNull() ?: 0L
                for (row in secondRows) {
                    val cols = row.rawLine.split(",").toMutableList()
                    if (distIdx < cols.size) {
                        val origDist = cols[distIdx].toLongOrNull() ?: 0L
                        cols[distIdx] = (origDist - baseDistance).toString()
                    }
                    appendLine(cols.joinToString(","))
                }
            } else {
                for (row in secondRows) {
                    appendLine(row.rawLine)
                }
            }
        }

        return SplitResult(
            firstCsv = firstCsv,
            secondCsv = secondCsv,
            firstMetadata = computeMetadataFromCsv(firstCsv, firstFileName),
            secondMetadata = computeMetadataFromCsv(secondCsv, secondFileName),
        )
    }

    /**
     * Compute [RideMetadata] from raw CSV content. Full parse (no downsampling).
     */
    fun computeMetadataFromCsv(csvContent: String, fileName: String): RideMetadata {
        val file = parseFile(csvContent)

        val speedIdx = file.headerCols.indexOf("speed")
        val currentIdx = file.headerCols.indexOf("current")
        val powerIdx = file.headerCols.indexOf("power")
        val pwmIdx = file.headerCols.indexOf("pwm")
        val distIdx = file.headerCols.indexOf("distance")

        var firstTimestampMs = 0L
        var lastTimestampMs = 0L
        var maxSpeed = 0.0
        var totalSpeed = 0.0
        var maxCurrent = 0.0
        var maxPower = 0.0
        var maxPwm = 0.0
        var totalPower = 0.0
        var lastDistance = 0L
        var sampleCount = 0

        for (line in file.dataRows) {
            val cols = line.split(",")
            val ts = parseTimestamp(cols[file.dateIdx], cols[file.timeIdx]) ?: continue

            if (sampleCount == 0) firstTimestampMs = ts
            lastTimestampMs = ts

            val speed = cols.getDoubleOrZero(speedIdx)
            if (speed > maxSpeed) maxSpeed = speed

            totalSpeed += speed

            val current = abs(cols.getDoubleOrZero(currentIdx))
            if (current > maxCurrent) maxCurrent = current

            val power = abs(cols.getDoubleOrZero(powerIdx))
            if (power > maxPower) maxPower = power
            totalPower += power

            val pwm = cols.getDoubleOrZero(pwmIdx)
            if (pwm > maxPwm) maxPwm = pwm

            if (distIdx >= 0 && distIdx < cols.size) {
                cols[distIdx].toLongOrNull()?.let { lastDistance = it }
            }

            sampleCount++
        }

        if (sampleCount == 0) {
            return RideMetadata(
                fileName = fileName,
                startTimeMillis = 0,
                endTimeMillis = 0,
                durationSeconds = 0,
                distanceMeters = 0,
                maxSpeedKmh = 0.0,
                avgSpeedKmh = 0.0,
                sampleCount = 0,
            )
        }

        val durationSec = (lastTimestampMs - firstTimestampMs) / 1000L
        val avgSpeed = totalSpeed / sampleCount
        val avgPower = totalPower / sampleCount
        val consumptionWh = if (durationSec > 0) avgPower * durationSec / 3600.0 else 0.0
        val consumptionWhPerKm = if (lastDistance > 0) consumptionWh * 1000.0 / lastDistance else 0.0

        return RideMetadata(
            fileName = fileName,
            startTimeMillis = firstTimestampMs,
            endTimeMillis = lastTimestampMs,
            durationSeconds = durationSec,
            distanceMeters = lastDistance,
            maxSpeedKmh = maxSpeed,
            avgSpeedKmh = avgSpeed,
            sampleCount = sampleCount,
            maxCurrentA = maxCurrent,
            maxPowerW = maxPower,
            maxPwmPercent = maxPwm,
            consumptionWh = consumptionWh,
            consumptionWhPerKm = consumptionWhPerKm,
        )
    }

    // ==================== Internal helpers ====================

    private data class ParsedFile(
        val headerLine: String,
        val headerCols: List<String>,
        val dateIdx: Int,
        val timeIdx: Int,
        val hasGps: Boolean,
        val dataRows: List<String>,
    )

    private fun parseFile(csvContent: String): ParsedFile {
        val lines = csvContent.trimEnd().lines()
        val headerLine = lines.firstOrNull() ?: ""
        val headerCols = headerLine.split(",")
        val dateIdx = headerCols.indexOf("date")
        val timeIdx = headerCols.indexOf("time")
        val hasGps = "latitude" in headerCols
        val dataRows = if (lines.size > 1) {
            lines.subList(1, lines.size).filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        return ParsedFile(headerLine, headerCols, dateIdx, timeIdx, hasGps, dataRows)
    }

    private fun parseTimestamp(dateStr: String, timeStr: String): Long? {
        if (dateStr.length < 10) return null
        val year = dateStr.substring(0, 4).toIntOrNull() ?: return null
        val month = dateStr.substring(5, 7).toIntOrNull() ?: return null
        val day = dateStr.substring(8, 10).toIntOrNull() ?: return null

        if (timeStr.length < 8) return null
        val hour = timeStr.substring(0, 2).toIntOrNull() ?: return null
        val minute = timeStr.substring(3, 5).toIntOrNull() ?: return null
        val second = timeStr.substring(6, 8).toIntOrNull() ?: return null
        val millis = if (timeStr.length > 9) timeStr.substring(9).toIntOrNull() ?: 0 else 0

        val nanos = millis * 1_000_000
        val ldt = LocalDateTime(year, month, day, hour, minute, second, nanos)
        return ldt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    }

    private fun List<String>.getDoubleOrZero(idx: Int): Double {
        if (idx < 0 || idx >= size) return 0.0
        return this[idx].toDoubleOrNull() ?: 0.0
    }
}
