package org.freewheel.core.telemetry

/**
 * Pure-Kotlin CSV serialization for TelemetrySample lists.
 * No platform dependencies — operates on strings only.
 */
object TelemetryCsvSerializer {

    private const val HEADER =
        "timestampMs,speedKmh,voltageV,currentA,powerW,temperatureC,batteryPercent,pwmPercent,gpsSpeedKmh"

    fun serialize(samples: List<TelemetrySample>): String {
        val sb = StringBuilder(samples.size * 80 + HEADER.length + 2)
        sb.appendLine(HEADER)
        for (s in samples) {
            sb.append(s.timestampMs).append(',')
            sb.append(s.speedKmh).append(',')
            sb.append(s.voltageV).append(',')
            sb.append(s.currentA).append(',')
            sb.append(s.powerW).append(',')
            sb.append(s.temperatureC).append(',')
            sb.append(s.batteryPercent).append(',')
            sb.append(s.pwmPercent).append(',')
            sb.appendLine(s.gpsSpeedKmh)
        }
        return sb.toString()
    }

    /**
     * Deserialize CSV text into a list of samples.
     * Malformed lines are silently skipped.
     * Samples older than [maxAgeMs] relative to [nowMs] are dropped.
     */
    fun deserialize(csv: String, nowMs: Long = Long.MAX_VALUE, maxAgeMs: Long = Long.MAX_VALUE): List<TelemetrySample> {
        val lines = csv.lines()
        if (lines.isEmpty()) return emptyList()

        val result = mutableListOf<TelemetrySample>()
        val cutoff = if (maxAgeMs < Long.MAX_VALUE) nowMs - maxAgeMs else Long.MIN_VALUE

        // Skip header line
        val startIndex = if (lines.firstOrNull()?.startsWith("timestampMs") == true) 1 else 0

        for (i in startIndex until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val sample = parseLine(line) ?: continue
            // Drop expired and future samples
            if (sample.timestampMs < cutoff) continue
            if (sample.timestampMs > nowMs && nowMs < Long.MAX_VALUE) continue
            result.add(sample)
        }
        return result
    }

    private fun parseLine(line: String): TelemetrySample? {
        val parts = line.split(',')
        if (parts.size < 8) return null
        return try {
            TelemetrySample(
                timestampMs = parts[0].trim().toLong(),
                speedKmh = parts[1].trim().toDouble(),
                voltageV = parts[2].trim().toDouble(),
                currentA = parts[3].trim().toDouble(),
                powerW = parts[4].trim().toDouble(),
                temperatureC = parts[5].trim().toDouble(),
                batteryPercent = parts[6].trim().toDouble(),
                pwmPercent = parts[7].trim().toDouble(),
                gpsSpeedKmh = if (parts.size > 8) parts[8].trim().toDouble() else 0.0
            )
        } catch (_: NumberFormatException) {
            null
        }
    }
}
