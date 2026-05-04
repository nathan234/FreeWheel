package org.freewheel.core.logging

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.abs

/**
 * Single-pass derivation of [RideMetadata] from a CSV file.
 *
 * Used at app launch to recover rides whose stop path was skipped (app
 * killed, crash, or missed state transition). Mirrors the metric tracking
 * in [RideLogger.stop] but doesn't materialize the full sample list, so
 * it's safe for very long rides and runs in milliseconds.
 *
 * Returns null when the file has no usable header or zero parseable rows.
 */
object RideRecovery {

    fun deriveMetadata(csvContent: String, fileName: String): RideMetadata? {
        val lines = csvContent.lineSequence().iterator()
        if (!lines.hasNext()) return null

        val headers = lines.next().split(",")
        val col = HashMap<String, Int>(headers.size)
        headers.forEachIndexed { i, name -> col[name.trim()] = i }

        val dateIdx = col[CsvColumns.DATE] ?: return null
        val timeIdx = col[CsvColumns.TIME] ?: return null
        val speedIdx = col[CsvColumns.SPEED] ?: return null
        val currentIdx = col[CsvColumns.CURRENT]
        val powerIdx = col[CsvColumns.POWER]
        val pwmIdx = col[CsvColumns.PWM]
        val tripDistIdx = col[CsvColumns.DISTANCE]

        val tz = TimeZone.currentSystemDefault()

        var firstTs = -1L
        var lastTs = -1L
        var maxSpeed = 0.0
        var sumSpeed = 0.0
        var maxCurrent = 0.0
        var maxPower = 0.0
        var sumPower = 0.0
        var maxPwm = 0.0
        var lastDistanceM = 0L
        var n = 0

        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) continue
            val cols = line.split(",")
            if (cols.size <= speedIdx) continue

            val ts = parseTimestamp(cols.getOrNull(dateIdx), cols.getOrNull(timeIdx), tz)
                ?: continue
            if (firstTs < 0) firstTs = ts
            lastTs = ts

            val s = cols[speedIdx].toDoubleOrNull() ?: 0.0
            if (s > maxSpeed) maxSpeed = s
            sumSpeed += s

            currentIdx?.let { cols.getOrNull(it)?.toDoubleOrNull() }?.let {
                val a = abs(it); if (a > maxCurrent) maxCurrent = a
            }
            powerIdx?.let { cols.getOrNull(it)?.toDoubleOrNull() }?.let {
                val p = abs(it)
                if (p > maxPower) maxPower = p
                sumPower += p
            }
            pwmIdx?.let { cols.getOrNull(it)?.toDoubleOrNull() }
                ?.let { if (it > maxPwm) maxPwm = it }
            tripDistIdx?.let { cols.getOrNull(it)?.toLongOrNull() }
                ?.let { lastDistanceM = it }

            n++
        }

        if (n == 0 || firstTs < 0) return null

        val durationSec = ((lastTs - firstTs) / 1000L).coerceAtLeast(0L)
        val avgSpeed = sumSpeed / n
        val avgPower = sumPower / n
        val whConsumed = if (durationSec > 0) avgPower * durationSec / 3600.0 else 0.0
        val whPerKm = if (lastDistanceM > 0) whConsumed * 1000.0 / lastDistanceM else 0.0

        return RideMetadata(
            fileName = fileName,
            startTimeMillis = firstTs,
            endTimeMillis = lastTs,
            durationSeconds = durationSec,
            distanceMeters = lastDistanceM,
            maxSpeedKmh = maxSpeed,
            avgSpeedKmh = avgSpeed,
            sampleCount = n,
            maxCurrentA = maxCurrent,
            maxPowerW = maxPower,
            maxPwmPercent = maxPwm,
            consumptionWh = whConsumed,
            consumptionWhPerKm = whPerKm,
        )
    }

    private fun parseTimestamp(date: String?, time: String?, tz: TimeZone): Long? {
        if (date == null || time == null || date.length < 10 || time.length < 8) return null
        val y = date.substring(0, 4).toIntOrNull() ?: return null
        val mo = date.substring(5, 7).toIntOrNull() ?: return null
        val d = date.substring(8, 10).toIntOrNull() ?: return null
        val h = time.substring(0, 2).toIntOrNull() ?: return null
        val mi = time.substring(3, 5).toIntOrNull() ?: return null
        val s = time.substring(6, 8).toIntOrNull() ?: return null
        val ms = if (time.length > 9) time.substring(9).toIntOrNull() ?: 0 else 0
        return LocalDateTime(y, mo, d, h, mi, s, ms * 1_000_000)
            .toInstant(tz).toEpochMilliseconds()
    }
}
