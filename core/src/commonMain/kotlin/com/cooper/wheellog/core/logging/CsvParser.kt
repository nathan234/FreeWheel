package com.cooper.wheellog.core.logging

import com.cooper.wheellog.core.telemetry.TelemetrySample
import com.cooper.wheellog.core.utils.Logger
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Parses ride CSV files (written by [CsvFormatter]) into [TelemetrySample] lists.
 * Handles both GPS and non-GPS column layouts by reading the header line.
 */
object CsvParser {

    private const val MAX_CHART_SAMPLES = 3600

    /**
     * Parse CSV content into a list of [TelemetrySample].
     * Long rides (> [MAX_CHART_SAMPLES]) are downsampled.
     *
     * @param csvContent Full CSV file content as a string.
     * @return Parsed samples, possibly downsampled. Empty list if parsing fails.
     */
    fun parse(csvContent: String): List<TelemetrySample> {
        val lines = csvContent.lineSequence().iterator()
        if (!lines.hasNext()) return emptyList()

        val headerLine = lines.next()
        val headers = headerLine.split(",")
        val colIndex = HashMap<String, Int>(headers.size)
        headers.forEachIndexed { i, name -> colIndex[name.trim()] = i }

        val dateIdx = colIndex["date"] ?: return emptyList()
        val timeIdx = colIndex["time"] ?: return emptyList()
        val speedIdx = colIndex["speed"] ?: return emptyList()
        val voltageIdx = colIndex["voltage"]
        val currentIdx = colIndex["current"]
        val powerIdx = colIndex["power"]
        val tempIdx = colIndex["system_temp"]
        val batteryIdx = colIndex["battery_level"]
        val pwmIdx = colIndex["pwm"]
        val gpsSpeedIdx = colIndex["gps_speed"]

        val tz = TimeZone.currentSystemDefault()
        val samples = mutableListOf<TelemetrySample>()

        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size <= speedIdx) continue

            try {
                val timestampMs = parseTimestamp(cols[dateIdx], cols[timeIdx], tz) ?: continue

                samples.add(TelemetrySample(
                    timestampMs = timestampMs,
                    speedKmh = cols[speedIdx].toDoubleOrNull() ?: 0.0,
                    voltageV = voltageIdx?.let { cols.getOrNull(it)?.toDoubleOrNull() } ?: 0.0,
                    currentA = currentIdx?.let { cols.getOrNull(it)?.toDoubleOrNull() } ?: 0.0,
                    powerW = powerIdx?.let { cols.getOrNull(it)?.toDoubleOrNull() } ?: 0.0,
                    temperatureC = tempIdx?.let { cols.getOrNull(it)?.toDoubleOrNull() } ?: 0.0,
                    batteryPercent = batteryIdx?.let { cols.getOrNull(it)?.toDoubleOrNull() } ?: 0.0,
                    pwmPercent = pwmIdx?.let { cols.getOrNull(it)?.toDoubleOrNull() } ?: 0.0,
                    gpsSpeedKmh = gpsSpeedIdx?.let { cols.getOrNull(it)?.toDoubleOrNull() } ?: 0.0,
                ))
            } catch (e: Exception) {
                Logger.w("CsvParser", "Skipping malformed row: ${e.message}")
            }
        }

        return downsample(samples)
    }

    private fun downsample(samples: List<TelemetrySample>): List<TelemetrySample> {
        if (samples.size <= MAX_CHART_SAMPLES) return samples
        val step = samples.size / MAX_CHART_SAMPLES
        return samples.filterIndexed { index, _ -> index % step == 0 }
    }

    /**
     * Parse "yyyy-MM-dd" + "HH:mm:ss.SSS" into epoch milliseconds.
     */
    private fun parseTimestamp(dateStr: String, timeStr: String, tz: TimeZone): Long? {
        // dateStr = "yyyy-MM-dd", timeStr = "HH:mm:ss.SSS"
        if (dateStr.length < 10) return null
        val year = dateStr.substring(0, 4).toIntOrNull() ?: return null
        val month = dateStr.substring(5, 7).toIntOrNull() ?: return null
        val day = dateStr.substring(8, 10).toIntOrNull() ?: return null

        // timeStr could be "HH:mm:ss.SSS" or "HH:mm:ss"
        if (timeStr.length < 8) return null
        val hour = timeStr.substring(0, 2).toIntOrNull() ?: return null
        val minute = timeStr.substring(3, 5).toIntOrNull() ?: return null
        val second = timeStr.substring(6, 8).toIntOrNull() ?: return null
        val millis = if (timeStr.length > 9) {
            timeStr.substring(9).toIntOrNull() ?: 0
        } else 0

        val nanos = millis * 1_000_000
        val ldt = LocalDateTime(year, month, day, hour, minute, second, nanos)
        return ldt.toInstant(tz).toEpochMilliseconds()
    }
}
